# DevCompanion 리팩토링 계획서

**작성일**: 2026-06-20  
**상태**: 계획 완료, 착수 전  

---

## 전수 조사 요약

프로젝트 전체 74개 .kt 파일, 핵심 15개 파일 심층 분석.  
서브에이전트 전수 조사 + 2회 적대적 재검토 완료.

### 조사 범위
- AppHealthMonitor, SessionLog, AiChatViewModel, AgentLoop, ToolExecutor, PermissionGate, ContextCompactor, UndoStack, BridgeServer, BrowserEngine, LlmRepositoryImpl, MainActivity, DevCompanionApp, SessionScratchpad

---

## 재평가 원칙

각 항목을 "실제 크래시/데이터 손실이 발생하는가? 빈도는? 수정 공수 대비 효과는?" 기준으로 2회 재평가:

1차 → 2차 재평가 결과:
- P0-1 engineScope 누수: P0 → **P2** (실제 OOM 리포트 없음, destroy()에서 취소됨)
- P0-2 BridgeServer 교착: P0 → **P2** (타임아웃 존재, 크래시 위험 없음)
- P0-3 ConfirmationHandler busy-wait: P0 → **P1** (기능적 버그 아님, 코드 품질)
- P1-3 이중 저장 경쟁: P1 → **P3** (데이터 손실 없음)
- P1-4 LlmRepositoryImpl 재생성: P1 → **P3** (SHARED_CLIENT로 OkHttp 재사용)
- P1-6 AgentLoop state 경쟁: P1 → **P2** (발생 시나리오 좁음)
- P2-2 SessionLog 라이프사이클: P2 → **P3** (중복 쓰기 무해)
- P2-3 lastFrameTimeNanos 리셋: P2 → **P3** (가짜 버그, 이미 리셋됨)
- P2-4 llmCaller 캡처: P2 → **P3** (UI 깜빡임 수준)
- P2-5 isToolResult 필터: P2 → **P3** (의도적, 문서화 필요)
- P2-6 ContextCompactor 타임아웃: P2 → **P3** (OkHttp 기본 타임아웃 존재)

---

## Phase 1: 실제 버그 수정 (독립적, 즉시 가능)

| # | 항목 | 등급 | 공수 | 파일 | 설명 |
|---|------|------|------|------|------|
| 1 | UndoStack 동시성 수정 | P1 | 20m | `UndoStack.kt` | `MutableList` → `ArrayDeque`, push/undo에 `synchronized` |
| 2 | SessionLog flush race 수정 | P1 | 30m | `SessionLog.kt` | snapshot-copy 패턴 도입. flush 시 버전/타임스탬프 기반으로 중복 건너뛰기 방지 |
| 3 | BridgeServer eval 이스케이프 | P1 | 15m | `BridgeServer.kt` | 수동 이스케이프 → `JsUtils.escapeJsString` 사용 |
| 4 | ConfirmationHandler → CompletableDeferred | P1 | 45m | `AiChatViewModel.kt` | busy-wait `delay(500)` 루프 → `CompletableDeferred` 대기 |

### 의존도
- 1~4 모두 독립적. 순서 무관.
- 4번은 Phase 2-5(engineScope)에서 패턴 참고됨.

---

## Phase 2: 성능/일관성 (Phase 1 완료 후 권장, 독립 실행 가능)

| # | 항목 | 등급 | 공수 | 파일 | 설명 |
|---|------|------|------|------|------|
| 5 | engineScope 생명주기 정리 | P2 | 1h | `AgentLoop.kt`, `AiChatViewModel.kt` | `engineScope`를 ViewModel에서 주입하거나 `viewModelScope` 기반 실행 |
| 6 | BridgeServer per-endpoint lock 분리 | P2 | 1h | `BridgeServer.kt` | `synchronized(this)` → `CompletableDeferred` per-request (eval, screenshot, dom 각각) |
| 7 | AgentLoop state 업데이트 Main 디스패처 통일 | P2 | 30m | `AgentLoop.kt` | `_state.value` 설정을 `withContext(Dispatchers.Main)`으로 래핑 |

### 의존도
- 5 ← 4의 CompletableDeferred 패턴 참고
- 6 ← 3과 같은 파일(BridgeServer), 순차 적용
- 7 ← 독립

---

## Phase 3: 코드 품질 (선택적)

| # | 항목 | 등급 | 공수 | 파일 | 설명 |
|---|------|------|------|------|------|
| 8 | BrowserEngine 생명주기 명시화 | P2 | 1h | `BrowserTab.kt`, `MainActivity.kt` | 재구성 시 이전 엔진 `destroy()` 보장 |
| 9 | AppHealthMonitor uninstall 상태 리셋 | P3 | 10m | `AppHealthMonitor.kt` | `lastFrameTimeNanos`, `frameCount`, `consecutiveDroppedFrames` 초기화 |
| 10 | ContextCompactor 명시적 타임아웃 | P3 | 15m | `ContextCompactor.kt` | `withTimeout(30_000)` 래핑 |

### 의존도
- 8 ← 5 완료 후 의미 있음
- 9, 10 ← 독립

---

## Phase 4: 아키텍처 리팩토링 (테스트 인프라 선행 필요)

