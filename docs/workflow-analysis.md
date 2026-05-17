# 유저-LLM-브라우저 내장 데이터 워크플로우 분석

> DevCompanion 아키텍처 기준, 현재 구현 상태와 개선 방향을 기능 범주별로 정리.

---

## 1. 현재 아키텍처 개요

```
┌──────────┐     ┌──────────────┐     ┌─────────────────┐
│   User   │────▶│ AiChatScreen │────▶│ AiChatViewModel │
│ (입력)    │     │ (View/Act 토글) │     │ (상태 관리)      │
└──────────┘     └──────────────┘     └────────┬────────┘
                                               │
                              ┌─────────────────┼─────────────────┐
                              │                 │                  │
                        ┌─────▼─────┐    ┌─────▼─────┐    ┌──────▼──────┐
                        │ Chat 모드  │    │ Act 모드   │    │ 주소창 스마트 │
                        │ sendMessage│    │ AgentLoop  │    │ 라우팅       │
                        └─────┬─────┘    └─────┬─────┘    └──────┬──────┘
                              │                 │                  │
                              ▼                 ▼                  ▼
                        LlmRepository     ToolExecutor       BrowserTab
                        (스트리밍)          (11개 도구)         (URL/DDG/AI)
```

### 키 파일 맵핑

| 역할 | 파일 | 줄수 |
|------|------|------|
| 뷰모델 (상태 오케스트레이션) | `AiChatViewModel.kt` | ~750 |
| 에이전트 루프 | `agent/AgentLoop.kt` | ~300 |
| 도구 실행 | `agent/ToolExecutor.kt` | ~300 |
| 권한 게이트 | `agent/PermissionGate.kt` | ~200 |
| 도구 정의 | `agent/ToolDefinition.kt` | ~250 |
| 시스템 프롬프트 | `SystemPromptBuilder.kt` | ~90 |
| 웹 컨텍스트 캡처 | `WebContextBuilder.kt` | ~320 |
| 주소창 스마트 라우팅 | `BrowserTab.kt` | ~730 |
| LLM 설정 | `LlmSettings.kt` | ~200 |
| URL 히스토리 | `data/UrlHistoryStore.kt` | ~55 |

---

## 2. 워크플로우 범주별 분석

### 범주 A: 모드 기본값 & 전환

**현재 상태:**
- `_agentMode = MutableStateFlow(false)` → View가 기본값 ✅ **수정 완료: `true`(Act)로 변경**
- `setAgentMode()` 호출 시 `LlmSettings.agentModeDefault`에 영속화 ✅ **방금 추가**
- 세션 간 유지: SharedPreferences 통해 보존

**발견된 문제점:**
1. **View 모드가 기본값이었음** → 사용자가 매번 Act로 전환해야 함
   - 해결: 기본값을 Act로 변경 + 영속화 완료
2. **View 모드의 제한이 명확하지 않음** → SystemPromptBuilder에서 "chat mode — no tool access"라고 하지만, 실제로는 자동 캡처만 다름

**개선 제안:**
- [x] Act 기본값 변경 (완료)
- [x] 유저 선호 영속화 (완료)
- [ ] View 모드 이름을 "Chat"으로, Act 모드 이름을 "Agent"로 UI 라벨 통일 (현재: View/Act vs Chat/Agent 혼용)
- [ ] 모드 전환 시 자동 캡처 토글 연동 (Act → 캡처 on, View → 캡처 off)

---

### 범주 B: 주소창 스마트 라우팅

**현재 상태:**
```
입력 → ?접두사 → AI 직행
     → ?접미사(한글/자연어) → AI 직행
     → http(s):// → URL 직행
     → 도메인.포함(공백없음) → https:// 붙여서 이동
     → 나머지 → DDG 검색
```

**발견된 문제점:**
1. **자연어 URL 요청이 DDG로 빠짐** → `"내가 작업하던 사이트로 이동해줘"` → DDG 검색 결과만 보여줌
2. **Act 모드와 주소창이 단절** → 주소창은 브라우저 탭 내부, AI 채팅은 별도 탭
3. **URL 히스토리가 AI에게 전달되지 않음** → `UrlHistoryStore`에 50개 URL 저장되지만 LLM 컨텍스트에 미포함

