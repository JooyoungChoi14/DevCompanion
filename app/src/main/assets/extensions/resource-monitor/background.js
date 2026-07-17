/**
 * DevCompanion Resource Monitor — WebExtension Background Script
 *
 * Intercepts network requests via webRequest API and accumulates resource entries.
 * Captures response bodies for text-based resources via filterResponseData.
 * Communicates with the host app via GeckoView port-based messaging.
 *
 * Port-based messaging flow:
 * 1. This extension calls browser.runtime.connect() to establish a port
 * 2. The host app receives the port via WebExtension.MessageDelegate.onConnect()
 * 3. The host app sends commands via port.postMessage()
 * 4. This extension responds via port.postMessage()
 *
 * Body capture flow:
 * 1. onHeadersReceived checks Content-Type to decide if body should be captured
 * 2. For text resources below size threshold, filterResponseData intercepts the stream
 * 3. Stream data is collected and stored in the resource map under 'content'
 * 4. getResources returns metadata + small content; getResourceContent returns content for a specific URL
 */

/* ── State ─────────────────────────────────────────────── */

/** Current page resources, keyed by URL for deduplication */
const resources = new Map();

/** Resource entry limit to prevent memory exhaustion on extremely heavy pages */
const MAX_RESOURCES = 500;

/** Maximum content size per resource (in bytes). Larger responses are truncated. */
const MAX_CONTENT_BYTES = 51200; // 50KB

/** Maximum total content bytes across all resources. Oldest content is nulled when exceeded. */
const MAX_TOTAL_CONTENT_BYTES = 5 * 1024 * 1024; // 5MB

/** Running total of captured content bytes (for OOM protection). */
let totalContentBytes = 0;

/** Maximum content to include in getResources response (in bytes). Larger content requires getResourceContent. */
const MAX_INLINE_CONTENT_BYTES = 10240; // 10KB — inline small content in list responses

/** Resource types that should never be evicted (core page resources) */
const PROTECTED_TYPES = new Set(['document', 'stylesheet']);

/** Current page URL (top-level navigation) */
let currentPageUrl = null;

/** Port connection to the host app */
let hostPort = null;

/** Text-like MIME type patterns — these resources get body captured */
const TEXT_MIME_PATTERNS = [
  'text/', 'javascript', 'ecmascript', 'json', 'xml',
  'svg', 'html', 'css', 'x-httpd'
];

/** Binary MIME type patterns — these are NEVER body-captured */
const BINARY_MIME_PATTERNS = [
  'image/', 'font/', 'audio', 'video', 'pdf', 'zip',
  'octet-stream', 'wasm', 'application/java',
  'application/x-', 'application/pdf', 'application/zip',
  'application/octet-stream', 'application/wasm',
  'application/java-archive', 'application/x-font-',
  'font/woff', 'font/ttf', 'font/otf'
];

/** Resource types (from webRequest) that are text and should be captured */
const CAPTURABLE_TYPES = new Set([
  'main_frame', 'sub_frame', 'script', 'stylesheet', 'xmlhttprequest', 'fetch'
]);

/* ── Helpers ─────────────────────────────────────────────── */

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

/** Check if a Content-Type is a text-based MIME that should have its body captured */
function isTextMimeType(contentType) {
  if (!contentType) return false; // No Content-Type → safe default: don't capture
  const ct = contentType.toLowerCase();
  // Explicitly exclude binary types first
  for (const pattern of BINARY_MIME_PATTERNS) {
    if (ct.includes(pattern)) return false;
  }
  // Check text patterns
  for (const pattern of TEXT_MIME_PATTERNS) {
    if (ct.includes(pattern)) return true;
  }
  return false; // Unknown MIME type → don't capture (safe default)
}

/** Check if a resource type (from webRequest details.type) should have its body captured */
function isCapturableResourceType(type) {
  return CAPTURABLE_TYPES.has(type);
}



/* ── webRequest Listeners ──────────────────────────────── */

/**
 * onHeadersReceived: capture response headers AND decide whether to capture body.
 *
 * For text-based resources with Content-Length ≤ threshold (or unknown length),
 * we use filterResponseData to intercept the response stream.
 */
/** Evict oldest content to stay under MAX_TOTAL_CONTENT_BYTES. */
function evictContent(newBytes) {
  totalContentBytes += newBytes;
  while (totalContentBytes > MAX_TOTAL_CONTENT_BYTES) {
    let oldestKey = null;
    let oldestTime = Infinity;
    // Find the oldest non-protected entry with content
    for (const [key, val] of resources) {
      if (val.content != null && !PROTECTED_TYPES.has(val.type)) {
        // Use insertion order (Map preserves it) as proxy for age
        if (oldestKey === null) {
          oldestKey = key;
          break; // Map iterates in insertion order, first with content is oldest
        }
      }
    }
    if (oldestKey === null) break; // Nothing left to evict
    const entry = resources.get(oldestKey);
    if (entry && entry.content != null) {
      totalContentBytes -= entry.content.length; // Approximate byte count
      entry.content = null; // Free memory but keep metadata
    } else {
      break;
    }
  }
}

