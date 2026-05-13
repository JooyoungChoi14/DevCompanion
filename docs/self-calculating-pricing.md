# 자가 계산 단가표 — 설계 문서

> 버전: 1.1 | 작성일: 2026-05-13 | 상태: 검증 완료 (2차)
> 
> ⚠️ **v1.1 수정사항** (근거 재검증 결과):
> - OpenRouter: `provider/model:free` 형태의 free tier 모델 존재 → `OpenRouterModelId`로 파싱 필요
> - 업데이트 주기: provider별 차등 적용 (OpenRouter=24h, OpenAI=7d, Anthropic=7d, Gemini=30d, Ollama=30d)
> - 단가표 기준 모델: Claude 3.5 Sonnet → Claude 4 Sonnet으로 정렬 (token-budget-design.md와 일치)
> - Gemini 2.5 Flash 단가 추가 (코드 기본 모델)

## 1. 목표

모델이 빠르게 추가되는 환경(Ollama, OpenRouter 등)에서 **수동 단가 업데이트의 한계**를 극복한다. LLM이 공식 문서를 파싱해 단가표를 제안하고, 유저 확인 후 반영.

**핵심 원칙**: LLM은 제안만, 결정권은 유저에게. API 키는 LLM이 절대 직접 사용하지 않음.

---

## 2. 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────┐
│                    자가 계산 파이프라인                      │
│                                                             │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐ │
│  │   Detection  │────>│  Collection  │────>│   Parsing    │ │
│  └──────────────┘     └──────────────┘     └──────┬───────┘ │
│        ↑                                          │         │
│        └──────────────────────────────────────────┘         │
│                    유저 확인 (LLM 제안)                      │
│                                                             │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│              Provider별 데이터 소스 전략                      │
│                                                             │
│  OpenRouter ──> /api/v1/models (JSON) ──> 직접 매핑 ✅      │
│  OpenAI ──────> 공식 가격 페이지 ──────> LLM 파싱 ⚠️        │
│  Anthropic ───> 공식 가격 페이지 ──────> LLM 파싱 ⚠️        │
│  Gemini ──────> 공식 가격 페이지 ──────> LLM 파싱 ⚠️        │
│  Ollama ──────> 수동 입력/커뮤니티 ────> 유저 커스텀       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. JSON 스키마: 단가표 공통 포맷

```json
{
  "$schema": "https://devcompanion.app/schemas/pricing-v1.json",
  "version": 1,
  "generatedAt": "2026-05-13T00:00:00Z",
  "source": "github",
  "sourceUrl": "https://raw.githubusercontent.com/JooyoungChoi14/DevCompanion/main/pricing/pricing-v1.json",
  "checksum": "sha256:abc123...",
  
  "providers": {
    "anthropic": {
      "models": [
        {
          "id": "claude-sonnet-4-20250514",
          "displayName": "Claude 4 Sonnet",
          "inputPricePerMTok": 3.00,
          "outputPricePerMTok": 15.00,
          "contextWindow": 200000,
          "supportsVision": true,
          "confidence": "high",
          "source": "official",
          "effectiveDate": "2024-10-22"
        }
      ]
    },
    "openai": { ... },
    "gemini": { ... },
    "ollama": { ... },
    "openrouter": { ... }
  }
}
```

### Kotlin 데이터 클래스

