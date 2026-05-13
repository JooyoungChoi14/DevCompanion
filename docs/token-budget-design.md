# S5: Token Budget Management — 설계 문서

> 버전: 1.1 | 작성일: 2026-05-13 | 상태: 검증 완료 (2차)
> 
> ⚠️ **v1.1 수정사항** (근거 재검증 결과):
> - 기준 모델: Claude 3.5 Sonnet → **Claude 4 Sonnet** (`claude-sonnet-4-20250514`)으로 변경 (코드 기본값과 정렬)
> - NT 가중치: 단일값 → **입력/출력 분리** (가격이 다르므로 단일 가중치는 부정확)
> - Gemini 2.5 Flash 단가 추가 (코드 기본 모델이 Pro가 아닌 Flash)
> - 이미지 토큰 추정: 고정값 → **provider별 차등 계산**

## 1. 목표

유저가 **예측 가능한 비용**으로 DevCompanion을 사용할 수 있게 한다. 모델 전환, 세션 종료, 예산 초과 시에도 일관된 경험 제공.

---

## 2. 3계층 예산 아키텍처

```
유저 설정 (달러)  →  내부 계산 (NT)  →  슬롯 할당 (비율)
     ↓                  ↓                  ↓
   "$0.50/세션"    가중치 변환        시스템/컨텍스트/히스토리/응답
```

### Layer 1: 달러 예산 (유저 설정)

```kotlin
data class DollarBudget(
    val sessionLimit: Double = 0.50,  // 기본값: $0.50
    val monthlyLimit: Double? = null, // 선택적
    val currency: String = "USD"
)
```

**기본값 OFF**: 예산 추적을 사용하지 않으면 제한 없음. ON으로 전환 시에만 제한 적용.

### Layer 2: 정규화 토큰 (NT, Normalized Token)

모델별 단가 차이를 흡수하는 추상 단위. **코드 기본 모델인 Claude 4 Sonnet을 기준**으로 설정.

> ⚠️ **v1.1 수정**: 기존 설계는 Claude 3.5 Sonnet을 기준으로 했으나, 코드베이스의
> 실제 기본 모델은 `claude-sonnet-4-20250514` (Claude 4 Sonnet). 단가 기준을 현실에 맞게 수정.

```kotlin
data class NormalizedToken(
    val rawTokens: Int,
    val modelWeight: ModelWeight,
    val ntValue: Double = rawTokens * modelWeight.blended()
)

// 가중치 = 기준 모델 단가 / 해당 모델 단가
// 입력/출력 분리 (단가가 다르므로 단일 가중치로는 부정확)
data class ModelWeight(
    val inputWeight: Double,    // 기준 입력 단가 / 해당 입력 단가
    val outputWeight: Double,   // 기준 출력 단가 / 해당 출력 단가
    val blendedRatio: Double = 0.7  // 입력:출력 비율 가정 (70:30)
) {
    fun blended(): Double = inputWeight * blendedRatio + outputWeight * (1 - blendedRatio)
}

object ModelWeights {
    // 기준: Claude 4 Sonnet ($3/$15 per MTok)
    // ⚠️ 단가는 자가 계산 파이프라인(self-calculating-pricing.md)으로 관리
    // 여기의 값은 하드코딩된 기본값 (오프라인 폴백용)
    private const val BASE_INPUT = 3.00
    private const val BASE_OUTPUT = 15.00

    val CLAUDE_4_SONNET = ModelWeight(1.0, 1.0)         // $3/$15 (기준)
    val GPT_4O = ModelWeight(1.2, 1.5)                  // $2.5/$10
    val GPT_41 = ModelWeight(1.5, 1.875)                // $2/$8
    val GEMINI_25_PRO = ModelWeight(2.4, 1.5)          // $1.25/$10
    val GEMINI_25_FLASH = ModelWeight(6.0, 2.86)       // $0.15/$3.50
    val GLM_51 = ModelWeight(60.0, 60.0)               // $0.05/$0.25 (Ollama Cloud, 추정)
    val OLLAMA_LOCAL = ModelWeight(Double.MAX_VALUE, Double.MAX_VALUE)  // 무료 (특수 처리)
}
```

**단가 출처**: 각 provider 공식 문서 (2026-05 기준)