| # | 항목 | 등급 | 공수 | 설명 |
|---|------|------|------|------|
| 11 | AiChatViewModel 분리 | P3 | 4h | `ChatRepository`, `AgentController`, `StreamingController` 분리 |
| 12 | AgentLoop 책임 분리 | P3 | 3h | `ToolRunner` 분리 (7→3 책임) |
| 13 | SessionLog DI 가능 분리 | P3 | 2h | `object` → `SessionLogger` 인터페이스 + `SessionLogImpl` |
| 14 | AppHealthMonitor DI 가능 분리 | P3 | 1h | `object` → `HealthMonitor` 인터페이스 + `AppHealthMonitorImpl` |

### 전제조건
- `androidTest` 기본 세팅 (ViewModel 단위 테스트 가능 환경)
- Robolectric 또는 에뮬레이터 CI 통합
- Phase 1-3 완료 후 안정적인 기반 위에서 진행

---

## 의존도 그래프

```
Phase 1 (독립):
  1. UndoStack ────┐
  2. SessionLog ────┤
  3. BridgeEval ────┤
  4. ConfirmHandler ┘

Phase 2 (Phase 1 완료 후 권장):
  5. engineScope ←── 4의 CompletableDeferred 패턴 참고
  6. BridgeLock ←── 3과 같은 파일 (순차 적용)
  7. StateMain ←── 독립

Phase 3 (선택적):
  8. EngineLifecycle ←── 5 완료 후 의미
  9. HealthReset ←── 독립
  10. CompactorTimeout ←── 독립

Phase 4 (테스트 인프라 선행):
  11-14 ←── androidTest 기본 세팅 필요
```

## 총 공수 추정

| Phase | 공수 | 비고 |
|-------|------|------|
| Phase 1 | ~1h 50m | 독립적, 즉시 가능 |
| Phase 2 | ~2h 30m | Phase 1 완료 후 권장 |
| Phase 3 | ~1h 25m | 선택적 |
| Phase 4 | ~10h | 테스트 인프라 선행 |

---

## 상세 기술 명세

### #1 UndoStack 동시성 수정
```kotlin
// Before: MutableList<String> + removeAt(0) O(n)
private val stack = mutableListOf<String>()

// After: ArrayDeque<String> + synchronized
private val stack = ArrayDeque<String>(MAX_STACK_SIZE)

fun push(url: String) = synchronized(stack) {
    if (stack.size >= MAX_STACK_SIZE) stack.removeFirst()
    stack.addLast(url)
}

suspend fun undo(engine: BrowserEngine): String? = synchronized(stack) {
    if (stack.isEmpty()) return null
    stack.removeLast()
}.let { url ->
    withContext(Dispatchers.Main) { engine.goBack() }
    url
}
```

### #2 SessionLog flush race 수정
```kotlin
// Before: count-based, non-atomic
@Volatile private var lastFlushCount = 0

// After: snapshot-based with versioning
private data class FlushMarker(val version: Long, val count: Int)
@Volatile private var lastFlushMarker = FlushMarker(0L, 0)
private val flushVersion = AtomicLong(0)

suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
    val marker = lastFlushMarker
    val snapshot = buffer.toList()
    if (snapshot.size <= marker.count) return@withContext
    val newEvents = snapshot.drop(marker.count)
    // ... write to disk ...
    lastFlushMarker = FlushMarker(flushVersion.incrementAndGet(), snapshot.size)
}
```

### #3 BridgeServer eval 이스케이프
```kotlin
// Before: manual escaping
val safeExpr = expression
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\u0000", "\\0")

// After: JsUtils.escapeJsString (already used in ToolExecutor)
val evalJs = """
    (function(){
        try {
            var r = eval(${JsUtils.escapeJsString(expression)});
            ...
        } catch(e) { ... }
    })()
""".trimIndent()
```

### #4 ConfirmationHandler → CompletableDeferred
```kotlin
// Before: busy-wait
while (_confirmationResult.value == null && System.currentTimeMillis() < deadline) {
    delay(500)
}
val result = _confirmationResult.value ?: false

// After: CompletableDeferred
private val confirmationDeferred = CompletableDeferred<Boolean>()

// In confirmationHandler:
loop.confirmationHandler = { call, details ->
    _pendingConfirmation.value = call to details
    _agentState.value = AgentState.WaitingConfirmation(call)
    confirmationDeferred.await()  // suspends without polling
}

// In respondConfirmation:
fun respondConfirmation(approved: Boolean) {
    confirmationDeferred.complete(approved)
    // Reset for next confirmation
    _confirmationResult.value = approved
}
```

---

## 검증 방법

각 Phase 완료 후:
1. **컴파일**: GitHub CI (Free Debug/Release + Gecko Debug/Release)
2. **동작 테스트**: 실기기에서 에이전트 루프 실행, confirmation 대기, undo/redo
3. **SessionLog 검증**: 30초 이상 사용 후 로그 내보내기 → 이벤트 누락/중복 확인
4. **BridgeServer 검증**: curl로 eval/dom/screenshot 동시 요청 → 타임아웃 없이 응답

---

## 파일 변경 예상 목록

| Phase | 파일 |
|-------|------|
| 1 | `UndoStack.kt`, `SessionLog.kt`, `BridgeServer.kt`, `AiChatViewModel.kt` |
| 2 | `AgentLoop.kt`, `BridgeServer.kt` (재변경), `AiChatViewModel.kt` (재변경) |
| 3 | `BrowserTab.kt`, `MainActivity.kt`, `AppHealthMonitor.kt`, `ContextCompactor.kt` |
| 4 | `AiChatViewModel.kt` (대폭 변경), `AgentLoop.kt` (분리), `SessionLog.kt` (인터페이스 분리), `AppHealthMonitor.kt` (인터페스 분리) |