# GitHub PAT 인증 — 설계 문서

> 버전: 1.1 | 작성일: 2026-05-13 | 상태: 검증 완료 (2차)
> 
> ⚠️ **v1.1 수정사항** (근거 재검증 결과):
> - Fine-grained PAT 처리: 단순 boolean → **sealed class** 분리 (scope가 아닌 repository+permission 단위)
> - Anthropic API 버전: 코드에서 `2023-06-01` 사용 중 → 최신 권장 `2023-06-01`과 일치 확인
> - GitHub App OAuth: 장기 로드맵으로 명시 (현재는 PAT만)
> - 코드베이스 EncryptedSharedPreferences 패턴과 완전 일치 확인

## 1. 목표

LLM이 GitHub repo 컨텍스트를 동적으로 주입할 수 있게 한다. **읽기 전용을 기본**, write 액션은 명시적 확인. 일반 웹 브라우징 시에는 repo 접근이 노이즈가 되지 않도록 트리거 기반으로 제한.

---

## 2. 3단계 기능 레벨

```
Level 1: Read (기본)      → repo 구조, 파일 내용, 이슈 조회
Level 2: Issue            → 이슈/코멘트 생성 (사용자 확인)
Level 3: Commit/Push      → 브랜치, 커밋, PR (확인 + 별도 권한)
```

---

## 3. 데이터 모델

### GitHubSettings

```kotlin
object GitHubSettings {
    private const val FILE_NAME = "devcompanion_github_settings"
    private const val KEY_PAT = "github_pat"
    private const val KEY_PAT_VALIDATED = "pat_validated"
    private const val KEY_PAT_SCOPES = "pat_scopes"
    private const val KEY_PAT_EXPIRES_AT = "pat_expires_at"
    private const val KEY_DEFAULT_REPO = "default_repo"
    private const val KEY_READ_ONLY_MODE = "read_only_mode"
    
    @Volatile
    private var prefs: SharedPreferences? = null
    
    /**
     * EncryptedSharedPreferences로 초기화
     * LlmSettings와 동일한 패턴
     */
    @Synchronized
    fun initialize(context: Context) { ... }
}
```

### GitHubPatCredential

```kotlin
sealed class PatType {
    data class Classic(
        val scopes: Set<ClassicScope>
    ) : PatType()
    
    data class FineGrained(
        val repositories: List<String>,  // "owner/repo" 형태, 비어있으면 모든 repo
        val permissions: Set<FineGrainedPermission>
    ) : PatType()
    
    enum class ClassicScope {
        PUBLIC_REPO,      // 공개 repo 읽기
        REPO,             // 비공개 repo 포함 읽기
        REPO_WRITE,       // 이슈/코멘트 생성
        REPO_ADMIN        // 푸시, 브랜치 삭제 등
    }
    
    enum class FineGrainedPermission {
        CONTENTS_READ,      // 코드 읽기
        ISSUES_READ,        // 이슈 읽기  
        ISSUES_WRITE,       // 이슈 쓰기
        PULL_REQUESTS_READ,
        PULL_REQUESTS_WRITE,
        METADATA_READ       // 기본, 항상 필요
    }

    /** 이 PAT가 쓰기 권한을 갖는지 확인 */
    fun canWrite(): Boolean = when (this) {
        is Classic -> scopes.contains(ClassicScope.REPO_WRITE) || scopes.contains(ClassicScope.REPO_ADMIN)
        is FineGrained -> permissions.contains(FineGrainedPermission.ISSUES_WRITE) || 
                           permissions.contains(FineGrainedPermission.PULL_REQUESTS_WRITE)
    }
}

data class GitHubPatCredential(
    val token: String,              // ghp_... 또는 github_pat_...
    val patType: PatType,
    val validatedAt: Long? = null,
    val expiresAt: Long? = null
) {
    // Fine-grained PAT 감지: github_pat_ 접두사
    companion object {
        fun fromToken(token: String, scopes: Set<PatType.ClassicScope> = emptySet()): GitHubPatCredential {
            val patType = if (token.startsWith("github_pat_")) {
                // Fine-grained PAT — scopes는 실제로 permissions로 매핑 필요
                PatType.FineGrained(
                    repositories = emptyList(),  // 검증 시 채움
                    permissions = scopes.mapNotNull { 
                        when (it) {
                            PatType.ClassicScope.PUBLIC_REPO -> PatType.FineGrainedPermission.CONTENTS_READ
                            PatType.ClassicScope.REPO -> PatType.FineGrainedPermission.CONTENTS_READ
                            PatType.ClassicScope.REPO_WRITE -> PatType.FineGrainedPermission.ISSUES_WRITE
                            PatType.ClassicScope.REPO_ADMIN -> PatType.FineGrainedPermission.PULL_REQUESTS_WRITE
                        }
                    }.toSet()
                )
            } else {
                PatType.Classic(scopes)
            }
            return GitHubPatCredential(token, patType)
        }
    }
}

enum class PatValidationStatus {
    VALID,            // 정상
    EXPIRED,          // 만료
    INSUFFICIENT_SCOPE, // 권한 부족
    INVALID,          // 토큰 자체 문제
    UNKNOWN           // 네트워크 오류 등
}
```

