# Architecture Review: WebView → BrowserEngine Abstraction Migration

**Reviewer:** Cold senior architect  
**Date:** 2026-06-09  
**Scope:** BrowserEngine interface, WebViewEngine, GeckoEngine, ToolExecutor, AgentLoop, PermissionGate, WebContextBuilder

---

## Critical Issues (must fix)

### C1. Thread-safety: mutable state accessed from multiple threads without synchronization

**GeckoEngine** has `_canGoBack`, `_canGoForward`, `_title`, `_url`, `_isLoading` — all plain `private var` fields updated from Gecko delegate callbacks (which run on the Gecko thread, not the UI thread) and read from coroutines on `Dispatchers.Main` or `Dispatchers.Default`. No `@Volatile`, no synchronization, no `StateFlow`. This is a data race.

**WebViewEngine** has `_isLoading` updated in `WebViewClient.onPageStarted/onPageFinished` (UI thread) but read from `isLoading` property on any coroutine context. Less severe because WebViewClient callbacks are main-thread-affined, but still risky if `isLoading` is read from a non-main dispatcher.

**Impact:** Stale reads, lost updates, crashes on Gecko flavor.  
**Fix:** Convert to `MutableStateFlow<Boolean>` / `MutableStateFlow<String?>` or at minimum `@Volatile`.

### C2. `evalJs` in GeckoEngine: CompletableDeferred completed on Gecko thread, not main thread

```kotlin
val result: GeckoResult<String> = session.evaluateJavaScript(js)
result.then({ value ->
    deferred.complete(value ?: "")  // <-- which thread?
    GeckoResult<Void>()
}, ...)
```

`GeckoResult.then()` callback runs on the Gecko runtime thread. `CompletableDeferred.complete()` is thread-safe, so this won't crash — but the downstream consumer may resume on the Gecko thread, which is neither the UI thread nor a coroutine dispatcher. Any UI-touching code after `await()` will crash or produce undefined behavior.

**Fix:** Wrap `deferred.complete()` inside `withContext(Dispatchers.Main)` or use `GeckoResult.then` on a specific `Looper` via `result.then(callback, handler)`.

### C3. `injectedStyleIds` in WebContextBuilder is a global mutable `Set` with zero synchronization

```kotlin
private val injectedStyleIds = mutableSetOf<String>()
```

This `object` singleton's mutable set is modified from `engine.view.post { ... }` callbacks (UI thread) and read from `isInjected()` (any thread). Classic race condition.

**Fix:** Replace with `ConcurrentHashMap.newKeySet()` or a `MutableStateFlow<Set<String>>`.

### C4. GeckoEngine.destroy() is a no-op — session leaks

```kotlin
override fun destroy() {
    // Don't close the session — Compose manages the view lifecycle
    // Session cleanup happens when the view is detached
}
```

The comment assumes Compose will clean up, but `GeckoSession` holds native resources. If the `GeckoView` is removed from the composition without a proper detach lifecycle (e.g., Activity recreation, rapid tab switching), the session and its native Gecko runtime objects leak. The "Compose manages the view lifecycle" assumption is fragile and undocumented.

**Fix:** At minimum, `session.close()` in `destroy()`. If the session is shared or reused, make that explicit via a lifecycle ownership model (e.g., `closeOnDestroy: Boolean` parameter).

### C5. WebViewEngine.destroy() doesn't destroy the WebView

```kotlin
override fun destroy() {
    debugger.detachWebView()
}
```

`WebView.destroy()` is never called. The WebView retains its JavaScript engines, DOM, and native renderer after `BrowserEngine.destroy()`. This is a memory leak on the free flavor every time a tab is closed.

**Fix:** Call `webView.destroy()` in `destroy()`. If Compose or the caller still holds a reference to `view`, document that `destroy()` makes the view unusable and callers must remove it from the composition first.

### C6. `browserCallbacks` in both engines is nullable and set-after-use

Both `WebViewEngine` and `GeckoEngine` hold `browserCallbacks` as `private var ... ? = null`. `installClients()` / `setupDelegates()` reads `browserCallbacks?.onPageStarted(...)` but `setCallbacks()` might be called after `setup()`, or between setup and the first page load. This is a temporal coupling problem with no enforcement.

Worse: `setCallbacks()` has no thread-safety guarantee. If called from a non-UI thread while a callback is firing, it's a race.

**Fix:** Make `setCallbacks()` part of the `setup()` signature (required parameter), or at minimum use `@Volatile` and document the call-order contract. Better: accept callbacks in the constructor.

---

## Concerns (should fix)

### W1. `evalJs` timeout in WebViewEngine doesn't cancel the pending JS evaluation

