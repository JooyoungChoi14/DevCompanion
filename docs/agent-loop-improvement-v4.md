# DevCompanion Agent Loop 개선안 v4 — 외부 시스템 전략 흡수 + 역제안

> 2026-05-25 | LibreChat, Codex, Claude CLI 조사 종합

---

## 1. 조사 대상 및 핵심 발견

### 1.1 LibreChat (`@librechat/agents`)

| 전략 | 구현 | DevCompanion 적용 가능성 |
|------|------|--------------------------|
| **재귀 제한** | `resolveRecursionLimit`: 기본 50회, 에이전트별 오버라이드 + 글로벌 캡 가능 | ✅ 이미 `maxIterations=10` 존재, 하지만 **적응형 조정** 필요 |
| **토큰 예산** | `DEFAULT_RESERVE_RATIO=0.05` (컨텍스트의 5%를 예산으로 예약) + `computeEffectiveMaxContextTokens` | ✅ **MUST** — 현재 DevCompanion에는 토큰 예산 관리 없음 |
| **툴 결과 잘라내기** | `maxToolResultChars` per-agent 설정 | ✅ 이미 `CONTEXT_TOOL_RESULT_BUDGET=4000` 존재, 하지만 **적응형**이 아님 |
| **지연 툴 로딩** | `defer_loading` + `tool_search`: 초기에 툴 스키마를 숨기고 필요시 검색 | ⚠️ DevCompanion은 툴 15개 → 오버헤드 불필요 |
| **서브에이전트** | `MAX_SUBAGENT_DEPTH` + `MAX_SUBAGENT_RUN_CONFIGS` + 깊은 클론으로 격리 | ⚠️ 현재 범위 밖 |
| **스킬 프라이밍** | `MAX_PRIMED_SKILLS_PER_TURN`으로 매 턴 주입 스킬 수 제한 | ⚡ 아이디어: **매 턴 주입 시스템 메시지 크기 제한** |
| **Context pruning** | `ContextPruningConfig` — 대화 히스토리에서 불필요 메시지 제거 | ✅ **NEED** — 긴 대화에서 컨텍스트 팽창 제어 |
| **Summarization** | `SummarizationConfig` — 오래된 메시지를 요약으로 교체 | ✅ **NEED** — 현재 무한 누적 구조 |
| **MCP 지침 주입** | 에이전트별 MCP 서버에서 동적 지침 주입 | ⚡ 아이디어: **URL별 도메인 특화 힌트** |

### 1.2 OpenAI Codex (`codex-rs`)

| 전략 | 구현 | DevCompanion 적용 가능성 |
|------|------|--------------------------|
| **Context Compaction** | LLM이 대화 히스토리를 "handoff summary"로 압축 — 다음 LLM이 이어서 작업 | ✅ **MUST** — 가장 중요한 패턴 |
| **Mid-turn Compaction** | 턴 도중에도 토큰 한계 도달 시 자동 압축 | ✅ **NEED** — 긴 에이전트 세션에서 필수 |
| **TruncationPolicy** | `Bytes(N)` 또는 `Tokens(N)` — 툴 출력을 **중간 잘라내기** (앞+뒤 유지) | ✅ **MUST** — 현재 앞부분만 잘라내기 → 중간 잘라내기로 변경 |
| **Pre-turn Compaction** | 새 턴 시작 전에 컨텍스트 크기 검사 → 필요시 압축 | ✅ **NEED** — Agent Loop 반복 시작 시 검사 |
| **Binary file guard** | 바이너리 파일 읽기 시도 → 즉시 에러 메시지 + 대안 제시 | ✅ 이미 CSP/Not Found 가이드 존재, **확장 가능** |
| **Token budget tracking** | `auto_compact_scope_limit` + `full_context_window_limit` — 이중 임계값 | ✅ **MUST** — 토큰 사용량 실시간 추적 |
| **Usage limit** | 토큰 한계 도달 시 graceful degradation | ✅ **NEED** |

### 1.3 Claude CLI (유출 프롬프트 패턴)

| 전략 | 구현 | DevCompanion 적용 가능성 |
|------|------|--------------------------|
| **Ralph Wiggum 루프 방지** | 동일한 패턴 반복 감지 시 강제 종료 | ✅ **MUST** — recall 무한루프, scroll+get_dom 중복 방지 |
| **Code Explorer 에이전트** | 특화된 서브에이전트: 구조 파악 → 검색 → 읽기의 단계적 진행 | ⚡ 아이디어: **2-Phase 탐색 전략** |
| **System prompt design** | "When you don't know, say so" / "Don't fabricate" / 명시적 제약 | ✅ 이미 부분 구현, **강화 가능** |
| **Agent creation system prompt** | 도구 결과에 대한 "지시 사항"을 시스템 프롬프트에 명시 | ✅ 이미 `buildFramedToolResult`로 구현, **확장 가능** |