```kotlin
data class PricingTable(
    val version: Int,
    val generatedAt: Long,
    val source: PricingSource,
    val checksum: String,
    val providers: Map<String, ProviderPricing>
) {
    fun getModel(provider: String, modelId: String): ModelPricing? {
        return providers[provider]?.models?.find { it.id == modelId }
    }
    
    fun getWeight(provider: String, modelId: String): ModelWeight {
        val model = getModel(provider, modelId) ?: return ModelWeight(1.0, 1.0)
        return calculateWeight(model.inputPricePerMTok, model.outputPricePerMTok)
    }
    
    private fun calculateWeight(inputPrice: Double, outputPrice: Double): ModelWeight {
        val baseInput = 3.00  // Claude 4 Sonnet 기준
        val baseOutput = 15.00
        return ModelWeight(
            inputWeight = baseInput / inputPrice,
            outputWeight = baseOutput / outputPrice
        )
    }
}

data class ProviderPricing(
    val models: List<ModelPricing>
)

data class ModelPricing(
    val id: String,
    val displayName: String,
    val inputPricePerMTok: Double,
    val outputPricePerMTok: Double,
    val contextWindow: Int,
    val supportsVision: Boolean,
    val confidence: ConfidenceLevel,
    val source: PricingSource,
    val effectiveDate: String
)

enum class ConfidenceLevel {
    HIGH,    // 공식 API JSON
    MEDIUM,  // LLM 파싱, 공식 문서 기반
    LOW,     // 커뮤니티, 추정
    CUSTOM   // 유저 직접 입력
}

enum class PricingSource {
    OFFICIAL_API,      // OpenRouter /api/v1/models
    OFFICIAL_WEB,      // LLM이 파싱한 공식 페이지
    COMMUNITY,         // 커뮤니티 기여
    CUSTOM             // 유저 입력
}
```

---

## 4. Provider별 전략

### 4.1 OpenRouter — 구조화된 JSON API

**가장 안정적인 소스**. LLM 파싱 불필요.

> ⚠️ **v1.1 수정**: OpenRouter 모델 ID에 `provider/model:free` 형태의 free tier가 존재.
> 이 형태를 올바르게 파싱하고, free tier는 단가 $0으로 처리해야 함.

```kotlin
data class OpenRouterModelId(
    val provider: String,      // "anthropic"
    val model: String,          // "claude-sonnet-4-20250514"
    val isFree: Boolean         // true if ":free" suffix
) {
    companion object {
        fun parse(fullId: String): OpenRouterModelId {
            val parts = fullId.split(":")
            val isFree = parts.getOrElse(1) { "" } == "free"
            val baseId = parts[0]
            val baseParts = baseId.split("/")
            return OpenRouterModelId(
                provider = baseParts.getOrElse(0) { "" },
                model = baseParts.getOrElse(1) { baseId },
                isFree = isFree
            )
        }
    }
    
    /**
     * free tier는 $0로 처리
     */
    fun effectivePrice(originalPrice: Double): Double {
        return if (isFree) 0.0 else originalPrice
    }
}

class OpenRouterPricingClient(private val okHttpClient: OkHttpClient) {
    private val apiUrl = "https://openrouter.ai/api/v1/models"
    
    suspend fun fetchPricing(): List<ModelPricing> {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/json")
            .build()
        
        val response = okHttpClient.newCall(request).execute()
        val json = response.body?.string() ?: return emptyList()
        
        return parseOpenRouterModels(json)
    }
    
    private fun parseOpenRouterModels(json: String): List<ModelPricing> {
        val root = JsonParser.parseString(json).asJsonObject
        val data = root.getAsJsonArray("data")
        
        return data.map { element ->
            val obj = element.asJsonObject
            val fullId = obj.get("id").asString  // "anthropic/claude-sonnet-4-20250514" or "openai/gpt-4o:free"
            val parsedId = OpenRouterModelId.parse(fullId)
            val pricing = obj.getAsJsonObject("pricing")
            
            val rawInputPrice = pricing.get("prompt")?.asDouble?.times(1_000_000) ?: 0.0
            val rawOutputPrice = pricing.get("completion")?.asDouble?.times(1_000_000) ?: 0.0
            
            ModelPricing(
                id = fullId,
                displayName = obj.get("name")?.asString ?: fullId,
                inputPricePerMTok = parsedId.effectivePrice(rawInputPrice),
                outputPricePerMTok = parsedId.effectivePrice(rawOutputPrice),
                contextWindow = obj.get("context_length")?.asInt ?: 0,
                supportsVision = obj.get("architecture")?.asJsonObject
                    ?.get("modality")?.asString?.contains("image") ?: false,
                confidence = if (parsedId.isFree) ConfidenceLevel.HIGH else ConfidenceLevel.HIGH,
                source = PricingSource.OFFICIAL_API,
                effectiveDate = obj.get("created")?.asLong?.let { 
                    formatTimestamp(it) 
                } ?: currentDate()
            )
        }
    }
}
```

### 4.2 직접 API (OpenAI, Anthropic, Gemini) — LLM 파싱

공식 문서는 HTML 테이블. LLM이 구조화된 JSON으로 변환.