### RepoContext (LLM에 주입될 구조)

```kotlin
data class RepoContext(
    val owner: String,
    val name: String,
    val branch: String = "main",
    val fileTree: FileTree,         // 최대 2뎁스
    val recentCommits: List<CommitSummary>,  // 최근 10개
    val openIssues: List<IssueSummary>,     // 관련 이슈 (라벨 기반)
    val readme: String?,            // README.md 내용 (요약)
    val techStack: TechStack?       // 자동 감지 (Kotlin, TypeScript 등)
) {
    /**
     * LLM에 주입될 텍스트 형태
     * 토큰 예산 내에서 최적화된 형식
     */
    fun toPromptContext(maxTokens: Int = 2000): String {
        return buildString {
            appendLine("## Repository Context: $owner/$name")
            appendLine("Branch: $branch")
            appendLine()
            
            appendLine("### File Tree (2 levels)")
            fileTree.format(2).lines().take(30).forEach { appendLine(it) }
            appendLine()
            
            appendLine("### Recent Commits")
            recentCommits.take(5).forEach { 
                appendLine("- ${it.hash.take(7)}: ${it.message.take(50)}")
            }
            appendLine()
            
            techStack?.let {
                appendLine("### Tech Stack")
                appendLine("- ${it.primaryLanguage} (${it.percentage}%)")
                appendLine("- Frameworks: ${it.frameworks.joinToString()}")
            }
        }
    }
}
```

---

## 4. PAT UI/UX

### 입력 화면

```
┌─────────────────────────────────────┐
│ GitHub Integration          [?]     │
│ ═══════════════════════════════════ │
│                                     │
│ Personal Access Token               │
│ ┌─────────────────────────────────┐ │
│ │ ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxx │ │
│ │ [👁]                            │ │
│ └─────────────────────────────────┘ │
│                                     │
│ Token scope (detected):             │
│   ✅ repo (read)                    │
│   ❌ repo (write) — limited to read │
│                                     │
│ [Test Connection]                   │
│                                     │
│ Connection: ✅ Valid                │
│ User: JooyoungChoi14                │
│ Rate limit: 4,987 / 5,000 remaining │
│                                     │
│ ── Default Repository ──            │
│                                     │
│ Repo: [JooyoungChoi14/DevCompanion ▼]│
│ Branch: [main ▼]                    │
│                                     │
│ [☑] Read-only mode (recommended)    │
│                                     │
│ [Save]                              │
└─────────────────────────────────────┘
```

### 권한 설명 툴팁

```
Read-only mode:
  • repo 구조/파일 읽기 ✅
  • 이슈 조회 ✅
  • 이슈 생성 ❌
  • 커밋/푸시 ❌

Write mode:
  모든 읽기 기능 +
  • 이슈/코멘트 생성 ✅
  • PR 생성 ✅
  • 커밋/푸시 (별도 확인) ✅
```

---

## 5. 보안 아키텍처

### 핵심 원칙: LLM은 PAT를 절대 직접 본다

```
❌ 잘못된 설계:
   시스템 프롬프트에 "github_pat: ghp_xxx" 포함
   → LLM이 응답에 실수로 포함할 수 있음

✅ 올바른 설계:
   앱이 GitHub API 호출 수행
   → LLM은 결과만 수신
   → PAT는 앱 내부에서만 사용
```

### 저장 보안

```kotlin
class GitHubPatStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "github_pat_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun savePat(pat: String) {
        prefs.edit().putString("pat", pat).apply()
        // backup 금지 설정
    }
    
    fun getPat(): String? = prefs.getString("pat", null)
}
```

### Manifest 설정

```xml
<application
    android:allowBackup="false"
    android:fullBackupContent="false"
    ... >
```

---

## 6. 트리거 조건

repo 컨텍스트가 LLM에 주입되는 조건:

