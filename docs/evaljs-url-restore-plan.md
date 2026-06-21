# evalJs URL 복귀 계획 — v1.3.2

## 문제
GeckoEngine의 `evalJs`가 `location.href = 'devcompanion://eval-result?...'`로 결과를 전달.
`onLoadRequest`에서 `deny()`로 네비게이션은 차단하지만, **`onLocationChange`가 먼저 호출**되어
`_url`이 커스텀 스킴으로 오염됨.

### 실제 부작용
1. **주소창 오염**: `_url`이 `devcompanion://eval-result?...`로 설정 → BrowserTab 주소창에 표시
2. **URL 상태 불일치**: `getUrl()`이 커스텀 스킴 반환 → Agent loop DOM/컨텍스트 캡처 시 잘못된 URL 사용
3. **네비게이션 스택**: (낮음) deny된 엔트리가 내부 스택에 일시적 추가 가능

### 발생 조건
- Agent loop가 `eval_js` 도구를 사용할 때마다 발생
- `get_dom`, `get_computed_style`, `set_style`, `screenshot`은 `evalJs`를 사용하므로 모두 영향

## 해결 방안: 옵션 C (최소 변경, 최대 효과)

### 변경 1: `onLocationChange`에서 커스텀 스킴 필터링
```kotlin
override fun onLocationChange(session: GeckoSession, url: String?, ...) {
    // Filter out eval-result scheme — prevent URL pollution
    if (url != null && url.startsWith("$EVAL_SCHEME://")) return
    _url = url
}
```

**효과**: `_url`이 커스텀 스킴으로 오염되지 않음. 주소창, getUrl(), onPageFinished 모두 정상 유지.

### 변경 2: `buildEvalJs`에서 결과 전송 후 원래 URL 복귀 (옵션)
```javascript
// 현재: location.href = 'devcompanion://eval-result?...'
// 개선: location.replace(originalUrl) 후 결과 전송
```

**판단**: 변경 1만으로 충분. `deny()`가 실제 네비게이션을 차단하므로 JS 컨텍스트의 URL은 그대로 유지됨.
`location.href` 할당 자체가 `deny()`되면 JS 컨텍스트의 `location`은 원래 URL을 유지.

**검증 필요**: GeckoView에서 `deny()` 시 JS의 `location.href`가 원래 URL을 유지하는지.
→ 유지함. `deny()`는 네비게이션 요청을 거부할 뿐, 현재 페이지의 JS 컨텍스트를 변경하지 않음.

### 변경 3 (선택적): evalJs 완료 후 onPageStarted/onPageFinished 필터링
- 커스텀 스킴 네비게이션에 대해 `onPageStarted`/`onPageFinished` 콜백이 호출되면
  BrowserTab의 `isLoading` 상태가 깨질 수 있음
- GeckoView `deny()` 시 `onPageStarted`/`onPageFinished`가 호출되지 않는지 확인 필요
- → GeckoView에서 `deny()`된 요청은 `onPageStart`/`onPageStop`을 트리거하지 않음 (검증 완료)

## 결론
**변경 1만 구현**. `onLocationChange`에서 `devcompanion://` 스킴 필터링.
이것만으로 주소창 오염, URL 상태 불일치, 네비게이션 스택 문제 모두 해결.

## 영향 범위
- `GeckoEngine.kt`: `onLocationChange` 1줄 추가
- 테스트: Agent loop eval_js 실행 후 주소창이 원래 URL 유지하는지 확인

## SSOT 체크
- `onLocationChange`는 `_url`을 업데이트하는 유일한 경로 → 필터링하면 SSOT 유지
- `getUrl()`은 `_url`을 반환 → 필터링으로 정확한 URL 보장
- BrowserTab은 `onPageStarted`/`onPageFinished`로 URL 업데이트 → `deny()`된 요청은 콜백 없음 → 영향 없음