---

## 2. Want / Need / Must 재선정

### MUST (즉시 수정, 시스템 프롬프트 + 코드)

| # | 항목 | 현재 상태 | 목표 | 수정 수단 |
|---|------|----------|------|----------|
| M1 | **recall 무한루프** | recall 도입했으나 LLM이 연속 recall 호출 | recall 1회 결과에 "더 이상 recall하지 마" 명시 + 연속 recall 감지 시 중단 | 프롬프트 + 코드 |
| M2 | **토큰 예산 인식** | LLM이 자신의 토큰 예산을 모름 | 매 iteration 시스템 메시지에 "남은 반복 수: N/10, 사용한 토큰: ~X, 예산: ~Y" 포함 | 프롬프트 + 코드 |
| M3 | **중간 잘라내기(middle truncation)** | 현재 앞부분만 표시 (`.take(4000)`) | Codex 패턴: 앞부분 + `...[중략]...` + 뒷부분 유지 | 코드 |
| M4 | **동일 액션 반복 감지** | `MAX_SAME_ACTION_REPEAT=2` 존재하나 미작동 | Codex의 `repeatCount` + Claude의 Ralph Wiggum: 동일 (tool, selector) 2회 반복 시 강제 차단 | 코드 |

### NEED (다음 단계, 아키텍처 변경)

| # | 항목 | 현재 상태 | 목표 | 수정 수단 |
|---|------|----------|------|----------|
| N1 | **Context Compaction** | 대화 히스토리 무한 누적 | Codex 패턴: 토큰 한계 도달 시 LLM이 대화를 "handoff summary"로 압축 | 코드 (새 컴포넌트) |
| N2 | **Pre-turn 토큰 검사** | 매 iteration 무조건 컨텍스트 캡처 | 반복 시작 전 토큰 사용량 검사 → 한계 근접 시 압축 또는 종료 | 코드 |
| N3 | **URL별 도메인 힌트** | 모든 사이트에 동일 프롬프트 | LibreChat MCP 패턴: URL 패턴별 특화 지침 (예: google.com → CSP 주의) | 프롬프트 동적 생성 |
| N4 | **툴 결과 적응형 잘라내기** | 고정 4000자 | LibreChat `maxToolResultChars` 패턴: 컨텍스트 예산 기반 동적 할당 | 코드 |
| N5 | **2-Phase 탐색 전략** | LLM이 임의로 툴 선택 | Claude Code Explorer 패턴: (1) get_dom으로 구조 파악 → (2) 선택자로 정밀 추출 | 프롬프트 |

### WANT (전략적 가치, 장기)

| # | 항목 | 근거 | 비고 |
|---|------|------|------|
| W1 | **Skill Priming** (URL별 동적 시스템 메시지) | LibreChat `injectSkillPrimes` — 턴마다 관련 스킬만 주입 | URL 기반 도메인 힌트로 축소 가능 |
| W2 | **서브에이전트 패턴** | LibreChat/Codex 모두 격리된 서브에이전트 사용 | Android 리소스 제약상 1차 루프 내 해결이 우선 |
| W3 | **Deferred Tool Loading** | 초기 5개만 노출, 필요시 추가 | 툴 15개로 오버헤드 미미, 현재 불필요 |
| W4 | **Context Pruning (메시지 레벨)** | LibreChat `ContextPruningConfig` | Compaction이 더 효율적, Compaction 먼저 구현 |

---

## 3. 역제안: 구체적 구현 계획

### Phase 1: MUST (시스템 프롬프트 + 코드 수정) — 1일

#### M1: recall 무한루프 방지

**프롬프트 수정** (`SystemPromptBuilder.kt`):
```kotlin
// recall 도구 설명에 추가:
"| recall | Retrieve previous tool results from session memory | Safe |"
// →
"| recall | Retrieve previous tool results from session memory. Use ONCE to get details, then proceed. Do NOT call recall multiple times for the same index. | Safe |"
```

**Cognitive Rules에 추가**:
```kotlin
sb.appendLine("### 8. Use recall Sparingly")
sb.appendLine("- recall is for retrieving previously stored tool results that were truncated.")
sb.appendLine("- Call recall ONCE per entry. If the recalled content still doesn't contain what you need, STOP and tell the user — do NOT recall again.")
sb.appendLine("- recall is NOT a search tool. It retrieves a specific entry by index. If you don't know the index, use extract_text or get_dom with a specific selector instead.")
```