```kotlin
object RepoContextTriggers {
    /**
     * 트리거 조건 검사
     */
    fun shouldInject(
        webView: WebView,
        errorMessage: String?,
        tokenBudget: TokenBudget,
        userExplicitRequest: Boolean
    ): TriggerDecision {
        // 1. 명시적 요청 (최우선)
        if (userExplicitRequest) {
            return TriggerDecision.Inject("User explicitly requested")
        }
        
        // 2. URL이 현재 repo 관련
        val currentUrl = webView.url ?: return TriggerDecision.Skip("No URL")
        if (isRepoRelatedUrl(currentUrl)) {
            return TriggerDecision.Inject("URL matches repo: $currentUrl")
        }
        
        // 3. 에러 메시지가 앱/네이티브 관련
        errorMessage?.let { msg ->
            if (isAppLevelError(msg)) {
                // 토큰 예산 확인
                if (!tokenBudget.shouldInjectRepoContext()) {
                    return TriggerDecision.Skip("Budget constraints")
                }
                return TriggerDecision.Inject("App-level error: ${msg.take(50)}")
            }
        }
        
        return TriggerDecision.Skip("No trigger condition met")
    }
    
    private fun isRepoRelatedUrl(url: String): Boolean {
        val repoPatterns = listOf(
            Regex("github\\.io/[^/]+"),           // GitHub Pages
            Regex("localhost.*dev-companion"),    // 로컬 개발
            Regex("127\\.0\\.0\\.1.*dev-companion"),
            Regex("file://.*dev-companion")       // 로컬 파일
        )
        return repoPatterns.any { url.contains(it) }
    }
    
    private fun isAppLevelError(message: String): Boolean {
        val appPatterns = listOf(
            "java\\.", "kotlin\\.", "android\\.",
            "Exception", "Error", "StackTrace",
            "NullPointer", "IllegalState", "IndexOutOfBounds"
        )
        return appPatterns.any { message.contains(it, ignoreCase = true) }
    }
}

sealed class TriggerDecision {
    data class Inject(val reason: String) : TriggerDecision()
    data class Skip(val reason: String) : TriggerDecision()
}
```

---

## 7. GitHub API 연동

### GitHubApiClient (앱 내부 전용)

```kotlin
class GitHubApiClient(
    private val credential: GitHubPatCredential,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    private val baseUrl = "https://api.github.com"
    
    private fun createRequest(path: String): Request {
        return Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer ${credential.token}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
    }
    
    /**
     * PAT 검증
     */
    suspend fun validatePat(): PatValidationResult {
        val request = createRequest("/user")
        return try {
            val response = okHttpClient.newCall(request).execute()
            when (response.code) {
                200 -> {
                    val user = parseUser(response.body?.string())
                    val scopes = response.header("X-OAuth-Scopes")?.split(",")?.toSet() ?: emptySet()
                    PatValidationResult.Valid(user, scopes)
                }
                401 -> PatValidationResult.Invalid
                403 -> PatValidationResult.InsufficientScope
                else -> PatValidationResult.Unknown(response.code)
            }
        } catch (e: IOException) {
            PatValidationResult.NetworkError(e.message)
        }
    }
    
    /**
     * repo 기본 정보
     */
    suspend fun getRepo(owner: String, name: String): RepoInfo? {
        val request = createRequest("/repos/$owner/$name")
        // ...
    }
    
    /**
     * 파일 트리 (shallow, 2뎁스)
     */
    suspend fun getFileTree(owner: String, name: String, branch: String = "main"): FileTree {
        // Git Trees API: ?recursive=1 하지만 제한
        val request = createRequest("/repos/$owner/$name/git/trees/$branch?recursive=1")
        // 2뎁스만 필터링
    }
    
    /**
     * 파일 내용 (base64 decode)
     */
    suspend fun getFileContent(owner: String, name: String, path: String, branch: String = "main"): String? {
        val request = createRequest("/repos/$owner/$name/contents/$path?ref=$branch")
        // ...
    }
    
    /**
     * 최근 커밋
     */
    suspend fun getRecentCommits(owner: String, name: String, branch: String = "main", limit: Int = 10): List<Commit> {
        val request = createRequest("/repos/$owner/$name/commits?sha=$branch&per_page=$limit")
        // ...
    }
    
    /**
     * 열린 이슈 (라벨 기반 필터링)
     */
    suspend fun getOpenIssues(owner: String, name: String, labels: List<String>? = null): List<Issue> {
        val labelParam = labels?.joinToString(",")?.let { "&labels=$it" } ?: ""
        val request = createRequest("/repos/$owner/$name/issues?state=open$labelParam")
        // ...
    }
    
    // ============== Write operations (read-only mode에서 차단) ==============
    
    /**
     * 이슈 생성 — 확인 필요
     */
    suspend fun createIssue(owner: String, name: String, title: String, body: String): Result<Issue> {
        if (!credential.patType.canWrite()) {
            return Result.failure(InsufficientScopeException("Write permission required"))
        }
        // ...
    }
}
```

