# DevCompanion Agent Loop — 설계 문서 (v3)

> ⚠️ **CRITICAL REVIEW APPLIED**: 보안, 상태 관리, 토큰 예산, WebView 스레딩, UX 피드백, **인지 개선(v3)** 반영

## 1. 비전

LLM이 WebView를 **직접 조작**하는 agentic 루프 구현.

```
사용자: "이 페이지에서 로그인 폼 찾아서 이메일 입력해줘"
에이전트: [DOM 읽기] → [이메일 필드 탐지] → [type("#email", "user@example.com")] → [확인]
```

## 2. 아키텍처 개요

```
┌─────────────────────────────────────────────────────┐
│                    AiChatViewModel                   │
│                                                      │
│  ┌──────────────┐  tool_calls   ┌──────────────┐   │
│  │ LLM Adapter │◄───────────────│  ToolExecutor │   │
│  └──────┬───────┘  tool_results └──────┬───────┘   │
│         │                                │           │
│         │ text                          │ actions   │
│         ▼                                ▼           │
│  ┌──────────────┐             ┌──────────────┐      │
│  │   Chat UI    │             │   WebView    │      │
│  └──────────────┘             └──────────────┘      │
│                                                      │
│  ┌────────────────────────────────────────────────┐ │
│  │   PermissionGate (Runtime DOM Inspection)      │ │
│  │   eval_js AST 분석 + 민감 필드 실시간 검사     │ │
│  └────────────────────────────────────────────────┘ │
│                                                      │
│  ┌────────────────────────────────────────────────┐ │
│  │   State Machine: Idle → Thinking → Executing   │ │
│  └────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

## 3. 핵심 컴포넌트

### 3.1 ToolDefinition

```kotlin
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject
)

data class ToolResult(
    val id: String,
    val output: String,
    val isError: Boolean = false
)
```

### 3.2 WebView Tools (위험도 분류)

| Tool | 설명 | 위험도 | 권한 | 비고 |
|------|------|--------|------|------|
| `navigate` | URL로 이동 | 🟡 | auto | http/https only |
| `click` | CSS 셀렉터로 클릭 | 🟡 | auto | - |
| `type` | 텍스트 입력 | 🟡→🔴 | auto→confirm | **런타임 DOM 검사** |
| `scroll` | 스크롤 | 🟢 | auto | - |
| `eval_js` | JS 실행 | 🔴 | **confirm + code review** | AST 패턴 검사 |
| `get_dom` | DOM 조회 | 🟢 | auto | 마스킹 적용 |
| `screenshot` | 스크린샷 | 🟢 | auto | 민감 필드 블러 |
| `submit_form` | 폼 제출 | 🔴 | confirm | 데이터 전송 |

**type 승격 로직**: `type` 실행 시 WebView에서 실시간으로 대상 요소의 `type`, `name`, `autocomplete` 속성을 검사. 민감 필드면 자동으로 SENSITIVE로 승격.

**URL 화이트리스트**: `navigate`는 `http://`, `https://`만 허용. `file://`, `javascript:`, `data:` 차단.

### 3.3 PermissionGate (개선됨)

