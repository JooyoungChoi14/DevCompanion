/**
 * DevCompanion Resource Monitor — WebExtension Background Script
 *
 * Intercepts network requests via webRequest API and accumulates resource entries.
 * Communicates with the host app via GeckoView port-based messaging.
 *
 * Port-based messaging flow:
 * 1. This extension calls browser.runtime.connect() to establish a port
 * 2. The host app receives the port via WebExtension.MessageDelegate.onConnect()
 * 3. The host app sends commands via port.postMessage()
 * 4. This extension responds via port.postMessage()
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

/** Port connection to the host app */
let hostPort = null;

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

/* ── Host Port Connection ───────────────────────────────── */

/**
 * Connect to the native app when the extension starts.
 * The host app (GeckoView) will receive this via MessageDelegate.onConnect().
 *
 * Uses browser.runtime.connectNative() for port-based communication with the app.
 * The nativeAppId must match what the app used in setMessageDelegate().
 */
function connectToHost() {
  try {
    // browser.runtime.connectNative() creates a port that the host app receives
    // via WebExtension.MessageDelegate.onConnect()
    hostPort = browser.runtime.connectNative('devcompanion');

    hostPort.onMessage.addListener((message) => {
      // Handle commands from the host app
      handleHostCommand(message);
    });

    hostPort.onDisconnect.addListener(() => {
      console.log('[ResourceMonitor] Host port disconnected');
      hostPort = null;
      // Attempt reconnect after a delay
      setTimeout(connectToHost, 2000);
    });

    console.log('[ResourceMonitor] Connected to host app');
  } catch (e) {
    console.error('[ResourceMonitor] Failed to connect to host:', e);
    // Retry after delay
    setTimeout(connectToHost, 3000);
  }
}

/**
 * Handle commands from the host app sent via port.postMessage().
 */
function handleHostCommand(message) {
  if (!message || !message.command) return;

  switch (message.command) {
    case 'getResources':
      const list = Array.from(resources.values());
      hostPort.postMessage({ resources: list });
      break;

    case 'getResourceCount':
      hostPort.postMessage({ count: resources.size });
      break;

    default:
      console.warn('[ResourceMonitor] Unknown command:', message.command);
  }
}

/* ── Runtime message handler (fallback for sendMessage) ── */

/**
 * Handle runtime messages as a fallback communication channel.
 * This handles browser.runtime.onMessage from the host app if it
 * uses WebExtension.setMessageDelegate() + direct messaging.
 */
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message || !message.command) return false;

  switch (message.command) {
    case 'getResources':
      const list = Array.from(resources.values());
      sendResponse({ resources: list });
      return true;

    case 'getResourceCount':
      sendResponse({ count: resources.size });
      return true;

    default:
      return false;
  }
});

/* ── Initialize ─────────────────────────────────────────── */

// Connect to host app on startup
connectToHost();