**개선 제안:**
- [ ] **URL 히스토리 컨텍스트 주입**: `SystemPromptBuilder.build("agent")`에 최근 N개 URL 목록 추가
- [ ] **주소창↔AI 브릿지**: `onAskAi` 콜백으로 전달된 질문이 자동으로 Act 모드에서 실행되도록 개선
- [ ] **URL 패턴 감지**: `"~사이트로 이동해줘"`, `"~URL로 가줘"` 같은 한국어 패턴 → URL 히스토리에서 fuzzy match

---

### 범주 C: 컨텍스트 캡처 & 전달

**현재 상태:**
- `CaptureMode`: Quick(스크린샷만), Standard(스크린+DOM), Full(스크린+DOM+스타일)
- `sendMessage()` (View/Chat): `CaptureMode.Standard`로 자동 캡처
- `sendMessageAgent()` (Act): AgentLoop 1회차에만 `CaptureMode.Standard`, 이후에는 스크린샷 제외
- DOM 스냅샷: 4000자 제한 (`MAX_DOM_LENGTH`)
- 비전 미지원 프로바이더: 스크린샷 자동 제거

**발견된 문제점:**
1. **2회차 이후 스크린샷 누락** → `full.copy(screenshotBase64 = "")` 처리가 과도함
   - LLM이 시각적 변화를 확인할 수 없음
2. **DOM 4000자 제한이 너무 작음** → 복잡한 페이지에서 핵심 요소 누락 가능
3. **캡처 타이밍**: 도구 실행 직후 재캡처가 없음 → LLM이 실행 결과를 텍스트 결과만으로 판단

**개선 제안:**
- [ ] **반복 캡처 전략**: navigate/click/scroll 이후 자동 재캡처 (Quick 모드로)
- [ ] **DOM 크기 예산 관리**: `TokenBudget`과 연동하여 동적 할당
- [ ] **캡처 모드 설정 노출**: Settings에서 Standard/Full 선택 가능하게

---

### 범주 D: 에이전트 루프 & 도구 체계

**현재 상태:**
- 11개 도구: navigate, click, type, scroll, eval_js, get_dom, get_computed_style, set_style, screenshot, submit_form, get_console_logs
- `PermissionGate`: SAFE(자동승인), MODERATE(자동승인), SENSITIVE(사용자확인)
- 루프 제한: 최대 10회 반복, 연속 에러 3회, 동일 액션 반복 3회
- `UndoStack`: navigate/submit_form 실행 전 URL 푸시

**발견된 문제점:**
1. **`eval_js`가 만능도구로 남용** → LLM이 DOM 조작을 eval_js로 하려는 경향
2. **CSS selector 불안정** → 동적 클래스명, SPA에서 DOM 변화 시 selector 무효
3. **`screenshot` 도구가 메타데이터만 반환** → 실제 이미지가 tool result에 포함되지 않음
4. **루프 종료 조건이 명확하지 않음** → "텍스트 응답만 오면 종료"인데, LLM이 계속 도구를 호출하는 케이스

**개선 제안:**
- [ ] **선택자 안정화**: `data-*` 속성 우선, 텍스트 매칭 폴백, XPath 대체 수단 추가
- [ ] **도구 결과에 스크린샷 포함**: navigate/click/type/set_style 후 자동 Quick 캡처
- [ ] **루프 종료 개선**: 연속 텍스트 응답 시 조기 종료, "DONE" 마커 지원
- [ ] **새 도구**: `fill_form`(다중 필드 일괄 입력), `wait_for`(요소 대기)

---

### 범주 E: 시스템 프롬프트 & LLM 지시

**현재 상태:**
- `SystemPromptBuilder.build(mode, url, customInstructions)`로 동적 생성
- Agent 모드: 도구 목록 테이블 포함
- Chat 모드: "도구 접근 불가" 명시
- 커스텀 프롬프트: `LlmSettings.loadCustomPrompt()`로 끝에 추가

**발견된 문제점:**
1. **URL 히스토리 미포함** → "내가 작업하던 사이트" 요청에 대응 불가
2. **도구 사용 전략 지시 부재** → LLM이 eval_js를 남용하거나 selector 없이 click을 시도
3. **한국어 지시 불일치** → 시스템 프롬프트는 영어, 사용자 입력은 한국어 혼재
4. **프롬프트 길이 관리 부재** → URL 히스토리 추가 시 토큰 예산 고려 필요