```kotlin
class PricingParserAgent(
    private val llmRepository: LlmRepository
) {
    /**
     * 공식 가격 페이지 원문을 JSON으로 변환
     * 
     * @param provider 공식 문서 URL
     * @param rawHtml fetch한 HTML 원문
     * @return 파싱된 가격표 또는 null
     */
    suspend fun parsePricingPage(
        provider: String,
        rawHtml: String
    ): List<ModelPricing>? {
        val prompt = buildString {
            appendLine("Extract pricing information from the following $provider documentation.")
            appendLine()
            appendLine("Input: HTML content from ${getPricingUrl(provider)}")
            appendLine()
            appendLine("Task:")
            appendLine("1. Find all model pricing tables")
            appendLine("2. Extract: model ID, display name, input price ($/1M tokens), output price ($/1M tokens), context window")
            appendLine("3. Return as JSON array matching this schema:")
            appendLine()
            appendLine("```json")
            appendLine("[")
            appendLine("  {")
            appendLine("    \"id\": \"gpt-4o\",")
            appendLine("    \"displayName\": \"GPT-4o\",")
            appendLine("    \"inputPricePerMTok\": 2.50,")
            appendLine("    \"outputPricePerMTok\": 10.00,")
            appendLine("    \"contextWindow\": 128000,")
            appendLine("    \"supportsVision\": true")
            appendLine("  }")
            appendLine("]")
            appendLine("```")
            appendLine()
            appendLine("HTML content:")
            appendLine("---")
            appendLine(rawHtml.take(15000))  // 토큰 제한
            appendLine("---")
        }
        
        val response = llmRepository.complete(prompt)
        return parseJsonResponse(response)
    }
    
    /**
     * 파싱 결과 검증
     */
    fun validateParsing(
        original: List<ModelPricing>,
        parsed: List<ModelPricing>
    ): ValidationResult {
        val missing = original.map { it.id } - parsed.map { it.id }.toSet()
        val extra = parsed.map { it.id } - original.map { it.id }.toSet()
        val priceMismatches = parsed.filter { new ->
            val old = original.find { it.id == new.id }
            old != null && abs(old.inputPricePerMTok - new.inputPricePerMTok) > 0.01
        }
        
        return ValidationResult(
            isValid = missing.isEmpty() && priceMismatches.isEmpty(),
            missingModels = missing,
            extraModels = extra,
            priceMismatches = priceMismatches
        )
    }
}
```

### 4.3 자가 검증 — 신뢰도 부여

```kotlin
class PricingValidator {
    /**
     * 신뢰도 평가
     */
    fun assessConfidence(
        parsed: List<ModelPricing>,
        validationResult: ValidationResult
    ): List<ModelPricing> {
        return parsed.map { model ->
            val confidence = when {
                // 공식 API에서 온 경우
                model.source == PricingSource.OFFICIAL_API -> ConfidenceLevel.HIGH
                
                // LLM 파싱 + 검증 통과
                validationResult.isValid && 
                validationResult.priceMismatches.none { it.id == model.id } -> ConfidenceLevel.MEDIUM
                
                // LLM 파싱 + 검증 실패
                else -> ConfidenceLevel.LOW
            }
            
            model.copy(confidence = confidence)
        }
    }
}
```

---

## 5. 업데이트 워크플로우

### 자동 감지 → 유저 확인 → 반영

```
┌─────────────────────────────────────────────┐
│           업데이트 트리거                    │
├─────────────────────────────────────────────┤
│  1. 앱 시작 시 (7일 경과)                   │
│  2. 설정에서 "Check for updates" 클릭        │
│  3. 모델 전환 시 (단가표에 없는 모델)        │
│  4. 세션 중 예산 오차 발견                   │
└─────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────┐
│           데이터 수집                        │
├─────────────────────────────────────────────┤
│  OpenRouter: JSON fetch                     │
│  Others: HTML fetch (앱이 직접)              │
└─────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────┐
│           LLM 파싱 (OpenRouter 제외)         │
│  LLM이 HTML → JSON 변환 제안                 │
│  (PAT 없이, 원문만으로)                      │
└─────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────┐
│           유저 확인 UI                       │
├─────────────────────────────────────────────┤
│  "새로운 가격 정보를 발견했습니다"           │
│                                             │
│  변경사항:                                   │
│    gpt-4o: $2.50 → $3.00 (+20%) ⚠️         │
│    claude-sonnet-4: unchanged               │
│    NEW: gemini-2.5-pro @ $1.25/MTok ✨    │
│                                             │
│  [Review Details] [Accept] [Skip] [Custom] │
└─────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────┐
│           반영 및 검증                     │
│  새 단가표 저장 → checksum 계산             │
│  다음 요청부터 적용                         │
└─────────────────────────────────────────────┘
```

### 유저 확인 UI

```
┌─────────────────────────────────────────────────┐
│ Pricing Update Available              [X]       │
│ ═══════════════════════════════════════════════ │
│                                                 │
│ Source: OpenRouter API                          │
│ Last checked: 2026-05-13 09:15                  │
│                                                 │
│ ┌─ Changes ───────────────────────────────────┐ │
│ │                                              │ │
│ │ 🔴 Increased (2)                             │ │
│ │   gpt-4o           $2.50 → $3.00   (+20%)    │ │
│ │   claude-sonnet-4   $3.00 → $3.75  (+25%)    │ │
│ │                                              │ │
│ │ 🟢 Decreased (1)                             │ │
│ │   gemini-1.5-pro   $3.50 → $2.80   (-20%)    │ │
│ │                                              │ │
│ │ ✨ New models (3)                             │ │
│ │   gemini-2.5-pro   @ $1.25/MTok              │ │
│ │   gpt-4.1-mini     @ $0.15/MTok              │ │
│ │   o3               @ $20.00/MTok             │ │
│ │                                              │ │
│ └─────────────────────────────────────────────┘ │
│                                                 │
│ Confidence: 98% (OpenRouter official API)         │
│                                                 │
│ [View Full Table]  [Accept All]  [Review Each]  │
│                                                 │
│ ⚠️ Manual verification recommended for:           │
│    o3 (high price, verify at openai.com/pricing)│
└─────────────────────────────────────────────────┘
```

---

## 6. 커스텀 단가표

유저가 직접 단가를 수정/추가:

```kotlin
class CustomPricingEditor {
    /**
     * 유저가 직접 모델 추가
     */
    fun addCustomModel(
        provider: String,
        modelId: String,
        inputPrice: Double,
        outputPrice: Double,
        contextWindow: Int
    ): ModelPricing {
        return ModelPricing(
            id = modelId,
            displayName = modelId,  // 유저가 나중에 수정 가능
            inputPricePerMTok = inputPrice,
            outputPricePerMTok = outputPrice,
            contextWindow = contextWindow,
            supportsVision = false,  // 유저가 명시적으로 설정
            confidence = ConfidenceLevel.CUSTOM,
            source = PricingSource.CUSTOM,
            effectiveDate = currentDate()
        )
    }
    