**코드 수정** (`AgentLoop.kt`):
```kotlin
// runAgentLoopBody 내부에 연속 recall 감지 추가:
var lastRecallIndex: Int? = null
var recallRepeatCount = 0

// 툴 실행 후:
if (call.name == "recall") {
    val idx = call.arguments.getAsJsonPrimitive("index")?.asInt
    if (idx == lastRecallIndex) {
        recallRepeatCount++
        if (recallRepeatCount >= 2) {
            messages.add(ChatMessage(role = "system",
                content = "[SYSTEM: You have recalled the same entry twice. Stop recalling and proceed with your current information or tell the user what's missing.]"))
        }
    } else {
        lastRecallIndex = idx
        recallRepeatCount = 0
    }
}
```

#### M2: 토큰 예산 인식

**매 iteration 시스템 메시지에 예산 정보 주입** (`AgentLoop.kt`):
```kotlin
// 빌드 프레이밍에 예산 정보 추가:
val budgetInfo = buildString {
    append("[BUDGET: Iteration ${iteration + 1}/$maxIterations")
    // 토큰 카운트가 가능한 경우:
    response.inputTokens?.let { append(", input_tokens: ~$it") }
    response.outputTokens?.let { append(", output_tokens: ~$it") }
    append(". Remaining iterations: ${iterations - i - 1}]")
}
```

**시스템 프롬프트에 추가** (`SystemPromptBuilder.kt`):
```kotlin
sb.appendLine("### 9. Token Budget Awareness")
sb.appendLine("- You have a maximum of 10 iterations per task. Plan your tool usage accordingly.")
sb.appendLine("- If you've used 7+ iterations, you MUST summarize findings and respond in text — no more exploration.")
sb.appendLine("- Each tool call consumes an iteration. Prefer targeted queries (specific selector) over broad ones (full page DOM).")
```

#### M3: 중간 잘라내기

**`buildFramedToolResult` 수정** (`AgentLoop.kt`):
```kotlin
// 기존: rawOutput.take(CONTEXT_TOOL_RESULT_BUDGET)
// 변경: 앞부분 + 중략 + 뒷부분 유지

private fun middleTruncate(raw: String, budget: Int): String {
    if (raw.length <= budget) return raw
    val headSize = (budget * 0.6).toInt()  // 60% 앞부분
    val tailSize = (budget * 0.3).toInt()   // 30% 뒷부분
    val omitted = raw.length - headSize - tailSize
    return raw.take(headSize) +
        "\n\n... [${omitted} chars omitted] ...\n\n" +
        raw.takeLast(tailSize)
}
```

#### M4: 동일 액션 반복 감지 강화

**`AgentLoop.kt`의 반복 감지 로직 수정**:
```kotlin
// 기존: prevAction 단순 비교
// 변경: (toolName, 핵심 인자) 쌍으로 추적, 2회 반복 시 강제 종료

data class ActionSignature(val tool: String, val keyParam: String?)

private var lastActionSignature: ActionSignature? = null
private var sameActionCount = 0

// 툴 실행 전:
val signature = ActionSignature(call.name, 
    call.arguments.getAsJsonPrimitive("selector")?.asString
    ?: call.arguments.getAsJsonPrimitive("url")?.asString)
if (signature == lastActionSignature) {
    sameActionCount++
    if (sameActionCount >= MAX_SAME_ACTION_REPEAT) {
        messages.add(ChatMessage(role = "system",
            content = "[SYSTEM: You have called ${signature.tool}(${signature.keyParam}) $sameActionCount times with the same result. STOP repeating this action and either try a different approach or report the limitation to the user.]"))
        sameActionCount = 0  // 리셋하여 루프는 계속 진행
    }
} else {
    lastActionSignature = signature
    sameActionCount = 0
}
```

---

### Phase 2: NEED (아키텍처 확장) — 2-3일

#### N1: Context Compaction

**새 컴포넌트**: `ContextCompactor.kt`