| Provider | 모델 (코드 기본) | Input $/MTok | Output $/MTok | 가중치(입력) | 가중치(출력) |
|---|---|---|---|---|---|
| Anthropic | claude-sonnet-4-20250514 ✅ | $3.00 | $15.00 | 1.0 | 1.0 |
| OpenAI | gpt-4o ✅ | $2.50 | $10.00 | 1.2 | 1.5 |
| OpenAI | gpt-4.1 | $2.00 | $8.00 | 1.5 | 1.875 |
| Google | gemini-2.5-pro | $1.25 | $10.00 | 2.4 | 1.5 |
| Google | gemini-2.5-flash ✅ | $0.15 | $3.50 | 6.0 | 2.86 |
| Ollama Cloud | glm-5.1 ✅ | $0.05 | $0.25 | 60.0 | 60.0 |
| Ollama Local | any | $0 | $0 | ∞ | ∞ |

> ✅ = 코드베이스 LlmProvider.kt의 기본 모델명과 일치

**업데이트 전략**:
- 앱 내 하드코딩된 기본값 (오프라인 동작 보장)
- GitHub raw JSON으로 원격 업데이트 (앱 시작 시 선택적 체크)
- 유저 커스텀값은 항상 우선

### Layer 3: 세션 내 슬롯 할당

```kotlin
data class TokenSlots(
    val systemPrompt: Double = 0.25,      // 25%: 고정 시스템 프롬프트
    val webContext: Double = 0.35,        // 35%: WebView 캡처, DOM
    val chatHistory: Double = 0.25,       // 25%: 대화 히스토리
    val responseBuffer: Double = 0.15     // 15%: 응답 여유
) {
    init {
        require(systemPrompt + webContext + chatHistory + responseBuffer == 1.0) {
            "Slot percentages must sum to 1.0"
        }
    }
}
```

---

## 3. 실시간 예산 관리

### TokenBudget 클래스

```kotlin
class TokenBudget(
    private val dollarBudget: DollarBudget,
    private val pricing: PricingTable,
    private val slots: TokenSlots = TokenSlots(),
    private val currentProvider: LlmProvider
) {
    private val _ntSpent = MutableStateFlow(0.0)
    val ntSpent: StateFlow<Double> = _ntSpent.asStateFlow()
    
    private val _dollarSpent = MutableStateFlow(0.0)
    val dollarSpent: StateFlow<Double> = _dollarSpent.asStateFlow()
    
    // 예산 임계값 (소프트/하드)
    val softLimit = dollarBudget.sessionLimit * 0.80
    val hardLimit = dollarBudget.sessionLimit

    /**
     * 메시지 전송 전 예산 확인
     * @return 예산 충족 시 true, 초과 시 false
     */
    fun canProceed(messages: List<ChatMessage>, context: WebContextPacket?): BudgetCheck {
        val estimatedInput = estimateTokens(messages, context)
        val providerWeight = pricing.getWeight(currentProvider)
        val estimatedNt = estimatedInput * providerWeight.blended()
        
        val projectedDollar = ntToDollar(estimatedNt, currentProvider)
        val totalAfterRequest = _dollarSpent.value + projectedDollar
        
        return when {
            totalAfterRequest > hardLimit -> BudgetCheck.ExceedsHard
            totalAfterRequest > softLimit -> BudgetCheck.ExceedsSoft(projectedDollar)
            else -> BudgetCheck.Ok(projectedDollar)
        }
    }

    /**
     * 실제 사용량 업데이트 (API 응답의 usage 필드 기준)
     */
    fun recordUsage(usage: TokenUsage) {
        val weight = pricing.getWeight(currentProvider)
        val nt = (usage.inputTokens * weight.inputWeight + usage.outputTokens * weight.outputWeight)
        _ntSpent.value += nt
        
        val dollar = ntToDollar(nt, currentProvider)
        _dollarSpent.value += dollar
    }

    /**
     * 컨텍스트 슬롯 확인 — 트리거 조건 충족 시 repo 주입 여부 결정
     */
    fun shouldInjectRepoContext(): Boolean {
        val webContextUsed = _ntSpent.value * slots.webContext
        val webContextLimit = calculateTotalNt() * slots.webContext * 0.80
        return webContextUsed < webContextLimit
    }
}

sealed class BudgetCheck {
    data class Ok(val projectedCost: Double) : BudgetCheck()
    data class ExceedsSoft(val projectedCost: Double) : BudgetCheck()
    object ExceedsHard : BudgetCheck()
}
```