/** Check if response headers indicate an authenticated resource that should not be captured. */
function isAuthenticated(headers) {
  for (const h of headers) {
    const name = h.name.toLowerCase();
    if (name === 'authorization' || name === 'www-authenticate') {
      return true;
    }
  }
  return false;
}

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

    // Determine binary status using both Content-Type and request type.
    // Many servers omit Content-Type for JS/CSS, but webRequest details.type
    // reliably identifies the resource category.
    const isTextByType = isCapturableResourceType(details.type) ||
      details.type === 'main_frame' || details.type === 'sub_frame';
    const isBinary = contentType
      ? !isTextMimeType(contentType)  // Has Content-Type: use it
      : !isTextByType;                  // No Content-Type: trust request type instead of assuming binary

    // Decide whether to capture the response body (must be determined BEFORE resources.set)
    const shouldCapture = !isBinary &&
      isCapturableResourceType(details.type) &&
      (contentLength === -1 || contentLength <= MAX_CONTENT_BYTES) &&
      !isAuthenticated(headers);

    const entry = {
      url: details.url,
      type: type,
      mimeType: contentType,
      size: contentLength,
      statusCode: details.statusCode || -1,
      isBinary: isBinary,
      hasContent: shouldCapture, // Signal to Kotlin that content will be available
      content: null,  // Will be filled by stream capture if applicable
      contentTruncated: false
    };

    // Merge with existing entry: preserve content AND metadata when we already captured it
    const existing = resources.get(details.url);
    if (existing) {
      if (existing.content != null) {
        // Preserve previously captured content (don't overwrite with null)
        entry.content = existing.content;
        entry.contentTruncated = existing.contentTruncated;
        // Also fix metadata: if we have content, the resource is not binary and has content
        entry.isBinary = false;
        entry.hasContent = true;
      }
    }

    // Deduplicate: same URL gets updated with richer info
    resources.set(details.url, entry);

    // Enforce limit with priority-based eviction
    if (resources.size > MAX_RESOURCES) {
      for (const [key, val] of resources) {
        if (PROTECTED_TYPES.has(val.type)) continue;
        // Reclaim content bytes before deleting
        if (val.content != null) {
          totalContentBytes -= val.content.length;
        }
        resources.delete(key);
        if (resources.size <= MAX_RESOURCES) break;
      }
    }

    // Early return: skip filterResponseData overhead if not capturing
    if (!shouldCapture) {
      return;
    }

    if (typeof browser.webRequest.filterResponseData === 'function') {
      try {
        const filter = browser.webRequest.filterResponseData(details.requestId);
        const capturedChunks = [];
        let capturedSize = 0;

        filter.ondata = (event) => {
          // Pass through the data to the browser unchanged
          filter.write(event.data);

          // Also collect it for our purposes
          if (capturedSize < MAX_CONTENT_BYTES) {
            const remaining = MAX_CONTENT_BYTES - capturedSize;
            if (event.data.byteLength <= remaining) {
              capturedChunks.push(event.data);
              capturedSize += event.data.byteLength;
            } else {
              // Truncate: only take what fits
              const sliced = event.data.slice(0, remaining);
              capturedChunks.push(sliced);
              capturedSize += sliced.byteLength;
            }
          }
        };

        filter.onstop = () => {
          filter.disconnect();

          // Decode collected chunks
          if (capturedChunks.length > 0) {
            try {
              const decoder = new TextDecoder('utf-8');
              let content = '';
              let totalBytes = 0;

              for (const chunk of capturedChunks) {
                totalBytes += chunk.byteLength;
                content += decoder.decode(chunk, { stream: true });
              }
              // Flush the decoder
              content += decoder.decode(undefined, { stream: false });

              // Update the resource entry — but check it still exists (may have been evicted)
              const current = resources.get(details.url);
              if (current) {
                current.content = content;
                current.contentTruncated = capturedSize >= MAX_CONTENT_BYTES;
                // Update size if we didn't have it from headers
                if (current.size <= 0) {
                  current.size = totalBytes;
                }
                // Enforce total content budget
                evictContent(content.length);
              }
            } catch (e) {
              console.warn('[ResourceMonitor] Failed to decode captured stream:', e);
            }
          }
        };

        filter.onerror = () => {
          filter.disconnect();
        };
      } catch (e) {
        // filterResponseData may fail for some request types
        console.warn('[ResourceMonitor] filterResponseData failed for', details.url, e);
      }
    }
  },
  { urls: ['<all_urls>'] },
  ['responseHeaders', 'blocking']
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
      // Main frame navigation — reset resources and content budget
      resources.clear();
      totalContentBytes = 0;
      currentPageUrl = details.url;
    }
  },
  { url: [{ schemes: ['http', 'https'] }] }
);

