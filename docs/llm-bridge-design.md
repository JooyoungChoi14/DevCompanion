# DevCompanion In-App LLM Bridge 설계 문서

## 1. 개요 (Overview)

**목표**: 외부 bore/MCP 의존 없이 Android 앱 내부에서 WebView 컨텍스트를 LLM에 전달하고 응답을 받아 표시

**핵심 원칙**:
- Provider-agnostic: Anthropic, OpenAI, Ollama(Cloud/Local) 모두 지원
- Streaming 응답: 실시간 토큰 수신
- Context 패킷: 스크린샷 + DOM + computed styles 일괄 전송
- 보안: API 키는 DataStore 암호화 저장

---

## 2. API Schema Reference

### 2.1 Anthropic Messages API

**Endpoint**: `POST https://api.anthropic.com/v1/messages`

**Headers**:
```
x-api-key: {ANTHROPIC_API_KEY}
anthropic-version: 2023-06-01
Content-Type: application/json
```

**Request Body**:
```json
{
  "model": "claude-3-5-sonnet-20241022",
  "max_tokens": 4096,
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "image",
          "source": {
            "type": "base64",
            "media_type": "image/png",
            "data": "iVBORw0KGgoAAAANS..."
          }
        },
        {
          "type": "text",
          "text": "이 웹페이지에서 'Submit' 버튼이 안 보입니다. CSS와 레이아웃을 분석해주세요."
        }
      ]
    }
  ]
}
```

**Streaming Response** (SSE):
```
event: message_start
data: {"type": "message_start", "message": {"id": "...", "role": "assistant"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "분석"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": " 결과"}}

event: message_stop
data: {"type": "message_stop"}
```

---

### 2.2 OpenAI Chat Completions API

**Endpoint**: `POST https://api.openai.com/v1/chat/completions`

**Headers**:
```
Authorization: Bearer {OPENAI_API_KEY}
Content-Type: application/json
```

**Request Body**:
```json
{
  "model": "gpt-4o",
  "max_tokens": 4096,
  "stream": true,
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/png;base64,iVBORw0KGgoAAAANS..."
          }
        },
        {
          "type": "text",
          "text": "이 웹페이지에서 'Submit' 버튼이 안 보입니다. CSS와 레이아웃을 분석해주세요."
        }
      ]
    }
  ]
}
```

**Streaming Response** (SSE):
```
data: {"id":"...","object":"chat.completion.chunk","choices":[{"delta":{"content":"분석"}}]}

data: {"id":"...","object":"chat.completion.chunk","choices":[{"delta":{"content":" 결과"}}]}

data: [DONE]
```

---

### 2.3 Ollama API

**Endpoint**: `POST https://ollama.com/api/chat` (Cloud) or `POST http://localhost:11434/api/chat` (Local)

**Request Body**:
```json
{
  "model": "llava",
  "stream": true,
  "messages": [
    {
      "role": "user",
      "content": "이 웹페이지에서 'Submit' 버튼이 안 보입니다. CSS와 레이아웃을 분석해주세요.",
      "images": ["iVBORw0KGgoAAAANS..."]
    }
  ]
}
```

**Note**: Ollama는 OpenAI format adapter로 변환 가능 (LibreChat 방식 차용)

---

## 3. Kotlin 인터페이스 설계

### 3.1 Domain Models