    /**
     * 기존 모델 가격 수정
     */
    fun overridePrice(
        existing: ModelPricing,
        newInputPrice: Double,
        newOutputPrice: Double
    ): ModelPricing {
        return existing.copy(
            inputPricePerMTok = newInputPrice,
            outputPricePerMTok = newOutputPrice,
            confidence = ConfidenceLevel.CUSTOM,
            source = PricingSource.CUSTOM
        )
    }
}
```

---

## 7. 보안 및 안전장치

### LLM 파싱 보안 규칙

| 규칙 | 이유 |
|---|---|
| **API 키 없이 원문만** | LLM이 인증 없이도 파싱 가능해야 함 |
| **JSON 스키마 검증** | LLM 환각으로 인한 잘못된 구조 방지 |
| **범위 검사** | inputPrice가 음수거나 1000$/MTok 초과 시 경고 |
| **diff 표시** | 변경사항을 명확히 보여주고 유저 확인 |
| **rollback 가능** | 잘못된 업데이트 시 이전 버전 복원 |

### 잘못된 파싱 감지

```kotlin
fun detectAnomalies(pricing: List<ModelPricing>): List<Anomaly> {
    val anomalies = mutableListOf<Anomaly>()
    
    for (model in pricing) {
        // 비정상적으로 높은 가격
        if (model.inputPricePerMTok > 100) {
            anomalies.add(Anomaly.PriceTooHigh(model.id, model.inputPricePerMTok))
        }
        
        // 비정상적으로 낮은 가격
        if (model.inputPricePerMTok < 0.001 && model.inputPricePerMTok > 0) {
            anomalies.add(Anomaly.PriceTooLow(model.id, model.inputPricePerMTok))
        }
        
        // output > input * 50 (일반적이지 않음)
        if (model.outputPricePerMTok > model.inputPricePerMTok * 50) {
            anomalies.add(Anomaly.OutputPriceSuspicious(model.id))
        }
        
        // context window 이상
        if (model.contextWindow > 10_000_000 || model.contextWindow < 1000) {
            anomalies.add(Anomaly.ContextWindowSuspicious(model.id, model.contextWindow))
        }
    }
    
    return anomalies
}
```

---

## 8. 저장 및 버전 관리

### 로컬 캐시

```
/data/data/com.devcompanion/files/pricing/
├── pricing-v1-current.json       # 현재 적용 중
├── pricing-v1-backup.json        # 이전 버전 (rollback용)
├── pricing-v1-custom.json        # 유저 커스텀 오버라이드
└── .checksum                      # 무결성 검증
```

### 원격 소스

```
https://raw.githubusercontent.com/JooyoungChoi14/DevCompanion/main/pricing/
├── pricing-v1.json               # 공식 단가표
├── pricing-v1.checksum           # SHA-256
└── changelog.md                  # 변경 이력
```

---

## 9. 구현 체크리스트

- [ ] `PricingTable` JSON 스키마 정의
- [ ] `ModelPricing` Kotlin 데이터 클래스
- [ ] OpenRouter API 클라이언트 (`/api/v1/models`)
- [ ] LLM 파싱 에이전트 (HTML → JSON)
- [ ] 파싱 결과 검증 로직
- [ ] 신뢰도 평가 (`ConfidenceLevel`)
- [ ] 업데이트 감지 (버전 체크)
- [ ] 유저 확인 UI (diff 표시)
- [ ] 커스텀 단가표 에디터
- [ ] 이상치 탐지 (`detectAnomalies`)
- [ ] checksum 검증
- [ ] rollback 메커니즘
- [ ] 설정: 자동 업데이트 ON/OFF

---

## 10. 업데이트 정책 (Provider별 차등)

> ⚠️ **v1.1 수정**: 단일 7일 주기에서 provider별 차등 주기로 변경.
> 변경 빈도가 다른 provider에 동일한 업데이트 주기를 적용하면 불필요한 API 호출이 발생.

```kotlin
data class UpdatePolicy(
    val provider: String,
    val checkIntervalHours: Int,
    val urgency: UpdateUrgency
)