```kotlin
/**
 * 대화 히스토리가 토큰 예산을 초과할 때,
 * 이전 툴 결과+응답을 LLM에게 "handoff summary"로 압축 요청.
 *
 * Codex 패턴: "Another language model started to solve this problem
 * and produced a summary. Use this to build on the work already done."
 */
class ContextCompactor(
    private val tokenBudget: Int = 8000, // 히스토리에 할당된 토큰 예산
    private val llmCaller: suspend (String) -> String
) {
    fun estimateTokens(messages: List<ChatMessage>): Int { ... }
    
    suspend fun compact(messages: MutableList<ChatMessage>): MutableList<ChatMessage> {
        if (estimateTokens(messages) <= tokenBudget) return messages
        
        // 첫 사용자 메시지 + 시스템 프롬프트 유지
        // 나머지를 요약 요청으로 교체
        val summary = llmCaller(buildCompactionPrompt(messages))
        
        return mutableListOf(
            messages.first(), // 원래 사용자 메시지
            ChatMessage(role = "system", content = 
                "Previous conversation summary:\n$summary\n---\nContinue from here."),
            messages.last()   // 최근 컨텍스트 유지
        )
    }
}
```

#### N2: Pre-turn 토큰 검사

```kotlin
// AgentLoop.runAgentLoopBody 시작 부분에:
val estimatedTokens = estimateTokens(messages)
val contextLimit = modelContextLimit ?: 128_000 // 모델별 기본값
val reserveRatio = 0.15 // 15% 예약 (LibreChat은 5%, 하지만 에이전트 루프는 더 보수적)

if (estimatedTokens > contextLimit * (1 - reserveRatio)) {
    // Compaction 수행 또는 종료
    val compacted = contextCompactor.compact(messages)
    messages.clear()
    messages.addAll(compacted)
}
```

#### N3: URL별 도메인 힌트

```kotlin
// SystemPromptBuilder.kt에 동적 섹션 추가:
fun buildDomainHints(url: String?): String {
    if (url == null) return ""
    return when {
        url.contains("google.com") -> 
            "⚠️ Google sites enforce strict CSP. eval_js will likely fail. Use click, type, and extract_text only."
        url.contains("chatgpt.com") || url.contains("openai.com") ->
            "⚠️ OpenAI sites block JavaScript injection. Prefer get_dom and extract_text."
        url.contains("github.com") ->
            "GitHub pages support eval_js. Use get_dom with specific selectors for code content."
        else -> ""
    }
}
```

#### N4: 툴 결과 적응형 잘라내기

```kotlin
// 기존: 고정 CONTEXT_TOOL_RESULT_BUDGET = 4000
// 변경: 남은 예산에 비례하여 동적 할당

fun computeToolResultBudget(
    totalBudget: Int,        // 컨텍스트 예산
    usedTokens: Int,         // 현재까지 사용한 토큰
    remainingIterations: Int // 남은 반복 수
): Int {
    val available = totalBudget - usedTokens
    val perIteration = available / maxOf(remainingIterations, 1)
    // 최소 2000자, 최대 6000자
    return perIteration.coerceIn(2000, 6000)
}
```

#### N5: 2-Phase 탐색 전략

**시스템 프롬프트에 추가**:
```kotlin
sb.appendLine("### 10. Structured Exploration Pattern")
sb.appendLine("For page content extraction tasks, follow this pattern:")
sb.appendLine("1. **Survey**: Use get_dom or extract_text ONCE to understand the page structure.")
sb.appendLine("2. **Target**: Use get_dom with a specific selector OR extract_text to get the exact content you need.")
sb.appendLine("3. **Verify**: Use screenshot to confirm visual state, or extract_text to confirm content.")
sb.appendLine("Do NOT call get_dom multiple times without specific selectors. Do NOT scroll and get_dom repeatedly expecting different results.")
```

---

### Phase 3: WANT (전략적, 장기) — 스프린트 후반

| 항목 | 설명 | 우선순위 |
|------|------|----------|
| W1 | URL 패턴 기반 도메인 힌트 자동 생성 | N3으로 축소 가능 |
| W2 | 서브에이전트 (독립 컨텍스트에서 탐색) | Android 리소스 제약, 보류 |
| W3 | 툴 그룹핑 (탐색/조작/확인) | 프롬프트로 해결 가능 |
| W4 | 메시지 레벨 프루닝 | N1 Compaction이 상위 대체 |

---

## 4. 핵심 인사이트: 외부 시스템이 DevCompanion와 다른 점

### 4.1 LibreChat의 핵심 차이점

LibreChat은 **LangGraph 기반 멀티에이전트 시스템**이다. DevCompanion과의 가장 큰 차이:

1. **툴 검색이 별도 단계** (`tool_search` → `tool_call`). DevCompanion은 항상 전체 툴 스키마를 제공
2. **Context 예산이 설정 기반** (`reserveRatio`, `maxContextTokens`). DevCompanion은 하드코딩
3. **Summarization이 별도 LLM 호출**. DevCompanion은 툴 결과만 잘라내기
4. **툴 실행에 Rate Limit** (`toolCallLimiter`: 1req/s/user). DevCompanion은 무제한