```kotlin
enum class ActionRisk { SAFE, MODERATE, SENSITIVE }

class PermissionGate {
    /**
     * [call] 분류. [type]은 실제 DOM 속성 검사 필요.
     */
    suspend fun classify(call: ToolCall, webView: WebView): ActionRisk = 
        when (call.name) {
            "navigate" -> checkNavigateRisk(call)
            "type" -> checkTypeRisk(call, webView)
            "eval_js" -> checkEvalRisk(call)
            "submit_form" -> SENSITIVE
            "click", "scroll", "get_dom", "screenshot" -> MODERATE
            else -> MODERATE
        }

    /**
     * navigate: URL 스킴 검사
     */
    private fun checkNavigateRisk(call: ToolCall): ActionRisk {
        val url = call.arguments["url"]?.asString ?: return SENSITIVE
        val allowed = url.startsWith("http://") || url.startsWith("https://")
        return if (allowed) MODERATE else SENSITIVE
    }

    /**
     * type: 실제 DOM 요소의 type/name/autocomplete 검사
     */
    private suspend fun checkTypeRisk(call: ToolCall, webView: WebView): ActionRisk {
        val selector = call.arguments["selector"]?.asString ?: return SENSITIVE
        
        val js = """
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return {error: 'not found'};
                return {
                    tagName: el.tagName,
                    type: el.type || null,
                    name: el.name || null,
                    autocomplete: el.autocomplete || null,
                    inputMode: el.inputMode || null
                };
            })()
        """.trimIndent()

        val result = webView.evalJs(js)
        val props = parseJsResult(result)

        val isSensitive = when {
            props["type"] == "password" -> true
            props["type"] == "hidden" -> true
            props["name"]?.contains(Regex("(?i)(password|pw|pass|secret|token|key|ssn|card|cvv)")) == true -> true
            props["autocomplete"]?.contains("cc-") == true -> true
            props["autocomplete"]?.contains("current-password") == true -> true
            props["inputMode"] == "numeric" && props["name"]?.contains("card") == true -> true
            else -> false
        }

        return if (isSensitive) SENSITIVE else MODERATE
    }

    /**
     * eval_js: AST 패턴 검사
     */
    private fun checkEvalRisk(call: ToolCall): ActionRisk {
        val code = call.arguments["expression"]?.asString ?: return SENSITIVE
        
        val dangerousPatterns = listOf(
            "document\\.cookie", "document\\.domain",
            "localStorage", "sessionStorage", "indexedDB",
            "\\bfetch\\s*\\(", "XMLHttpRequest", "WebSocket\\s*\\(",
            "navigator\\.sendBeacon", "performance\\.getEntries",
            "\\bFunction\\s*\\(", "\\beval\\s*\\(", "setTimeout\\s*\\["
        )
        
        val hasDangerous = dangerousPatterns.any { 
            code.contains(Regex(it)) 
        }
        
        return if (hasDangerous) SENSITIVE else MODERATE
    }

    /**
     * Confirmation UI에 표시할 정보
     */
    fun getConfirmationDetails(call: ToolCall, webView: WebView?): ToolConfirmationDetails {
        return when (call.name) {
            "type" -> ToolConfirmationDetails(
                action = "Type into field",
                target = call.arguments["selector"]?.asString ?: "unknown",
                preview = call.arguments["text"]?.asString?.take(50) + "...",
                riskLevel = SENSITIVE
            )
            "eval_js" -> ToolConfirmationDetails(
                action = "Execute JavaScript",
                target = "WebView",
                preview = call.arguments["expression"]?.asString?.take(200) ?: "...",
                riskLevel = SENSITIVE
            )
            "submit_form" -> ToolConfirmationDetails(
                action = "Submit form",
                target = call.arguments["selector"]?.asString ?: "unknown",
                preview = "Form data will be sent",
                riskLevel = SENSITIVE
            )
            else -> ToolConfirmationDetails(
                action = call.name,
                target = call.arguments.toString(),
                preview = "",
                riskLevel = MODERATE
            )
        }
    }
}

data class ToolConfirmationDetails(
    val action: String,
    val target: String,
    val preview: String,
    val riskLevel: ActionRisk
)
```

### 3.4 State Machine 기반 Agent Loop