```kotlin
// LLM Provider 타입
sealed class LlmProvider {
    abstract val name: String
    abstract val apiKey: String
    abstract val baseUrl: String
    
    data class Anthropic(
        override val apiKey: String,
        override val baseUrl: String = "https://api.anthropic.com",
        val version: String = "2023-06-01"
    ) : LlmProvider() {
        override val name = "anthropic"
    }
    
    data class OpenAi(
        override val apiKey: String,
        override val baseUrl: String = "https://api.openai.com",
        val organization: String? = null
    ) : LlmProvider() {
        override val name = "openai"
    }
    
    data class Ollama(
        override val apiKey: String = "",
        override val baseUrl: String = "https://ollama.com",
        val model: String = "llava"
    ) : LlmProvider() {
        override val name = "ollama"
    }
}

// 메시지 콘텐츠 블록 (Provider-agnostic)
sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class Image(
        val base64Data: String,
        val mimeType: String = "image/png"
    ) : ContentBlock()
}

// 컨텍스트 패킷 (WebView → LLM)
data class WebContextPacket(
    val url: String,
    val title: String,
    val screenshotBase64: String,
    val domSnapshot: DomSnapshot,
    val styles: ComputedStyles,
    val timestamp: Long = System.currentTimeMillis()
)

data class DomSnapshot(
    val selector: String,
    val outerHTML: String,
    val textContent: String?,
    val tagName: String,
    val id: String?,
    val className: String?,
    val childCount: Int
)

data class ComputedStyles(
    val viewportWidth: Int,
    val viewportHeight: Int,
    val elements: List<ElementStyle>
)

data class ElementStyle(
    val selector: String,
    val display: String?,
    val visibility: String?,
    val opacity: String?,
    val zIndex: String?,
    val position: String?,
    val overflow: String?,
    val width: String?,
    val height: String?,
    val color: String?,
    val backgroundColor: String?,
    val fontSize: String?,
    val fontFamily: String?,
    val borderRadius: String?
)

// LLM 응답 스트림 이벤트
sealed class LlmStreamEvent {
    data class Start(val messageId: String) : LlmStreamEvent()
    data class Token(val content: String) : LlmStreamEvent()
    data class Error(val message: String) : LlmStreamEvent()
    data class Complete(val usage: TokenUsage?) : LlmStreamEvent()
}

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int
)
```

---

### 3.2 Repository Interface

```kotlin
interface LlmRepository {
    /**
     * Provider 설정 업데이트 (API 키, 엔드포인트 등)
     */
    suspend fun setProvider(provider: LlmProvider)
    
    /**
     * WebView 컨텍스트를 포함하여 LLM에 쿼리
     * Streaming 응답을 Flow로 반환
     */
    suspend fun queryWithContext(
        userMessage: String,
        context: WebContextPacket,
        model: String? = null,
        maxTokens: Int = 4096
    ): Flow<LlmStreamEvent>
    
    /**
     * 일반 텍스트 쿼리 (컨텍스트 없이)
     */
    suspend fun query(
        messages: List<Pair<String, String>>, // (role, content)
        model: String? = null,
        maxTokens: Int = 4096
    ): Flow<LlmStreamEvent>
    
    /**
     * 사용 가능한 모델 목록
     */
    suspend fun listModels(): List<String>
}
```

---

### 3.3 Provider Adapter 구현체 (의사코드)

```kotlin
// Anthropic Adapter
class AnthropicLlmAdapter(
    private val httpClient: OkHttpClient,
    private val json: Json
) : LlmAdapter {
    
    override fun buildRequest(
        provider: LlmProvider.Anthropic,
        messages: List<Pair<String, List<ContentBlock>>>,
        model: String,
        maxTokens: Int
    ): Request {
        val requestBody = AnthropicRequest(
            model = model,
            max_tokens = maxTokens,
            messages = messages.map { (role, content) ->
                AnthropicMessage(
                    role = role,
                    content = content.map { block ->
                        when (block) {
                            is ContentBlock.Text -> AnthropicContent.Text(block.text)
                            is ContentBlock.Image -> AnthropicContent.Image(
                                source = AnthropicImageSource(
                                    type = "base64",
                                    media_type = block.mimeType,
                                    data = block.base64Data
                                )
                            )
                        }
                    }
                )
            }
        )
        
        return Request.Builder()
            .url("${provider.baseUrl}/v1/messages")
            .header("x-api-key", provider.apiKey)
            .header("anthropic-version", provider.version)
            .post(json.encodeToString(requestBody).toRequestBody(JSON))
            .build()
    }
    
    override fun parseStream(line: String): LlmStreamEvent? {
        // SSE 파싱: "data: {...}"
        return when {
            line.contains("content_block_delta") -> {
                val delta = json.decodeFromString<AnthropicDelta>(line)
                LlmStreamEvent.Token(delta.delta.text)
            }
            line.contains("message_stop") -> LlmStreamEvent.Complete(null)
            line.contains("error") -> LlmStreamEvent.Error(line)
            else -> null
        }
    }
}

// OpenAI Adapter
class OpenAiLlmAdapter(
    private val httpClient: OkHttpClient,
    private val json: Json
) : LlmAdapter {
    
    override fun buildRequest(
        provider: LlmProvider.OpenAi,
        messages: List<Pair<String, List<ContentBlock>>>,
        model: String,
        maxTokens: Int
    ): Request {
        val requestBody = OpenAiRequest(
            model = model,
            max_tokens = maxTokens,
            stream = true,
            messages = messages.map { (role, content) ->
                OpenAiMessage(
                    role = role,
                    content = content.map { block ->
                        when (block) {
                            is ContentBlock.Text -> OpenAiContent.Text(block.text)
                            is ContentBlock.Image -> OpenAiContent.ImageUrl(
                                image_url = OpenAiImageUrl(
                                    url = "data:${block.mimeType};base64,${block.base64Data}"
                                )
                            )
                        }
                    }
                )
            }
        )
        
        return Request.Builder()
            .url("${provider.baseUrl}/v1/chat/completions")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .post(json.encodeToString(requestBody).toRequestBody(JSON))
            .build()
    }
    
    override fun parseStream(line: String): LlmStreamEvent? {
        // SSE 파싱: "data: {...}"
        return when {
            line == "[DONE]" -> LlmStreamEvent.Complete(null)
            line.contains("content") -> {
                val chunk = json.decodeFromString<OpenAiChunk>(line)
                LlmStreamEvent.Token(chunk.choices.firstOrNull()?.delta?.content ?: "")
            }
            line.contains("error") -> LlmStreamEvent.Error(line)
            else -> null
        }
    }
}
```

