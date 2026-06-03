# DevCompanion 종합 개선 계획

## 이슈 1: 터치 정지 (앱 전체가 터치 불응)

### 현상
- 앱이 터치에 반응하지 않음
- Session log에 아무것도 남지 않음 → Compose 레벨 이벤트 자체가 소실

### 원인 분석
1. **WebView 렌더 프로세스 정지**: `onRenderProcessGone`에서 `view.destroy()` 후 `pendingAction=Reload`만 설정 → factory 재호출 안 됨 → 깨진 WebView에 reload만 시도
2. **Compose AndroidView key 미변경**: factory는 key가 변경될 때만 재호출됨. 현재는 key 없이 사용
3. **`view.destroy()` 직접 호출 위험**: Compose가 관리하는 View를 직접 destroy하면 메모리 누수 가능

### 대응 (크리틱 반영)
- **A1**: `onRenderProcessGone`에서 `view.destroy()` 제거 → 대신 `webViewCrashed = true` + `webViewKey += 1`
- **A2**: `AndroidView`에 `key = webViewKey` 추가 → key 변경 시 factory 재호출 보장
- **A3**: 정지 시 `Box` 겹치기로 에러 오버레이 표시 ("WebView 정지됨" + 재시도 버튼)
- **A4**: 재시도 버튼 → `webViewKey += 1` + `webViewCrashed = false`
- **A5** (선택): `Choreographer` 프레임 콜백으로 메인 스레드 응답성 모니터링 → SessionLog에 경고

## 이슈 2: NetworkTab 표가 좁아서 안 보임

### 현상
- weight 기반 5컬럼이 좁은 화면에서 cell 잘림
- Status code, headers 등 상세 접근이 row 탭으로만 가능

### 크리틱 반영
- **가로 스크롤 테이블 단독은 모바일 비적합** (제스처 충돌, stickyHeader 동기화 문제)
- **expand 패턴이 모바일 UX에 더 적합**
- 가로 스크롤은 보조 기능으로만

### 채택: **expand-first + 가로 스크롤 선택 토글**
- **기본**: 세로 리스트 + row 탭 → expand (즉시 전환, 애니메이션 없음 → 점프 방지)
- **expand 내용**: General 요약 + Headers 접힘 + "전체 복사" 버튼
- **가로 스크롤 모드**: 설정 토글로 전환 가능 (디버깅/비교 목적)
- 공유 `ScrollState`로 헤더-바디 동기화

### 구현
1. **weight + `widthIn(min=)` 하이브리드**: 기존 weight 비율 유지 + 최소 너비 보장
2. **expand 패턴**: `expandedEntryId` 토글 → 즉시 전환 (AnimatedVisibility 대신 → 점프 방지)
3. **expand 내부**: General 정보 + Request/Response headers 요약 + 복사 버튼
4. **stickyHeader**: LazyColumn `stickyHeader`로 헤더 고정
5. **가로 스크롤**: TopAppBar에 아이콘 토글 → `horizontalScroll` 모드 전환

## 이슈 3: SessionLog 데이터 확보 + LLM 토론 사이클

### 현상
- 어느 순간부터 SessionLog export 데이터가 안 쌓임
- 브라우저 단위 문제(네트워크 에러, 크래시, 렌더 정지 등)를 LLM이 분석하여 해결 방안을 제시하는 사이클 필요

### 원인 분석
- `SessionLog.flush()`가 `onPause`/`onStop`에서만 호출 → 앱 강제 종료 시 데이터 손실
- `MAX_BUFFER_SIZE = 5000`이지만 디스크 플러시가 지연되면 메모리에서 손실
- WebView 크래시/ANR 발생 시 `onStop`이 호출되지 않을 수 있음

### 대응
1. **주기적 자동 플러시**: 타이머(30초)로 `flush()` 호출 → 강제 종료 시에도 최신 30초 데이터 보존
2. **WebView 생명주기 이벤트 로깅**: `onRenderProcessGone`, `onPageStarted`, `onPageFinished`, `onReceivedError` → SessionLog에 기록
3. **ANR/정지 감지 이벤트**: 메인 스레드 블록 시 `SessionLog.log(EventType.ERROR, ...)` 기록
4. **LLM 토론 사이클 인터페이스**: SessionLog 데이터를 LLM에 전달하는 파이프라인
   - export → JSONL → LLM context에 주입
   - LLM이 패턴 분석 → 원인 가설 → 해결 방안 제시
   - 결과를 다시 SessionLog에 기록 (EventType.AGENT_ANALYSIS 등)
5. **SessionLog 데이터 풍부화**:
   - `EventType.WEBVIEW_CRASH` 추가
   - `EventType.WEBVIEW_RECOVER` 추가
   - `EventType.NETWORK_ERROR` (기존 `trackHttpError`과 연동)
   - `EventType.ANR_DETECTED` 추가

## 작업 순서 (크리틱 반영: 버그 수정 우선)

### Phase 1: 버그 수정 (최우선)
1. **BrowserTab WebView 재생성** — `webViewKey` + `onRenderProcessGone` 수정
2. **BrowserTab 에러 오버레이** — 정지 시각 피드백 + 재시도 버튼
3. **SessionLog 자동 플러시** — 30초 타이머 + 생명주기 이벤트 로깅

### Phase 2: NetworkTab 리디자인
4. **expand 패턴** — weight+widthIn 하이브리드 + 즉시 전환 expand
5. **가로 스크롤 토글** (선택) — 설정 아이콘으로 전환

### Phase 3: LLM 토론 사이클
6. **EventType 추가** — WEBVIEW_CRASH, WEBVIEW_RECOVER, NETWORK_ERROR, ANR_DETECTED
7. **LLM 분석 인터페이스** — export JSONL → context 주입 파이프라인

### Phase 4: CI
8. **빌드 + APK 제공**

## 파일 변경 예상
- `BrowserTab.kt` — WebView 재생성 + 에러 오버레이
- `NetworkTab.kt` — expand 패턴 + 가로 스크롤 토글
- `SessionLog.kt` — 자동 플러시 + EventType 추가
- `WebViewDebugger.kt` — trackHttpError → SessionLog 연동

## 크리틱 반영 사항
1. ✅ 버그 수정(WebView 재생성)을 기능 개발(NetworkTab)보다 선행
2. ✅ `view.destroy()` 직접 호출 제거 → key 변경으로 대체
3. ✅ `AnimatedVisibility` 대신 즉시 전환 (스크롤 점프 방지)
4. ✅ 가로 스크롤은 보조 토글로, 기본은 expand-first
5. ✅ weight + widthIn 하이브리드 (순수 widthIn 전환의 부작용 회피)
6. ✅ 공유 ScrollState로 헤더-바디 동기화 (가로 스크롤 모드 시)
7. ✅ SessionLog 데이터 확보 문제 해결 추가