**개선 제안:**
- [ ] **URL 히스토리 섹션 추가**: 최근 10개 URL을 프롬프트에 삽입
- [ ] **도구 사용 가이드라인 추가**: "DOM 조작은 click/type 우선, eval_js는 최후수단"
- [ ] **선택자 전략 지시**: "data-* 속성 → 텍스트 내용 → CSS 클래스 순서"
- [ ] **토큰 예산 관리**: `TokenBudget`과 연동하여 프롬프트 길이 동적 조절

---

### 범주 F: 메모리 & 세션 연속성

**현재 상태:**
- `UrlHistoryStore`: 최근 50개 URL 영속화 (SharedPreferences)
- `BookmarksStore`: 북마크 영속화
- `ChatHistory`: 대화 ID 자동 복원 (`516b546`에서 구현)
- `LlmSettings`: 프로바이더, API 키, 커스텀 프롬프트, 최대 반복 횟수 영속화
- `agentModeDefault`: ✅ 방금 추가 (Act 모드 기본값 영속화)

**발견된 문제점:**
1. **세션 간 컨텍스트 단절** → 새 대화 ID로 시작하면 이전 작업 맥락 상실
2. **URL 히스토리가 AI에게 안 보임** → 저장은 되지만 LLM 프롬프트에 미반영
3. **작업 히스토리 없음** → "어제 작업하던 거 이어서 해줘" 불가능
4. **쿠키 완전 차단** → LLM이 쿠키 존재 여부조차 모름 (설계 의도이지만, 로그인 상태 감지 불가)

**개선 제안:**
- [ ] **URL 히스토리 → 시스템 프롬프트**: 최근 방문 URL 10개를 컨텍스트로 주입
- [ ] **작업 요약 영속화**: Agent Loop 완료 시 요약을 저장 → 다음 세션에서 "최근 작업" 컨텍스트로 활용
- [ ] **페이지 상태 스냅샷**: navigate 직후 타이틀+URL을 세션 메모리에 저장
- [ ] **쿠키 상태 메타**: 쿠키 값은 마스킹하되 "로그인됨/안됨" 상태는 LLM에게 전달

---

### 범주 G: 보안 & 권한 모델

**현재 상태:**
- `PermissionGate`: 3단계 위험 분류 (SAFE/MODERATE/SENSITIVE)
- SENSITIVE 도구: `eval_js`(위험 패턴 감지), `type`(비밀번호 필드), `submit_form`, `set_style`
- URL 화이트리스트: `http://`, `https://` 만 허용
- 쿠키/스토리지 접근: `eval_js`에서 패턴 감지로 차단
- `Allow Always 금지`: 1회성 승인만 가능 (설계 원칙)

**발견된 문제점:**
1. **click이 MODERATE** → 악의적 버튼(결제, 삭제) 클릭도 자동 승인됨
2. **navigate가 MODERATE** → 피싱 사이트 이동도 자동 승인 가능
3. **set_style이 SENSITIVE** → 디버깅 목적의 무해한 스타일 변경도 확인 필요
4. **DANGEROUS_JS_PATTERNS가 과도** → `fetch`, `localStorage` 등 디버깅에 필요한 패턴도 차단

**개선 제안:**
- [ ] **DOM 기반 동적 위험 분류**: click 대상이 결제/삭제 버튼이면 SENSITIVE로 승격
- [ ] **navigate 위험 분류 강화**: 새 도메인 이동 시 SENSITIVE, 같은 도메인 내는 MODERATE
- [ ] **set_style 낮춤**: DOM 읽기 없는 스타일 변경은 MODERATE로 하향
- [ ] **디버그 모드**: 개발자 설정으로 SENSITIVE 자동 승인 옵션 (경고 표시)

---

### 범주 H: 스트리밍 & UX 피드백

**현재 상태:**
- Chat 모드: 토큰 스트리밍 (`LlmRepository.stream()`)
- Agent 모드: `LlmStreamEvent` (Token/ToolCalls/Start/Complete/Error)
- 상태 표시: `AgentState` → Thinking/CheckingPermission/WaitingConfirmation/ExecutingTool/Error
- 도구 결과: 이모지 프리픽스(🔧/⚠️) + JSON 포맷팅