```kotlin
kotlinx.coroutines.withTimeout(timeoutMs) {
    val deferred = CompletableDeferred<String>()
    webView.post {
        webView.evaluateJavascript(js) { result ->
            deferred.complete(result ?: "")
        }
    }
    deferred.await()
}
```

When the timeout fires, the coroutine cancels and `withTimeout` throws, but `webView.evaluateJavascript(js, callback)` has already been posted to the main thread queue. The JS *will* execute eventually, and the callback *will* fire — but `deferred` is already cancelled. The `complete()` call on a cancelled `CompletableDeferred` is a no-op, which is fine, but the side effects of the JS code still happen. For destructive JS (e.g., `document.body.remove()`), the timeout doesn't actually prevent execution.

**Fix:** Document this limitation. For truly dangerous operations, the permission gate should be the guard, not the timeout.

### W2. `escapeJsString` is duplicated in ToolExecutor and PermissionGate

Two identical implementations of `escapeJsString`. This is an SSOT violation — any fix to one must be manually replicated to the other.

**Fix:** Extract to a shared utility (e.g., `com.devcompanion.engine.JsUtils.escapeJsString`).

### W3. WebContextBuilder.injectCss / revertCss are fire-and-forget with no error propagation

```kotlin
engine.view.post {
    engine.evaluateJavascript(js) { result ->
        if (result == "true") {
            injectedStyleIds.add(styleId)
        }
    }
}
return true  // <-- returns true BEFORE the JS actually executes
```

The function returns `true` unconditionally, even if the JS hasn't executed yet or returns `false`. Callers have no way to know if injection actually succeeded. This is especially problematic for `isInjected(styleId)` — it might return `false` for a style that *is* injected but whose callback hasn't fired yet.

**Fix:** Make `injectCss` / `revertCss` suspend functions that await the JS result. Or at minimum, document the async nature and remove the boolean return.

### W4. CSS injection in WebContextBuilder is an XSS vector

```kotlin
style.textContent = '$escapedCss';
```

The escaping only handles `\`, `\n`, `\r`, `'`, and backticks. It doesn't handle `</style>` (which breaks out of the style element) or Unicode escapes that decode to quote characters. A malicious or accidental `css` string containing `</style><script>...</script>` would inject arbitrary JS.

**Fix:** Also escape `</style>` → `<\/style>` and `<` → `\\x3c` within the CSS context. Or use `textContent` assignment from a properly escaped JS string via a safer escaping pipeline.

### W5. GeckoEngine.scrollX/scrollY return the View's scroll, not the page's

`geckoView.scrollX` and `geckoView.scrollY` return the *Android View's* scroll position, not the *web page's* scroll position. In GeckoView, the page scrolls internally within the compositor — the `GeckoView` widget itself rarely scrolls. These will almost always return 0.

**Fix:** Use a JS-based scroll position query (`window.scrollX`, `window.scrollY`) via `evaluateJavascript`, or document that these are unreliable for GeckoEngine and return -1 (like `contentHeight()`).

### W6. `contentHeight()` returns -1 for Gecko — consumers may not handle this

`BrowserEngine.contentHeight()` returns `-1` for GeckoEngine. The interface doc says "Returns -1 if unknown (GeckoView)" but no consumer code is shown checking for -1. If any caller does `if (engine.contentHeight() > threshold)` or uses it in arithmetic, -1 silently passes comparisons or causes wrong behavior.

**Fix:** Make the return type `Int?` with `null` meaning unknown, or audit all call sites and add guards.

### W7. `underlyingWebView` / `underlyingSession` / `underlyingGeckoView` / `underlyingDebugger` are abstraction leaks

These public properties expose implementation details through the abstraction layer. Any code that does `val webView = (engine as WebViewEngine).underlyingWebView` has bypassed the abstraction entirely. This defeats the purpose of the interface and makes it impossible to swap implementations without auditing all downcasts.

**Fix:** If flavor-specific access is genuinely needed, use a sealed interface / visitor pattern, or move flavor-specific operations into the `BrowserEngine` interface with default no-ops. At minimum, document that these are for internal use only and annotate with `@RestrictTo(RestrictTo.Scope.LIBRARY)`.

### W8. AgentLoop's `semanticErrorCounts` and loop detection state are plain mutable maps/vars on a class that's used from multiple coroutines

`AgentLoop` is instantiated once but `runAgentLoopBody` runs on `Dispatchers.Default` (via the launched coroutine in `start()`). The mutable state (`semanticErrorCounts`, `lastActionSignature`, `sameActionCount`, `lastRecallIndex`, `recallRepeatCount`) is read and written from this coroutine. If `start()` is called concurrently (even though `currentJob?.cancel()` is called first), there's a race between the cancelling coroutine and the new one.