```kotlin
sealed class AgentState {
    object Idle : AgentState()
    data class Thinking(val iteration: Int) : AgentState()
    data class CheckingPermission(val call: ToolCall, val iteration: Int) : AgentState()
    data class WaitingConfirmation(val call: ToolCall) : AgentState()
    data class ExecutingTool(val tool: String, val iteration: Int) : AgentState()
    data class Error(val message: String) : AgentState()
}

sealed class AgentEvent {
    data class ToolExecuted(val call: ToolCall, val result: ToolResult) : AgentEvent()
    data class TextResponse(val content: String) : AgentEvent()
    data class ConfirmationRequired(val call: ToolCall, val details: ToolConfirmationDetails) : AgentEvent()
    data class Rejected(val call: ToolCall) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    object BudgetExceeded : AgentEvent()
}

class AgentLoop(
    private val toolExecutor: ToolExecutor,
    private val permissionGate: PermissionGate
) {
    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()
    
    private var currentJob: Job? = null

    fun start(
        userMessage: String,
        webView: WebView,
        maxIterations: Int = 10,
        tokenBudget: TokenBudget = TokenBudget.default()
    ): Flow<AgentEvent> = callbackFlow {
        currentJob?.cancel()
        
        currentJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                runAgent(userMessage, webView, maxIterations, tokenBudget)
            } catch (e: CancellationException) {
                _state.value = AgentState.Idle
                throw e
            }
        }
        
        awaitClose { currentJob?.cancel() }
    }

    private suspend fun ProducerScope<AgentEvent>.runAgent(
        userMessage: String,
        webView: WebView,
        maxIterations: Int,
        tokenBudget: TokenBudget
    ) {
        _state.value = AgentState.Thinking(0)
        
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage(role = "user", content = userMessage))
        
        var consecutiveErrors = 0
        var lastAction: Pair<String, JsonObject>? = null
        var repeatCount = 0
        
        repeat(maxIterations) { iteration ->
            // Token budget check
            if (tokenBudget.isExceeded(messages)) {
                send(AgentEvent.BudgetExceeded)
                _state.value = AgentState.Error("Token budget exceeded")
                return
            }
            
            // Capture context: iteration 0만 screenshot
            val context = if (iteration == 0) {
                WebContextBuilder.buildContext(webView, CaptureMode.Standard)
            } else {
                // 후속: screenshot 없이 DOM만
                buildDomOnlyContext(webView)
            }
            
            _state.value = AgentState.Thinking(iteration + 1)
            
            // LLM call with tools
            val response = callLlmWithTools(messages, context)
            
            if (response.hasToolCalls) {
                for (call in response.toolCalls) {
                    // Loop detection
                    val actionKey = call.name to call.arguments
                    if (actionKey == lastAction) {
                        repeatCount++
                        if (repeatCount >= 3) {
                            send(AgentEvent.Error("Stuck in loop: same action repeated"))
                            _state.value = AgentState.Error("Loop detected")
                            return
                        }
                    } else {
                        repeatCount = 0
                        lastAction = actionKey
                    }
                    
                    // Permission check
                    _state.value = AgentState.CheckingPermission(call, iteration + 1)
                    val risk = permissionGate.classify(call, webView)
                    
                    if (risk == ActionRisk.SENSITIVE) {
                        val details = permissionGate.getConfirmationDetails(call, webView)
                        send(AgentEvent.ConfirmationRequired(call, details))
                        _state.value = AgentState.WaitingConfirmation(call)
                        
                        val approved = awaitUserConfirmation()
                        if (!approved) {
                            send(AgentEvent.Rejected(call))
                            messages.add(toolResult(call.id, "User rejected"))
                            _state.value = AgentState.Thinking(iteration + 1)
                            continue
                        }
                    }
                    
                    // Execute
                    _state.value = AgentState.ExecutingTool(call.name, iteration + 1)
                    val result = try {
                        toolExecutor.execute(call, webView)
                    } catch (e: Exception) {
                        ToolResult(call.id, "Error: ${e.message}", isError = true)
                    }
                    
                    // Error tracking
                    if (result.isError) {
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) {
                            send(AgentEvent.Error("Too many consecutive errors"))
                            _state.value = AgentState.Error("Max errors")
                            return
                        }
                    } else {
                        consecutiveErrors = 0
                    }
                    
                    send(AgentEvent.ToolExecuted(call, result))
                    messages.add(toolResult(call.id, result.output))
                    _state.value = AgentState.Thinking(iteration + 1)
                }
            } else {
                send(AgentEvent.TextResponse(response.content))
                _state.value = AgentState.Idle
                return
            }
        }
        
        send(AgentEvent.Error("Max iterations ($maxIterations) reached"))
        _state.value = AgentState.Error("Timeout")
    }

    fun stop() {
        currentJob?.cancel()
        _state.value = AgentState.Idle
    }
}
```