**발견된 문제점:**
1. **Agent 모드 스트리백이 불안정** → 토큰 단위 업데이트가 `_currentResponse`에만 반영, 완료 후 사라짐
2. **도구 실행 중 피드백 부족** → "ExecutingTool" 상태만 표시, 어떤 도구인지 UI에 안 보임
3. **확인 대화상자 타임아웃** → 60초 타임아웃, 긴 작업 시 부족할 수 있음
4. **취소 UX** → Agent Loop 취소 시 부분 응답 처리가 Chat과 다름

**개선 제안:**
- [ ] **도구 실행 상세 표시**: "🔧 Navigating to example.com..." 같은 실시간 피드백
- [ ] **Agent 모드 진행 바**: 반복 횟수/최대 횟수 시각화
- [ ] **확인 대화상장 개선**: 타겟 요소 미리보기(스크린샷 하이라이트) 포함
- [ ] **스트리밍 안정화**: Agent 모드에서도 점진적 텍스트 표시

---

## 3. 작업 우선순위 제안

### P0 (즉시 적용, 작은 패치)
| # | 작업 | 범주 | 예상 규모 |
|---|------|------|----------|
| 1 | Act 모드 기본값 + 영속화 | A | ✅ 완료 |
| 2 | URL 히스토리 → 시스템 프롬프트 주입 | B+E | ~30줄 |
| 3 | SystemPromptBuilder에 도구 사용 가이드라인 추가 | E | ~20줄 |
| 4 | Agent Loop 2회차 이후 스크린샷 복원 (Quick 모드) | C | ~5줄 |

### P1 (단기, 1-2일)
| # | 작업 | 범주 | 예상 규모 |
|---|------|------|----------|
| 5 | DOM 캡처 크기 예산 관리 (TokenBudget 연동) | C+D | ~80줄 |
| 6 | 도구 실행 후 자동 Quick 캡처 (navigate/click/type) | C+D | ~40줄 |
| 7 | 한국어 URL 요청 패턴 감지 (주소창 + Agent) | B | ~30줄 |
| 8 | navigate 도메인 변경 시 위험 승격 | G | ~20줄 |

### P2 (중기, 3-5일)
| # | 작업 | 범주 | 예상 규모 |
|---|------|------|----------|
| 9 | 작업 요약 영속화 (Agent Loop 완료 시) | F | ~60줄 |
| 10 | 새 도구: fill_form, wait_for | D | ~150줄 |
| 11 | Agent 진행 상태 시각화 (반복 카운터, 도구 이름) | H | ~80줄 |
| 12 | 개발자 모드: SENSITIVE 자동 승인 토글 | G | ~30줄 |

### P3 (장기, 설계 필요)
| # | 작업 | 범주 | 예상 규모 |
|---|------|------|----------|
| 13 | 선택자 안정화 (data-* 우선, 텍스트 폴백) | D | 설계 필요 |
| 14 | 세션 간 컨텍스트 전달 아키텍처 | F | 설계 필요 |
| 15 | 토큰 예산 관리 전체 (S5: sliding window) | C+E | ~200줄 |
| 16 | 쿠키 상태 메타 정보 전달 (값 마스킹) | F+G | 설계 필요 |

---

## 4. 아키텍처 제약 사항

- **SSOT**: 모드 상태는 `AiChatViewModel._agentMode` → `LlmSettings.agentModeDefault`로 단일 진실
- **단방향**: View → ViewModel → Model (Compose state flow)
- **보안 원칙**: Allow Always 금지, 쿠키 값 마스킹, URL 화이트리스트
- **로컬 완결**: 서버 없이 브라우저 + LLM API만으로 동작
- **프로바이더 독립**: 4개 프로바이더(Anthropic, OpenAI, Ollama, Gemini) 공통 인터페이스

---

## 5. 변경된 파일 요약

### 완료 (이번 패치)
| 파일 | 변경 내용 |
|------|----------|
| `LlmSettings.kt` | `agentModeDefault` 프로퍼티 추가 (기본값 `true`), `clear()` 시 보존 |
| `AiChatViewModel.kt` | `_agentMode` 초기값을 `LlmSettings.agentModeDefault`로 변경, `setAgentMode()`에서 영속화 |

### 예정 (P0 나머지)
| 파일 | 변경 내용 |
|------|----------|
| `SystemPromptBuilder.kt` | URL 히스토리 섹션 추가, 도구 사용 가이드라인 추가 |
| `AgentLoop.kt` | 2회차 이후 Quick 스크린샷 복원 |
| `AiChatViewModel.kt` | URL 히스토리를 SystemPromptBuilder에 전달 |