/* ── Host Port Connection ───────────────────────────────── */

/**
 * Connect to the native app when the extension starts.
 * The host app (GeckoView) will receive this via MessageDelegate.onConnect().
 */
function connectToHost() {
  try {
    hostPort = browser.runtime.connectNative('devcompanion');

    hostPort.onMessage.addListener((message) => {
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
  const requestId = message.requestId; // Forward requestId for response correlation

  switch (message.command) {
    case 'getResources':
      sendResourceList(requestId);
      break;

    case 'getResourceContent':
      sendResourceContent(message.url, requestId);
      break;

    case 'getResourceCount':
      if (hostPort) {
        const msg = { command: 'getResourceCount', count: resources.size };
        if (requestId) msg.requestId = requestId;
        hostPort.postMessage(msg);
      }
      break;

    default:
      console.warn('[ResourceMonitor] Unknown command:', message.command);
  }
}

/**
 * Send the list of resources with inline content for small text resources.
 * Large content is excluded — use getResourceContent for those.
 */
function sendResourceList(requestId) {
  const list = Array.from(resources.values()).map((entry) => {
    const obj = {
      url: entry.url,
      type: entry.type,
      mimeType: entry.mimeType,
      size: entry.size,
      statusCode: entry.statusCode,
      isBinary: entry.isBinary,
      hasContent: !!entry.content // Always send hasContent
    };
    // Inline small content to avoid round-trip
    if (entry.content && entry.content.length <= MAX_INLINE_CONTENT_BYTES) {
      obj.content = entry.content;
      obj.contentTruncated = entry.contentTruncated;
    } else if (entry.content) {
      // Content exists but is too large for inline — hasContent already set to true
      obj.contentTruncated = entry.contentTruncated;
    }
    return obj;
  });

  if (hostPort) {
    const msg = { command: 'getResources', resources: list };
    if (requestId) msg.requestId = requestId;
    hostPort.postMessage(msg);
  }
}

/**
 * Send the content of a specific resource, identified by URL.
 * Used by the host app when the user taps a resource to view its source.
 */
function sendResourceContent(url, requestId) {
  const entry = resources.get(url);
  if (!entry) {
    if (hostPort) {
      const msg = { command: 'getResourceContent', url: url, content: null, error: 'Resource not found' };
      if (requestId) msg.requestId = requestId;
      hostPort.postMessage(msg);
    }
    return;
  }

  if (entry.isBinary || !entry.content) {
    if (hostPort) {
      const msg = { command: 'getResourceContent', url: url, content: null, isBinary: entry.isBinary };
      if (requestId) msg.requestId = requestId;
      hostPort.postMessage(msg);
    }
    return;
  }

  if (hostPort) {
    const msg = { command: 'getResourceContent', url: url, content: entry.content, contentTruncated: entry.contentTruncated };
    if (requestId) msg.requestId = requestId;
    hostPort.postMessage(msg);
  }
}

/* ── Runtime message handler (fallback for sendMessage) ── */

/**
 * Handle runtime messages as a fallback communication channel.
 */
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message || !message.command) return false;

  switch (message.command) {
    case 'getResources': {
      const list = Array.from(resources.values()).map((entry) => {
        const obj = {
          url: entry.url,
          type: entry.type,
          mimeType: entry.mimeType,
          size: entry.size,
          statusCode: entry.statusCode,
          isBinary: entry.isBinary,
          hasContent: !!entry.content
        };
        if (entry.content && entry.content.length <= MAX_INLINE_CONTENT_BYTES) {
          obj.content = entry.content;
          obj.contentTruncated = entry.contentTruncated;
        } else if (entry.content) {
          obj.contentTruncated = entry.contentTruncated;
        }
        return obj;
      });
      const response = { resources: list };
      if (message.requestId) response.requestId = message.requestId;
      sendResponse(response);
      return true;
    }

    case 'getResourceContent': {
      const entry = resources.get(message.url);
      const response = {};
      if (message.requestId) response.requestId = message.requestId;
      if (!entry) {
        response.url = message.url;
        response.content = null;
        response.error = 'Resource not found';
        sendResponse(response);
        return true;
      }
      if (entry.isBinary || !entry.content) {
        response.url = message.url;
        response.content = null;
        response.isBinary = entry.isBinary;
        sendResponse(response);
        return true;
      }
      response.url = message.url;
      response.content = entry.content;
      response.contentTruncated = entry.contentTruncated;
      sendResponse(response);
      return true;
    }

    case 'getResourceCount': {
      const response = { count: resources.size };
      if (message.requestId) response.requestId = message.requestId;
      sendResponse(response);
      return true;
    }

    default:
      return false;
  }
});

/* ── Initialize ─────────────────────────────────────────── */

// Connect to host app on startup
connectToHost();