### 3.5 Token Budget 관리

```kotlin
data class TokenBudget(
    val maxInputTokens: Int = 8000,
    val maxOutputTokens: Int = 4096,
    val imageTokenCost: Int = 800  // Base64 PNG
) {
    companion object {
        fun default(model: String = "glm-5.1"): TokenBudget = when {
            model.contains("claude") -> TokenBudget(100000, 4096)
            model.contains("gpt-4") -> TokenBudget(8000, 4096)
            model.contains("glm") -> TokenBudget(8000, 4096)
            else -> TokenBudget(8000, 4096)
        }
    }

    /**
     * 대략적 토큰 수 추정 (1 token ≈ 3-4 chars)
     */
    fun estimateTokens(messages: List<ChatMessage>): Int {
        val textTokens = messages.sumOf { it.content.length / 3 }
        val imageTokens = messages.count { it.hasContext } * imageTokenCost
        return textTokens + imageTokens
    }

    fun isExceeded(messages: List<ChatMessage>): Boolean {
        return estimateTokens(messages) > maxInputTokens * 0.7
    }
}
```

### 3.6 ToolExecutor (WebView 스레드 안전)

```kotlin
class ToolExecutor {
    /**
     * 모든 WebView 조작은 Main 스레드에서 실행
     */
    suspend fun execute(call: ToolCall, webView: WebView): ToolResult = 
        withContext(Dispatchers.Main) {
            when (call.name) {
                "navigate" -> executeNavigate(call, webView)
                "click" -> executeClick(call, webView)
                "type" -> executeType(call, webView)
                "scroll" -> executeScroll(call, webView)
                "eval_js" -> executeEval(call, webView)
                "get_dom" -> executeGetDom(call, webView)
                "screenshot" -> executeScreenshot(call, webView)
                "submit_form" -> executeSubmit(call, webView)
                else -> ToolResult(call.id, "Unknown tool: ${call.name}", isError = true)
            }
        }

    private suspend fun executeNavigate(call: ToolCall, webView: WebView): ToolResult {
        val url = call.arguments["url"]?.asString ?: return error("Missing url")
        
        // URL 화이트리스트 검사
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return error("Only http/https URLs allowed")
        }
        
        webView.loadUrl(url)
        return ToolResult(call.id, "Navigated to $url")
    }

    private suspend fun executeClick(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments["selector"]?.asString ?: return error("Missing selector")
        
        val js = """
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return {success: false, error: 'Element not found'};
                el.click();
                return {success: true, tagName: el.tagName, text: el.textContent?.substring(0, 50)};
            })()
        """.trimIndent()
        
        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeType(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments["selector"]?.asString ?: return error("Missing selector")
        val text = call.arguments["text"]?.asString ?: return error("Missing text")
        val clear = call.arguments["clear"]?.asBoolean ?: true
        
        val js = """
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return {success: false, error: 'Element not found'};
                if (${if (clear) "true" else "false"}) el.value = '';
                el.value = '${text.replace("'", "\\'")}';
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                return {success: true, value: el.value?.substring(0, 50)};
            })()
        """.trimIndent()
        
        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeEval(call: ToolCall, webView: WebView): ToolResult {
        val expression = call.arguments["expression"]?.asString ?: return error("Missing expression")
        
        // 이미 PermissionGate에서 검사됨
        val result = webView.evalJs(expression)
        return ToolResult(call.id, result)
    }

    private suspend fun executeGetDom(call: ToolCall, webView: WebView): ToolResult {
        val selector = call.arguments["selector"]?.asString ?: "body"
        
        val js = """
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return {error: 'not found'};
                
                // 마스킹: password 필드 값 제거
                var html = el.outerHTML;
                html = html.replace(/type=["']password["']/gi, 'type="password" value="***"');
                
                return {
                    tagName: el.tagName,
                    outerHTML: html.substring(0, 10000),
                    textContent: el.textContent?.substring(0, 2000),
                    attributes: Array.from(el.attributes).map(a => a.name + '=' + a.value.substring(0, 100))
                };
            })()
        """.trimIndent()
        
        val result = webView.evalJs(js)
        return ToolResult(call.id, result)
    }

    private suspend fun executeScreenshot(call: ToolCall, webView: WebView): ToolResult {
        // 이미지 캡처는 WebContextBuilder 활용
        val context = WebContextBuilder.buildContext(webView, CaptureMode.Quick)
        return ToolResult(call.id, context.screenshotBase64.take(100) + "...")
    }

    private fun error(msg: String) = ToolResult("", msg, isError = true)
}

/**
 * WebView 확장: Main 스레드에서 eval 결과를 suspend로 받기
 */
suspend fun WebView.evalJs(script: String): String = suspendCancellableCoroutine { cont ->
    post {
        if (!cont.isActive) return@post
        evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resume(result ?: "")
        }
    }
}
```