---

## 4. Context Builder 구현

```kotlin
class WebContextBuilder(
    private val webView: WebView
) {
    /**
     * WebView에서 전체 컨텍스트 패킷 추출
     */
    suspend fun buildContext(
        selector: String = "body",
        includeScreenshot: Boolean = true,
        includeStyles: Boolean = true
    ): WebContextPacket = suspendCancellableCoroutine { continuation ->
        
        webView.post {
            try {
                // 1. Screenshot 캡처
                val screenshot = if (includeScreenshot) captureScreenshot() else ""
                
                // 2. DOM 스냅샷 (evaluateJavascript)
                val domJs = buildDomJs(selector)
                webView.evaluateJavascript(domJs) { domResult ->
                    
                    // 3. Computed styles 추출
                    val stylesJs = buildStylesJs()
                    webView.evaluateJavascript(stylesJs) { stylesResult ->
                        
                        val packet = WebContextPacket(
                            url = webView.url ?: "",
                            title = webView.title ?: "",
                            screenshotBase64 = screenshot,
                            domSnapshot = parseDomResult(domResult),
                            styles = parseStylesResult(stylesResult)
                        )
                        continuation.resume(packet)
                    }
                }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }
    
    private fun captureScreenshot(): String {
        val bitmap = Bitmap.createBitmap(
            webView.width.coerceAtLeast(1),
            webView.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        webView.draw(Canvas(bitmap))
        
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        
        bitmap.recycle()
        return base64
    }
    
    private fun buildStylesJs(): String = """
        (function(){
            const interactive = ['button','input','select','textarea','a','[onclick]','[role="button"]'];
            const elements = [];
            interactive.forEach(sel => {
                document.querySelectorAll(sel).forEach(el => {
                    const cs = getComputedStyle(el);
                    elements.push({
                        selector: el.tagName + (el.id ? '#'+el.id : '') + (el.className ? '.'+el.className.split(' ').join('.') : ''),
                        display: cs.display,
                        visibility: cs.visibility,
                        opacity: cs.opacity,
                        zIndex: cs.zIndex,
                        position: cs.position,
                        overflow: cs.overflow,
                        width: cs.width,
                        height: cs.height,
                        color: cs.color,
                        backgroundColor: cs.backgroundColor,
                        fontSize: cs.fontSize,
                        fontFamily: cs.fontFamily,
                        borderRadius: cs.borderRadius
                    });
                });
            });
            return JSON.stringify({
                viewportWidth: window.innerWidth,
                viewportHeight: window.innerHeight,
                elements: elements.slice(0, 50) // 상위 50개만
            });
        })()
    """.trimIndent()
}
```

---