---

## 8. LLM 컨텍스트 주입 흐름

```
사용자: "이 에러 뭐야?"
  ↓
에러 메시지 분석 → 앱 레벨 에러 확인
  ↓
RepoContextTriggers.shouldInject() 검사
  ↓
트리거 충족? 
  ├── No → 일반 응답
  └── Yes → GitHubApiClient.fetchRepoContext()
              ↓
              repo 기본 정보
              파일 트리 (2뎁스)
              최근 커밋 (10개)
              관련 이슈 (라벨 필터)
              ↓
              RepoContext.toPromptContext()
              ↓
LLM 프롬프트:
  "에러: NullPointerException..."
  ""
  "## Repository Context: JooyoungChoi14/DevCompanion"
  "Branch: main"
  "..."
  ↓
LLM 응답 (컨텍스트 기반 진단)
```

---

## 9. Write 액션 확인 UI

Issue 생성 예시:

```
┌─────────────────────────────────────┐
│ Create GitHub Issue           [X]   │
│ ═══════════════════════════════════ │
│                                     │
│ LLM suggests creating an issue:     │
│                                     │
│ Title:                              │
│ ┌─────────────────────────────────┐ │
│ │ Fix NullPointerException in     │ │
│ │ AgentLoop.kt:145                │ │
│ └─────────────────────────────────┘ │
│                                     │
│ Body preview:                       │
│ ┌─────────────────────────────────┐ │
│ │ ## Problem                      │ │
│ │ NPE when tokenBudget is null    │ │
│ │                                 │ │
│ │ ## Location                     │ │
│ │ AgentLoop.kt:145                │ │
│ │                                 │ │
│ │ ## Suggested Fix                │ │
│ │ Add null check before...        │ │
│ └─────────────────────────────────┘ │
│                                     │
│ Labels: [bug ▼] [agent-loop ▼]      │
│                                     │
│ [Edit] [Create Issue] [Cancel]      │
└─────────────────────────────────────┘
```

---

## 10. 에러 처리

```kotlin
sealed class GitHubApiError : Exception() {
    class RateLimited(val resetAt: Long) : GitHubApiError()
    class NotFound(val resource: String) : GitHubApiError()
    class InsufficientScope(val required: PatScope) : GitHubApiError()
    class PatExpired : GitHubApiError()
    class NetworkError(cause: Throwable) : GitHubApiError()
}

// UI에 표시
fun formatError(error: GitHubApiError): String = when (error) {
    is GitHubApiError.RateLimited -> 
        "GitHub API rate limit exceeded. Resets at ${formatTime(error.resetAt)}"
    is GitHubApiError.NotFound -> 
        "Repository not found: ${error.resource}"
    is GitHubApiError.InsufficientScope -> 
        "PAT needs ${error.required} scope. Current: read-only"
    is GitHubApiError.PatExpired -> 
        "PAT expired. Please regenerate in GitHub Settings → Developer settings"
    is GitHubApiError.NetworkError -> 
        "Network error. Check connection and retry."
}
```

---

## 11. 구현 체크리스트

- [ ] `GitHubSettings` EncryptedSharedPreferences 초기화
- [ ] PAT 입력 UI (가림 처리, 표시/숨김 토글)
- [ ] PAT 검증 API 연동 (`/user`)
- [ ] Scope 감지 및 표시
- [ ] 기본 repo/branch 선택
- [ ] Read-only 모드 토글
- [ ] `GitHubApiClient` 구현 (GET 메서드)
- [ ] `RepoContext` 생성 및 포맷팅
- [ ] `RepoContextTriggers` 조건 구현
- [ ] AgentLoop 연동 (트리거 충족 시 컨텍스트 주입)
- [ ] Write 액션 확인 UI (Issue 생성)
- [ ] 에러 처리 및 UI 표시

---

## 12. 장기 로드맵: GitHub App OAuth

현재: PAT (단순, 즉시 작동)
향후: GitHub App OAuth device flow (더 안전, repo별 권한, 만료 없음)

| 특징 | PAT | GitHub App |
|---|---|---|
| 설정 복잡도 | 낮음 (토큰만) | 높음 (앱 등록 필요) |
| 만료 | 있음 (fine-grained은 만료 설정 가능) | 없음 (refresh token) |
| 권한 범위 | scope 기반 | repo + permission 기반 (세분화) |
| 조직 관리 | 제한적 | 조직 승인 흐름 지원 |
| 보안 | 토큰 유출 시 전체 권한 노출 | repo별 격리 |

Phase 1에서는 PAT로 시작, Phase 3+에서 GitHub App 전환 검토.