## 4. UI/UX 흐름

### 4.1 에이전트 모드 진입

```
┌─────────────────────────────────────┐
│  🤖 Agent Mode    [Turn On]         │  ← 명시적 토글
├─────────────────────────────────────┤
│                                     │
│  /do login with user@test.com       │  ← 또는 "/do" 프리픽스
│                                     │
└─────────────────────────────────────┘
```

### 4.2 실행 중 UI (타임라인)

```
┌─────────────────────────────────────┐
│  🔴 Stop                            │  ← Send → Stop
├─────────────────────────────────────┤
│                                     │
│  🧠 Analyzing page...               │
│  ✓ Found login form                 │
│  🔧 click(#login-btn)               │
│  ✓ Success                           │
│  🧠 Typing email...                 │
│  🔒 type(#email) — Allow? [Yes][No] │  ← 인라인 confirmation
│  ✓ Allowed                           │
│  ✓ Typed                             │
│  🔧 submit_form(#login) — Allow?     │
│                                     │
└─────────────────────────────────────┘
```

### 4.3 Confirmation 인라인 UI

```kotlin
@Composable
fun InlineConfirmation(
    details: ToolConfirmationDetails,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    Card(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("⚠️ ${details.action}", fontWeight = FontWeight.Bold)
            }
            
            Text("Target: ${details.target}")
            
            if (details.preview.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        details.preview,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            
            Row {
                Button(onClick = onAllow) { Text("Allow Once") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onDeny) { Text("Deny") }
            }
        }
    }
}
```

## 5. 프로바이더별 Tool Calling

### 5.1 Ollama

```json
{
  "model": "glm-5.1",
  "messages": [...],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "click",
        "description": "Click an element",
        "parameters": {"type": "object", "properties": {"selector": {"type": "string"}}}}
    }
  ]
}
```

응답: `message.tool_calls[].function.name/arguments`

### 5.2 OpenAI

```json
{
  "model": "gpt-4o",
  "messages": [...],
  "tools": [{"type": "function", "function": {"name": "click", ...}}]
}
```

응답: `delta.tool_calls[].function.name/arguments` (incremental)

### 5.3 Anthropic

```json
{
  "model": "claude-sonnet-4-20250514",
  "messages": [...],
  "tools": [{"name": "click", "description": "...", "input_schema": {...}}]
}
```

응답: `content_block_start` type=`tool_use` + `content_block_delta` type=`input_json_delta`

### 5.4 Gemini

```json
{
  "contents": [...],
  "tools": [{"function_declarations": [{"name": "click", ...}]}]
}
```

응답: `functionCall` 블록

## 6. 보안 체크리스트