**Fix:** Ensure `start()` is not callable concurrently, or protect state with a `Mutex`. The `currentJob?.cancel()` + immediate `launch` pattern has a race window — the old coroutine might still be in `finally` when the new one starts reading mutable state.

### W9. PermissionGate.checkTypeRisk does a synchronous-feeling evalJs that's actually async with a timeout

`engine.evalJs(js)` has a default 5-second timeout. If the WebView is unresponsive, the permission gate will block for 5 seconds *before* showing the confirmation dialog. This creates a perceptible UI freeze on the "Checking permission" step.

**Fix:** Use a shorter timeout (500ms?) for the DOM inspection query, and default to SENSITIVE on timeout (which already happens via the catch block, but the latency is the problem).

---

## Minor (nice to have)

### M1. `BrowserEngine` interface mixes sync and async APIs inconsistently

`getUrl()` is sync, `evalJs()` is suspend, `evaluateJavascript()` is callback-based, `screenshot()` is suspend, `destroy()` is sync. Pick a model and stick to it. The callback-based `evaluateJavascript` is especially awkward in a coroutine world — it should be internal to the implementations.

### M2. `currentUrl()` and `currentTitle()` are redundant aliases

```kotlin
fun currentUrl(): String? = getUrl()
fun currentTitle(): String? = getTitle()
```

These add no value. Pick one naming convention and use it consistently. The Kotlin convention is `url`/`title` as properties anyway.

### M3. WebViewEngine installs JS injections on every `onPageFinished` without checking if they're already applied

The `InjectionConfig.AUTOFILL_INJECTION`, `VH_FIX_INJECTION`, etc. are injected on every page load. If the page doesn't navigate away (e.g., SPA route change), these might be injected multiple times. The `MutationObserver`-based heartbeat in particular has caused infinite loops before (per the BrowserEngine doc comment). Double-injecting observers is a known freeze risk.

**Fix:** Guard with a sentinel: `if (!window.__dcVhFix) { window.__dcVhFix = true; ... }`.

### M4. `screenshotBase64()` has duplicated code between WebViewEngine and GeckoEngine

The base64 encoding logic is identical. This should be a default implementation on the interface or an extension function on `BrowserEngine`.

### M5. `WebViewEngine.configureDefaults` hardcodes user agent string

```kotlin
webView.settings.userAgentString =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 ..."
```

This will be wrong on any non-Pixel-8 device running any non-Android-14 version. It also breaks sites that adapt content based on UA. Consider making this configurable or using a modified version of the system default.

### M6. No logging in GeckoEngine

WebViewEngine has extensive `SessionLog` calls. GeckoEngine has zero. When something goes wrong on the Gecko flavor, there's nothing to diagnose from.

### M7. `getUrl()` / `getTitle()` in WebViewEngine delegate directly to WebView properties

`webView.url` and `webView.title` must be called on the UI thread. If `getUrl()` is called from a non-UI coroutine (which it is — `WebContextBuilder.buildContext` calls it without dispatcher context), this can throw `CalledFromWrongThreadException`.

### M8. `AgentLoop.start()` creates a new `CoroutineScope(Dispatchers.Default)` every call

This scope is never cancelled explicitly (only the `Job` is). The scope itself leaks. Use a `CoroutineScope` tied to the `AgentLoop` lifecycle or a `SupervisorJob`.

### M9. ToolExecutor's `executeGetConsoleLogs` uses string interpolation in JS

```kotlin
if ('$level' !== 'all') filtered = logs.filter(function(e){ return e.level === '$level'; });
```

The `$limit` is also interpolated directly: `filtered.slice(-$limit)`. While these come from trusted agent tool calls, they're still string-interpolated into JS without escaping. A crafted `level` value could break out of the string literal.

**Fix:** Use `escapeJsString` for these interpolated values.

---

## Verdict

The abstraction is structurally sound — the `BrowserEngine` interface captures the right operations, the flavor split is clean at the source-set level, and the callback model is reasonable. But the implementation has **real, ship-blocking concurrency bugs**. The Gecko flavor in particular is dangerously undertested: mutable state with no synchronization, callbacks arriving on the Gecko thread, and scroll/viewport methods that return wrong values.

The `WebContextBuilder` CSS injection has an XSS hole, and the global mutable `injectedStyleIds` set is a race waiting to happen.

The abstraction leaks (`underlyingWebView`, `underlyingSession`) will proliferate if not gated now.

**Recommendation: Do not ship the Gecko flavor until C1–C6 are resolved.** The free flavor is less broken (WebView's main-thread affinity papers over many sins), but C5 (WebView leak on destroy) and C7 (non-UI-thread WebView access) should be fixed before any production use.

The migration is 60% done. The interface is right. The implementations need hardening.