### 토큰 추정 (클라이언트 측)

API 호출 전 실제 토큰 수를 알 수 없으므로 보수적 추정:

```kotlin
object TokenEstimator {
    /**
     * 보수적 토큰 추정
     * 실제 토큰 수의 ~120%로 추정, 80%에서 경고
     */
    fun estimate(message: String, language: Language = Language.KOREAN): Int {
        return when (language) {
            Language.KOREAN -> (message.length * 0.5 * 1.2).toInt()
            Language.ENGLISH -> (message.split(" ").size * 1.3 * 1.2).toInt()
            Language.JAPANESE -> (message.length * 0.6 * 1.2).toInt()
            Language.CHINESE -> (message.length * 0.5 * 1.2).toInt()
        }
    }
    
    fun estimate(messages: List<ChatMessage>, context: WebContextPacket?, provider: LlmProvider): Int {
        val textTokens = messages.sumOf { estimate(it.content) }
        val imageTokens = context?.let { 
            estimateImageTokens(it.screenshot, provider) 
        } ?: 0
        return textTokens + imageTokens
    }
    
    /**
     * Provider별 이미지 토큰 추정
     * 
     * OpenAI: 512x512=85, 1024x1024=765 tokens (detail 레벨별 차등)
     * Anthropic: 이미지당 ~1600 tokens (권장 해상도 기준)
     * Gemini: 무료 티어에서는 이미지 과금 없음, 입력 토큰으로 계산 시 보수적 추정
     * Ollama: 모델별 상이, 보수적 추정
     */
    fun estimateImageTokens(
        base64Image: String?,
        provider: LlmProvider,
        detail: String = "auto"
    ): Int {
        if (base64Image.isNullOrBlank()) return 0
        
        // Base64 → 실제 바이트 크기 추정
        val bytes = (base64Image.length * 3) / 4
        val megapixels = (bytes / 3) / 1_000_000.0  // RGB 가정
        
        return when (provider) {
            is LlmProvider.OpenAi -> {
                when (detail) {
                    "low" -> 85
                    "high" -> (85 + megapixels * 170).toInt().coerceIn(85, 1105)
                    else -> 200  // auto 기본값 (보수적)
                }
            }
            is LlmProvider.Anthropic -> {
                (megapixels * 1600).toInt().coerceIn(400, 1600)
            }
            is LlmProvider.Gemini -> {
                (megapixels * 258).toInt().coerceIn(100, 1000)
            }
            is LlmProvider.Ollama -> {
                1000  // 보수적 추정
            }
        }
    }
}
```

---

## 4. UI/UX 흐름

### 설정 화면

```
┌─────────────────────────────────────┐
│ 💰 Token Budget                     │
│                                     │
│ [ ] Enable budget tracking          │
│                                     │
│ ── When enabled ──                  │
│                                     │
│ Session limit:    [====|===] $0.50  │
│                   [min] [$] [max]   │
│                                     │
│ Monthly limit:     [====|====] $20  │
│    (optional, 0 = unlimited)        │
│                                     │
│ ── Exceed behavior ──              │
│ (•) Warn only                       │
│ ( ) Pause and ask                   │
│ ( ) Hard stop                       │
│                                     │
│ [View pricing table]                │
│ [Reset to defaults]                 │
└─────────────────────────────────────┘
```

### 세션 중 화면

```
┌─────────────────────────────────────┐
│ Chat with AI                 ⚡ 12% │
│ ═══════════════════════════════════ │
│                                     │
│ User: ...                           │
│ AI: ...                             │
│                                     │
│ ── Input area ──                    │
│                                     │
│ [Message...              ] [Send]  │
│                                     │
│ 💰 $0.032 / $0.50  |  6.4% used     │
│ █████░░░░░░░░░░░░░░░░░░░░░░░░░     │
│ [Details]                           │
└─────────────────────────────────────┘
```

### 상세 비용 대화상자