## 5. UI 구성

```kotlin
@Composable
fun AiChatPanel(
    viewModel: AiChatViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    
    Column(modifier = modifier.fillMaxSize()) {
        // 상단: 컨텍스트 요약
        ContextSummaryBar(
            url = viewModel.currentUrl,
            hasScreenshot = viewModel.hasScreenshot,
            onCaptureContext = { viewModel.captureContext() }
        )
        
        // 중앙: 메시지 리스트
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = true
        ) {
            items(messages.reversed()) { message ->
                ChatMessageItem(message)
            }
            if (isStreaming) {
                item { StreamingIndicator() }
            }
        }
        
        // 하단: 입력창
        ChatInputBar(
            onSend = { text -> viewModel.sendMessage(text) },
            enabled = !isStreaming
        )
    }
}
```

---

## 6. 설정 화면 확장

```kotlin
@Composable
fun LlmSettingsSection(
    settings: LlmSettings,
    onSettingsChange: (LlmSettings) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    SettingsSectionHeader("AI Provider", expanded) { expanded = !expanded }
    
    AnimatedVisibility(visible = expanded) {
        Column {
            // Provider 선택
            SingleChoiceSegmentedButtonRow {
                LlmProviderType.values().forEach { type ->
                    SegmentedButton(
                        selected = settings.providerType == type,
                        onClick = { onSettingsChange(settings.copy(providerType = type)) }
                    ) {
                        Text(type.name)
                    }
                }
            }
            
            // API Key 입력
            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = { onSettingsChange(settings.copy(apiKey = it)) },
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation()
            )
            
            // Base URL (Ollama용)
            if (settings.providerType == LlmProviderType.OLLAMA) {
                OutlinedTextField(
                    value = settings.baseUrl,
                    onValueChange = { onSettingsChange(settings.copy(baseUrl = it)) },
                    label = { Text("Base URL") }
                )
            }
            
            // 모델 선택
            OutlinedTextField(
                value = settings.model,
                onValueChange = { onSettingsChange(settings.copy(model = it)) },
                label = { Text("Model") },
                placeholder = { Text(when(settings.providerType) {
                    LlmProviderType.ANTHROPIC -> "claude-3-5-sonnet-20241022"
                    LlmProviderType.OPENAI -> "gpt-4o"
                    LlmProviderType.OLLAMA -> "llava"
                })}
            )
        }
    }
}
```

---

## 7. 보안 고려사항

1. **API Key 저장**: DataStore + Android Keystore 암호화
2. **네트워크**: HTTPS only, certificate pinning 권장
3. **입력 검증**: 사용자 입력 sanitization
4. **컨텍스트 민감정보**: screenshot에 개인정보 포함 가능성 → 흐림 처리 옵션

---

## 8. 구현 우선순위

### Phase 1: Core Infrastructure
- [ ] `LlmRepository` 인터페이스 정의
- [ ] `AnthropicLlmAdapter` 구현 (가장 간단한 schema)
- [ ] `WebContextBuilder` 구현 (screenshot + DOM)
- [ ] `AiChatViewModel` 구현

### Phase 2: UI Integration
- [ ] DevTools 바텀시트에 "AI" 탭 추가
- [ ] SettingsSheet에 LLM 설정 섹션 추가
- [ ] Chat UI 구현

### Phase 3: Provider Expansion
- [ ] `OpenAiLlmAdapter` 구현
- [ ] `OllamaLlmAdapter` 구현
- [ ] Provider 자동 감지/테스트 기능

### Phase 4: Advanced Features
- [ ] 컨텍스트 히스토리 관리
- [ ] CSS 인젝트/프리뷰
- [ ] 토큰 사용량 추적

---

## 9. 참고자료

- LibreChat BaseClient: https://github.com/danny-avila/LibreChat/blob/main/api/app/clients/BaseClient.js
- LibreChat OllamaClient: https://github.com/danny-avila/LibreChat/blob/main/api/app/clients/OllamaClient.js
- Anthropic Vision API: https://docs.anthropic.com/en/docs/build-with-claude/vision
- OpenAI Vision API: https://platform.openai.com/docs/guides/vision
- Android DataStore: https://developer.android.com/topic/libraries/architecture/datastore