| 항목 | 구현 | 검증 방법 |
|------|------|-----------|
| URL 화이트리스트 (http/https only) | ✅ | `checkNavigateRisk` |
| `type` 런타임 DOM 검사 | ✅ | `checkTypeRisk` |
| `eval_js` AST 패턴 검사 | ✅ | `checkEvalRisk` |
| 민감 액션 confirmation | ✅ | `PermissionGate` |
| Cookie/Storage 접근 차단 | ✅ | AST 패턴에 포함 |
| 이미지 토큰 제한 (1회만) | ✅ | `AgentState` iteration 0 체크 |
| Loop detection | ✅ | `lastAction` + `repeatCount` |
| Error limit | ✅ | `consecutiveErrors >= 3` |
| Cancel 가능 | ✅ | `currentJob.cancel()` |
| Undo/Rollback | ⚠️ Phase 2 | URL 스택 + `goBack()` |

## 7. 구현 우선순위 (Revised)

### Phase 1: Foundation + Safety (Must)
1. `ToolDefinition.kt` — 데이터 클래스
2. `PermissionGate.kt` — Runtime DOM 검사 + AST 분석
3. `ToolExecutor.kt` — WebView Main 스레드 조작
4. `AgentLoop.kt` — State machine + cancel support
5. Ollama 어댑터 — tool_call 파싱

### Phase 2: UX Polish (Need)
6. 명시적 Agent Mode 토글 + `/do` 프리픽스
7. 타임라인 UI + 인라인 confirmation
8. Send → Stop 버튼 변환
9. Undo/Rollback 메커니즘

### Phase 3: Multi-Provider (Want)
10. OpenAI tool_call 파싱
11. Anthropic tool_use 파싱
12. Gemini functionCall 파싱

## 8. 의견 요청 포인트 (Updated)

1. **툴 폴백** — Phase 1에서는 tool calling 지원 모델만 활성화, Phase 3 이후 폴백 검토
2. **Allow Once/Always** — **Allow Once만 제공**. 영구 허용은 보안 패턴 위반
3. **DOM 전략** — iteration 0: screenshot+DOM, 이후: DOM only. 명시적 `screenshot` 호출 시에만 이미지
4. **중단 조건** — max iterations + same action 3회 + consecutive errors 3회
5. **쿠키 격리** — **완전 차단**. LLM은 쿠키 존재 여부조차 알 수 없음

---

## 9. 에이전트 인지 개선 (v3)

> 실제 사용 세션 분석 결과, 도구 한계가 아닌 **에이전트의 판단력 문제**가 주요 실패 원인이었음.
> ChatGPT 공유 링크 추출 세션에서 45초+ 소요, 35회 tool call, 0회 성공 추출.

### 9.1 문제 분석

| 문제 | 사례 | 빈도 |
|------|------|------|
| 실패 원인 분석 불가 | CSP 에러 메시지를 읽고도 동일한 `eval_js` 5회 재시도 | 5/5 |
| 사용자 명시 지시 무시 | "원문 그대로" 요청에 요약 제공 (2회) | 2/3 응답 |
| 반복 실패 탈출 불가 | `get_dom` 800 chars 잘림 → 스크롤 15회 + `get_dom` 15회 반복 | 15회 |
| 도구 능력 과신 | 잘린 DOM으로 전체 텍스트 추출 시도 | 지속적 |

### 9.2 해결 방안

#### A. 시스템 프롬프트 인지 규칙 (MUST)

`SystemPromptBuilder`에 **Cognitive Rules** 섹션 추가:

```
## Cognitive Rules (MUST follow)

### 1. Analyze Failure Causes Before Retrying
When a tool returns an error, READ the error message and identify the root cause.
- CSP error → JS blocked. Do NOT retry eval_js. Switch tools.
- Truncated output → Use extract_text or specific selectors. Do NOT scroll+get_dom repeatedly.
- Selector not found → Try different strategy, not same selector.

### 2. Respect Explicit User Intent
- 'exact/original text' → verbatim, not summary
- 'raw data' → raw, not interpretation
- Cannot fulfill → state honestly. Never substitute summary for original.

### 3. Escape Repetitive Failure Loops
- Same tool category fails 3 times → STOP using that approach
- Switch strategy or inform user of limitation
- Max 3 attempts at same approach

### 4. Know Your Tools' Limits
- get_dom: ~10K chars, truncated. Use extract_text for content.
- eval_js: Blocked by CSP on many sites. Don't retry on CSP error.
- scroll + get_dom: Doesn't increase content. Use specific selectors.
- screenshot: Viewport only, not full page.
```