```
┌─────────────────────────────────────┐
│ Session Cost Details        [X]   │
│ ═══════════════════════════════════ │
│                                     │
│ This session                        │
│   Requests: 12                      │
│   Input:  4,230 tokens              │
│   Output: 1,847 tokens              │
│   Total:  $0.032                    │
│                                     │
│ ┌─ By Model ──────────────────────┐ │
│ │ Claude 4 Sonnet                  │ │
│ │   In: 2,100 × $3.00/MTok = $0.006│ │
│ │   Out:   820 × $15.00/MTok = $0.012│ │
│ │   Subtotal: $0.018              │ │
│ │                                 │ │
│ │ GPT-4o                         │ │
│ │   In: 2,130 × $2.50/MTok = $0.005│ │
│ │   Out: 1,027 × $10.00/MTok = $0.010│ │
│ │   Subtotal: $0.014              │ │
│ └─────────────────────────────────┘ │
│                                     │
│ Budget: $0.50  |  Used: 6.4%       │
│                                     │
│ [Open provider dashboard]           │
│  → Verify in OpenAI/Anthropic usage │
└─────────────────────────────────────┘
```

---

## 5. 트리거 조건 연동

S5 토큰 예산 관리는 GitHub repo 컨텍스트 주입의 **선행 조건**이다.

```kotlin
// AgentLoop에서 repo 컨텍스트 주입 여부 결정
suspend fun shouldInjectRepoContext(
    errorMessage: String,
    tokenBudget: TokenBudget
): RepoContextDecision {
    // 트리거 조건 1: 에러가 앱/네이티브 관련
    if (!isAppLevelError(errorMessage)) {
        return RepoContextDecision.Skip("Not an app-level error")
    }
    
    // 트리거 조건 2: 컨텍스트 슬롯 여유 확인
    if (!tokenBudget.shouldInjectRepoContext()) {
        return RepoContextDecision.Skip("Web context slot nearly full")
    }
    
    // 트리거 조건 3: 달러 예산 여유 확인
    val repoContextCost = estimateRepoContextTokens() * pricing.getWeight(provider).blended()
    val projectedDollar = tokenBudget.ntToDollar(repoContextCost, provider)
    if (projectedDollar > tokenBudget.remainingDollar * 0.20) {
        // repo 컨텍스트가 잔여 예산의 20% 이상이면 경고
        return RepoContextDecision.Warn(projectedDollar)
    }
    
    return RepoContextDecision.Inject(repoContextCost)
}
```

---

## 6. 이벤트/상태

```kotlin
sealed class BudgetEvent {
    object BudgetEnabled : BudgetEvent()
    object BudgetDisabled : BudgetEvent()
    data class SoftLimitApproached(val percentUsed: Double) : BudgetEvent()
    data class HardLimitReached(val totalSpent: Double) : BudgetEvent()
    data class ModelChanged(
        val fromProvider: LlmProvider,
        val toProvider: LlmProvider,
        val newWeight: Double
    ) : BudgetEvent()
}

sealed class BudgetState {
    object Disabled : BudgetState()
    data class Active(
        val ntSpent: Double,
        val dollarSpent: Double,
        val dollarLimit: Double,
        val percentUsed: Double,
        val warnings: Int = 0
    ) : BudgetState()
}
```

---

## 7. 보안/프라이버시

| 항목 | 정책 | 이유 |
|---|---|---|
| 예산 데이터 저장 | EncryptedSharedPreferences | 민감 설정 |
| 원격 단가표 검증 | SHA-256 checksum | 변조 방지 |
| 달러 잔액 LLM 전달 | 유저 선택적 동의 | 정보 노출 최소화 |
| API 키로 과금 조회 | 앱이 직접, LLM 경유 없음 | Premortem 사고 2 방지 |

---

## 8. 구현 체크리스트

- [ ] `TokenBudget` 클래스 구현
- [ ] `TokenSlots` 기본값 설정
- [ ] `TokenEstimator` 추정 로직
- [ ] 설정 UI: ON/OFF 토글, 슬라이더, 동작 선택
- [ ] 세션 UI: 비용 표시, 진행바, 상세 대화상자
- [ ] `PricingTable` JSON 스키마
- [ ] 단가표 원격 업데이트 메커니즘
- [ ] 예산 이벤트/상태 관리
- [ ] 트리거 조건 연동 (GitHub repo 컨텍스트)

---

## 9. 의견 요청 포인트

1. **기본값**: 세션 $0.50 적절한가? 개발 워크플로우에 맞는가?
2. **슬롯 비율**: system 25%, context 35%, history 25%, buffer 15% 합리적인가?
3. **초과 동작**: 경고만이 기본 괜찮은가? 아니면 Pause가 더 안전한가?
4. **단가 업데이트**: 얼마나 자주 체크할까? (매 시작 / 일일 / 주간)