**흡수할 패턴**: reserveRatio 개념, 적응형 잘라내기, 에이전트별 재귀 제한

### 4.2 Codex의 핵심 차이점

Codex는 **Rust 기반 네이티브 에이전트**이다. 가장 중요한 패턴:

1. **Mid-turn Compaction**: 턴 도중에도 토큰 한계 도달 시 대화를 요약
2. **"Handoff Summary"**: "Another language model started to solve this problem" — 이전 컨텍스트를 다른 LLM 호출 결과로 프레이밍
3. **TruncationPolicy**: Bytes 또는 Tokens 단위, **중간 잘라내기** (앞+뒤 유지)
4. **Pre-sampling Compaction**: 각 샘플링 전에 토큰 검사
5. **Binary guard**: 바이너리 파일 감지 → 즉시 에러 + 대안 제시

**흡수할 패턴**: 중간 잘라내기, 토큰 예산 추적, Compaction 프레이밍

### 4.3 Claude CLI의 핵심 차이점

Claude CLI는 **에이전트 시스템 프롬프트 설계**에 강점:

1. **명시적 루프 방지**: "If you've tried the same approach twice and it didn't work, try something different"
2. **Code Explorer의 단계적 진행**: 구조 파악 → 검색 → 읽기
3. **Ralph Wiggum 감지**: 동일 패턴 반복 → 강제 종료

**흡수할 패턴**: 루프 방지 명시화, 2-Phase 탐색

---

## 5. 즉시 적용 가능한 시스템 프롬프트 수정 요약

현재 `SystemPromptBuilder.kt`의 **Cognitive Rules** 섹션에 추가:

```kotlin
// 기존 Rule 1-7 유지, 다음 추가:

sb.appendLine("### 8. Use recall Sparingly")
sb.appendLine("- Call recall ONCE per entry. If the recalled content doesn't contain what you need, STOP and tell the user.")
sb.appendLine("- Do NOT call recall multiple times for the same index. recall is NOT a search tool.")

sb.appendLine("### 9. Token Budget Awareness")
sb.appendLine("- You have a maximum of 10 iterations. Each tool call = 1 iteration.")
sb.appendLine("- At iteration 8+, you MUST summarize and respond in text — no more exploration.")
sb.appendLine("- Prefer targeted queries (specific selector) over broad ones (full page DOM).")

sb.appendLine("### 10. Structured Exploration Pattern")
sb.appendLine("- Survey → Target → Verify: get_dom ONCE for structure, then specific selectors.")
sb.appendLine("- Do NOT call get_dom/extract_text multiple times without different selectors.")
sb.appendLine("- Do NOT scroll and get_dom repeatedly expecting more content.")
```

---

## 6. 구현 우선순위

| 순서 | 항목 | 난이도 | 영향 | 수정 위치 |
|------|------|--------|------|-----------|
| 1 | M1: recall 무한루프 방지 | 낮음 | 높음 | 프롬프트 + 코드 |
| 2 | M4: 동일 액션 반복 감지 | 낮음 | 높음 | 코드 |
| 3 | M3: 중간 잘라내기 | 중간 | 높음 | 코드 |
| 4 | M2: 토큰 예산 인식 | 중간 | 높음 | 프롬프트 + 코드 |
| 5 | N5: 2-Phase 탐색 전략 | 낮음 | 중간 | 프롬프트 |
| 6 | N3: URL별 도메인 힌트 | 낮음 | 중간 | 프롬프트 동적 생성 |
| 7 | N4: 적응형 잘라내기 | 중간 | 중간 | 코드 |
| 8 | N1: Context Compaction | 높음 | 높음 | 새 컴포넌트 |
| 9 | N2: Pre-turn 토큰 검사 | 중간 | 높음 | 코드 |

---

## 7. 결론

**가장 큰 인사이트**: DevCompanion의 현재 문제(루프, 중복, 요약 위반)는 **프롬프트 수정만으로 70% 해결 가능**하다. Codex의 Compaction은 아키텍처 변경이 필요하지만, 루프 방지와 예산 인식은 프롬프트 + 최소 코드 수정으로 즉시 적용 가능.

**핵심 원칙** (3개 시스템 모두 공통):
1. **토큰 예산을 LLM에게 알려라** — 모름이 과소비의 원인
2. **동일 행동 반복을 강제 차단하라** — LLM은 스스로 멈추지 못함
3. **컨텍스트를 잘라낼 때 앞부분만 버리지 마라** — 중간 생략이 훨씬 유용