enum class UpdateUrgency {
    HIGH,   // 변경 시 즉시 알림 (OpenRouter: 모델 추가/가격 변경 빈번)
    MEDIUM, // 변경 시 배지 표시 (OpenAI, Anthropic: 분기별 변경)
    LOW     // 수동 확인 권장 (Ollama, 로컬: 거의 변경 없음)
}

object UpdatePolicies {
    val DEFAULT = mapOf(
        "openrouter" to UpdatePolicy("openrouter", 24, UpdateUrgency.HIGH),    // 자주 변경
        "openai" to UpdatePolicy("openai", 168, UpdateUrgency.MEDIUM),          // 주간
        "anthropic" to UpdatePolicy("anthropic", 168, UpdateUrgency.MEDIUM),    // 주간
        "gemini" to UpdatePolicy("gemini", 336, UpdateUrgency.LOW),            // 월간
        "ollama" to UpdatePolicy("ollama", 720, UpdateUrgency.LOW)              // 월간+
    )
}
```

## 11. 의견 요청 포인트

1. **업데이트 주기**: 7일 기본 적절한가? 더 짧게?
2. **자동 업데이트**: HIGH confidence만 자동 적용할까? 전부 유저 확인?
3. **커스텀 모델**: 유저가 직접 모델 추가하는 기능 필요한가?
4. **다중 소스**: OpenRouter + 공식 API 병렬로 체크할까?
5. **알림 방식**: 배너? 푸시? 설정 배지?
