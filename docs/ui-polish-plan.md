# UI Polish Plan — v1.3.1

## 1. Settings 탭 텍스트 수정 ✅ 완료
- "Integrations" → "More" + `labelSmall` + `maxLines = 1`
- "Appearance", "AI"도 동일 스타일 적용 (예방)

## 2. StartPage 제거 + Home URL 설정

### 제거 대상
- `StartPage.kt` 전체 삭제
- `BookmarksStore.kt` + `Bookmark` 데이터 클래스 삭제
- `BrowserTab.kt`에서:
  - `showStartPage` / `startPageVisible` / `onStartPageVisibleChange` 파라미터·상태 제거
  - `bookmarksStore` / `bookmarks` 상태 제거
  - `navigateFromStartPage` 람다 제거
  - `if (showStartPage) { ModalBottomSheet { StartPage(...) } }` 블록 전체 제거
  - `import Bookmark, BookmarksStore, UrlHistoryStore` 중 미사용 항목 제거
  - Home 버튼: StartPage 열기 대신 설정된 홈 URL로 이동
- `ExperimentalMaterial3Api` 옵트인 (ModalBottomSheet 제거 후 필요 없으면 제거)

### 유지
- `UrlHistoryStore` — autocomplete 및 AiChatViewModel에서 사용
- `urlHistory` / `urlHistoryStore` — BrowserTab 자동완성 드롭다운 유지

### 새 기능: Settings > More > Home URL
- Settings "More" 탭에 Home URL 입력 필드 추가
- 기본값: `about:blank`
- 입력값은 `SettingsViewModel` / `DataStore`에 영속화
- BrowserTab의 Home 버튼 클릭 → 설정된 URL로 `BrowserAction.Navigate`
- 빈 값이면 `about:blank`로 폴백

### Home 버튼 동작
- Before: `showStartPage = true; onNavigateHome?.invoke()` (StartPage 모달 열기)
- After: `pendingAction = BrowserAction.Navigate(homeUrl)` (설정된 URL로 이동)
- `onNavigateHome` 파라미터는 제거 가능 (로직이 BrowserTab 내부로 이동)

### 위험 평가 (서브에이전트 리뷰 통과)
- 기존 북마크 데이터: SharedPreferences에 orphaned되나 무해
- urlHistoryStore: BrowserTab autocomplete + AiChatViewModel에서 계속 사용
- Home 버튼 no-op 위험: Settings에 Home URL 기본값 `about:blank` 설정으로 해결