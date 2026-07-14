/**
 * DevCompanion Resource Monitor — WebExtension Background Script
 *
 * Intercepts network requests via webRequest API and accumulates resource entries.
 * Communicates with the host app via GeckoView WebExtension messaging
 * (runtime.webExtensionController.sendMessage → browser.runtime.onMessage).
 *
 * This replaces the previous Performance API polling approach which had two critical flaws:
 * 1. CORS restrictions: performance.getEntriesByType('resource') returns empty
 *    size/statusCode for cross-origin resources
 * 2. URL scheme bridge limit: devcompanion://eval-result?data=... has ~8KB limit,
 *    causing eval_timeout for resource-heavy pages
 */

/* ── State ─────────────────────────────────────────────── */

/** Current page resources, keyed by URL for deduplication */
const resources = new Map();

/** Resource entry limit to prevent memory exhaustion on extremely heavy pages */
const MAX_RESOURCES = 500;

/** Resource types that should never be evicted (core page resources) */
const PROTECTED_TYPES = new Set(['document', 'stylesheet']);

/** Current page URL (top-level navigation) */
let currentPageUrl = null;

/** MIME type → resource type mapping for SourcesTab display */
function mapType(initiatorType, contentType) {
  // Prefer MIME type when available (more accurate)
  if (contentType) {
    const ct = contentType.toLowerCase();
    if (ct.includes('text/html')) return 'document';
    if (ct.includes('javascript') || ct.includes('ecmascript')) return 'script';
    if (ct.includes('css')) return 'stylesheet';
    if (ct.includes('image/') || ct.includes('svg')) return 'image';
    if (ct.includes('font') || ct.includes('woff') || ct.includes('ttf') || ct.includes('otf')) return 'font';
    if (ct.includes('json') || ct.includes('xml')) return 'xhr';
  }
  // Fallback to initiator type
  switch (initiatorType) {
    case 'navigation':
    case 'document': return 'document';
    case 'script': return 'script';
    case 'link':
    case 'css': return 'stylesheet';
    case 'img': return 'image';
    case 'font': return 'font';
    case 'xmlhttprequest':
    case 'fetch': return 'xhr';
    default: return 'other';
  }
}

/* ── webRequest Listeners ──────────────────────────────── */

/**
 * onHeadersReceived: capture response headers (Content-Type, Content-Length, status code)
 * This is where we get MIME type and status code — not available in onBeforeRequest.
 * No CORS restriction: webRequest runs with extension permissions.
 */
browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    const headers = details.responseHeaders || [];
    let contentType = null;
    let contentLength = -1;

    for (const h of headers) {
      const name = h.name.toLowerCase();
      if (name === 'content-type') {
        contentType = h.value.split(';')[0].trim(); // Strip charset
      }
      if (name === 'content-length') {
        contentLength = parseInt(h.value, 10) || -1;
      }
    }

    const type = mapType(
      details.type === 'main_frame' ? 'navigation' : details.type,
      contentType
    );

    const entry = {
      url: details.url,
      type: type,
      mimeType: contentType,
      size: contentLength,
      statusCode: details.statusCode || -1
    };

    // Deduplicate: same URL gets updated with richer info
    resources.set(details.url, entry);

    // Enforce limit with priority-based eviction:
    // Protected types (document, stylesheet) are never evicted.
    // Among unprotected, evict the oldest entry first.
    if (resources.size > MAX_RESOURCES) {
      for (const [key, val] of resources) {
        if (PROTECTED_TYPES.has(val.type)) continue;
        resources.delete(key);
        if (resources.size <= MAX_RESOURCES) break;
      }
    }
  },
  { urls: ['<all_urls>'] },
  ['responseHeaders']
);

/**
 * onBeforeRedirect: remove old URL when a resource redirects.
 * The redirect target will be captured by onHeadersReceived.
 */
browser.webRequest.onBeforeRedirect.addListener(
  (details) => {
    resources.delete(details.url);
  },
  { urls: ['<all_urls>'] }
);

/* ── Navigation tracking ───────────────────────────────── */

/**
 * When main frame navigates, clear accumulated resources for the new page.
 * This prevents stale resources from the previous page mixing in.
 */
browser.webNavigation.onBeforeNavigate.addListener(
  (details) => {
    if (details.frameId === 0) {
      // Main frame navigation — reset resources
      resources.clear();
      currentPageUrl = details.url;
    }
  },
  { url: [{ schemes: ['http', 'https'] }] }
);

/* ── Message handler ───────────────────────────────────── */

/**
 * Handle messages from the host app (GeckoView messaging).
 *
 * Commands:
 * - "getResources": Return current page resources as JSON array
 * - "getResourceCount": Return just the count (lightweight check)
 *
 * Note: "clearResources" is NOT needed here because
 * webNavigation.onBeforeNavigate already clears resources on navigation.
 */
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.command === 'getResources') {
    const list = Array.from(resources.values());
    sendResponse({ resources: list });
    return true; // async response
  }

  if (message.command === 'getResourceCount') {
    sendResponse({ count: resources.size });
    return true;
  }

  return false;
});