#### B. `extract_text` 신규 도구 (MUST)

`innerText` 기반 텍스트 전용 추출 도구:

```kotlin
EXTRACT_TEXT = ToolDefinition(
    name = "extract_text",
    description = "Extract visible text content from the page or a specific element. Unlike get_dom which returns HTML markup (which can be very large and truncated), this tool returns only the human-readable text. Use this when you need the actual content of a page — articles, conversations, lists — rather than its HTML structure. Returns up to ~8,000 characters.",
    parameters = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("selector", ...)
            add("includeHeadings", ...)
        })
    }
)
```

장점:
- HTML 태그·CSS 클래스·속성 없이 순수 텍스트만 반환
- `#`, 개행 등 최소 구조 보존
- `get_dom` 10K chars 중 태그가 90%인 반면, `extract_text`는 내용 위주
- CSP 영향 없음 (`evaluateJavascript`으로 `innerText` 접근)

#### C. `get_dom` 응답 메타데이터 (MUST)

```json
{
  "tagName": "SECTION",
  "outerHTML": "...(10000 chars)...",
  "textContent": "...(2000 chars)...",
  "childCount": 5,
  "_meta": {
    "truncated": true,
    "totalLength": 45200,
    "capturedLength": 10000
  }
}
```

`_meta.truncated = true` → LLM이 잘림을 인식하고 `extract_text`로 전환 가능.

#### D. `eval_js` 설명 개선 (WANT)

Before: "WARNING: This is a powerful tool..."
After: "WARNING: Many sites (chatgpt.com, google.com, etc.) block eval via Content Security Policy (CSP). If you receive a CSP error, do NOT retry eval_js — switch to get_dom, extract_text, or click instead."

이 설명은 **CSP 에러를 만나면 재시도하지 말라는** 명시적 지시를 포함한다.

#### E. AgentLoop 반복 실패 추적 개선 (WANT)

현재 `consecutiveErrors >= 3`은 **다른 종류의 에러**도 포함.
CSP 에러는 성공적인 실행이므로 `isError=false`지만, 에이전트 입장에서는 **의미적 실패**다.

개선안: ToolResult에 `semanticError` 필드 추가

```kotlin
data class ToolResult(
    val id: String,
    val output: String,
    val isError: Boolean = false,
    val semanticError: String? = null  // CSP 차단, 잘림 등 의미적 실패
)
```

`eval_js`가 CSP 에러를 반환하면 `semanticError = "CSP blocked"`로 설정.
`get_dom`이 잘린 응답이면 `semanticError = "truncated"`로 설정.

AgentLoop에서 `semanticError`가 연속 3회 누적되면 해당 도구 카테고리 사용 중단.

### 9.3 구현 상태

| 항목 | 상태 | 파일 |
|------|------|------|
| Cognitive Rules 시스템 프롬프트 | ✅ 구현 | `SystemPromptBuilder.kt` |
| `extract_text` 도구 정의 | ✅ 구현 | `ToolDefinition.kt` |
| `extract_text` 도구 실행 | ✅ 구현 | `ToolExecutor.kt` |
| `get_dom` 메타데이터 (`_meta.truncated`) | ✅ 구현 | `ToolExecutor.kt` |
| `eval_js` 설명 개선 (CSP 경고) | ✅ 구현 | `ToolDefinition.kt` |
| `extract_text` PermissionGate 분류 | ✅ 구현 | `PermissionGate.kt` |
| ToolResult.semanticError 필드 | 🔲 예정 | `ToolDefinition.kt`, `AgentLoop.kt` |
| AgentLoop 의미적 실패 누적 추적 | 🔲 예정 | `AgentLoop.kt` |
