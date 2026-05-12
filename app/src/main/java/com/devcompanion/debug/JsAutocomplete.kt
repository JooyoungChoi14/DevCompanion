package com.devcompanion.debug

/**
 * Global JS keywords and DOM API for autocomplete.
 * Organized by category for context-aware suggestions.
 */
object JsAutocomplete {
    // Global object names (things you can type directly or after `window.`)
    val globalObjects = setOf(
        "window", "document", "navigator", "location", "history",
        "console", "localStorage", "sessionStorage", "fetch", "XMLHttpRequest",
        "Promise", "JSON", "Math", "Date", "Array", "Object", "String",
        "Number", "Boolean", "RegExp", "Map", "Set", "WeakMap", "WeakSet",
        "Error", "TypeError", "RangeError", "SyntaxError",
        "parseInt", "parseFloat", "isNaN", "isFinite", "encodeURI", "decodeURI",
        "encodeURIComponent", "decodeURIComponent", "eval", "setTimeout", "setInterval",
        "clearTimeout", "clearInterval", "requestAnimationFrame", "cancelAnimationFrame",
        "alert", "confirm", "prompt", "atob", "btoa",
        "performance", "crypto", "indexedDB", "caches", "Worker", "SharedWorker",
        "Event", "CustomEvent", "MessageChannel", "URL", "URLSearchParams",
        "FormData", "Headers", "Request", "Response",
        "Element", "HTMLElement", "Node", "NodeList", "HTMLCollection",
    )

    // Common property groups by parent object
    private val windowProps = setOf(
        "location", "document", "navigator", "history", "screen",
        "localStorage", "sessionStorage", "innerWidth", "innerHeight",
        "outerWidth", "outerHeight", "scrollX", "scrollY", "pageXOffset", "pageYOffset",
        "devicePixelRatio", "orientation", "visualViewport",
        "getComputedStyle", "getSelection", "matchMedia",
        "addEventListener", "removeEventListener", "dispatchEvent",
        "setTimeout", "setInterval", "clearTimeout", "clearInterval",
        "requestAnimationFrame", "cancelAnimationFrame",
        "fetch", "open", "close", "stop", "print", "scrollTo", "scrollBy",
        "atob", "btoa", "crypto", "performance",
    )

    private val documentProps = setOf(
        "body", "head", "documentElement", "title", "domain", "URL",
        "cookie", "readyState", "referrer", "contentType",
        "getElementById", "getElementsByClassName", "getElementsByTagName",
        "querySelector", "querySelectorAll", "querySelectorAll",
        "createElement", "createTextNode", "createDocumentFragment",
        "createEvent", "createRange",
        "addEventListener", "removeEventListener",
        "write", "writeln", "open", "close",
        "hasFocus", "exitFullscreen", "fullscreenElement",
    )

    private val consoleProps = setOf(
        "log", "warn", "error", "info", "debug", "dir", "dirxml",
        "table", "trace", "group", "groupEnd", "time", "timeEnd",
        "count", "countReset", "assert", "clear", "profile", "profileEnd",
    )

    private val navigatorProps = setOf(
        "userAgent", "appName", "appVersion", "platform", "language",
        "languages", "onLine", "cookieEnabled", "connection",
        "geolocation", "mediaDevices", "serviceWorker",
        "getBattery", "vibrate", "sendBeacon",
    )

    private val locationProps = setOf(
        "href", "protocol", "host", "hostname", "port", "pathname",
        "search", "hash", "origin", "assign", "replace", "reload",
    )

    private val elementProps = setOf(
        "id", "className", "classList", "tagName", "nodeName", "nodeType",
        "innerHTML", "innerText", "textContent", "outerHTML",
        "style", "attributes", "dataset",
        "children", "childNodes", "firstChild", "lastChild", "nextSibling", "previousSibling",
        "parentElement", "parentNode",
        "getAttribute", "setAttribute", "removeAttribute", "hasAttribute",
        "querySelector", "querySelectorAll",
        "addEventListener", "removeEventListener",
        "appendChild", "removeChild", "replaceChild", "insertBefore",
        "cloneNode", "contains", "matches", "closest",
        "getBoundingClientRect", "getClientRects", "offsetWidth", "offsetHeight",
        "offsetTop", "offsetLeft", "scrollWidth", "scrollHeight",
        "scrollTop", "scrollLeft", "focus", "blur", "click",
        "remove", "insertAdjacentHTML", "insertAdjacentElement",
    )

    private val mathProps = setOf(
        "abs", "ceil", "floor", "round", "max", "min", "pow", "sqrt",
        "random", "PI", "E", "LN2", "LN10", "LOG2E", "LOG10E",
    )

    private val jsonProps = setOf(
        "parse", "stringify",
    )

    private val promiseProps = setOf(
        "then", "catch", "finally", "resolve", "reject", "all", "race", "allSettled", "any",
    )

    // Map from object name to its properties
    private val propertyMap = mapOf(
        "window" to windowProps,
        "document" to documentProps,
        "console" to consoleProps,
        "navigator" to navigatorProps,
        "location" to locationProps,
        "Element" to elementProps,
        "HTMLElement" to elementProps,
        "Math" to mathProps,
        "JSON" to jsonProps,
        "Promise" to promiseProps,
    )

    /**
     * Get autocomplete suggestions for the current input.
     * Returns a list of completion strings.
     * Supports simple chaining (e.g. document.querySelector, window.document).
     */
    fun getSuggestions(input: String): List<String> {
        if (input.isBlank()) return globalObjects.sorted()

        val lastDot = input.lastIndexOf('.')
        if (lastDot < 0) {
            // No dot — filter global objects
            return globalObjects.filter { it.startsWith(input, ignoreCase = true) }
                .sortedBy { it }
        }

        // Has a dot — find the object part and suggest its properties
        val objectPart = input.substring(0, lastDot)
        val partial = input.substring(lastDot + 1)

        // Check static property map: try full chain first, then first segment
        val staticProps = propertyMap[objectPart]
            ?: propertyMap[objectPart.substringBefore('.')]

        if (staticProps != null) {
            return if (partial.isBlank()) staticProps.sorted()
            else staticProps.filter { it.startsWith(partial, ignoreCase = true) }.sorted()
        }

        // Unknown object — no suggestions
        return emptyList()
    }

    /**
     * Build a JS expression to enumerate properties of an object for dynamic autocomplete.
     * Returns a string that when eval'd, gives a JSON array of property names.
     */
    fun buildPropertyQuery(expr: String): String {
        return "JSON.stringify(Object.getOwnPropertyNames($expr).filter(p=>typeof $expr[p]!=='function'||p.length<30).slice(0,100))"
    }
}