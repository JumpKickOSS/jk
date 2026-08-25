// SPDX-License-Identifier: Apache-2.0
// The only file that talks HTTP: token bootstrap, fetch wrappers, and the SSE client.
// See docs/webclient.md and docs/http.md (auth tiers, #t= fragment bootstrap).

const TOKEN_KEY = 'jk-http-token';
const EPOCH_KEY = 'jk-engine-epoch';
const EPOCH_HEADER = 'X-Jk-Engine-Epoch';
const RELOAD_FLAG = 'jk-epoch-reload';

/**
 * On load, adopt a token from the URL fragment (`#t=…` — printed by `jk engine status`), stash it
 * in sessionStorage <em>and</em> localStorage, and scrub it from the address bar. Fragments never
 * leave the browser. localStorage lets a new tab on the same origin load history without
 * re-opening the tokenized URL (sessionStorage alone is per-tab).
 */
export function bootstrapToken() {
  const match = /^#t=([A-Za-z0-9_=-]+)$/.exec(location.hash);
  if (match) {
    storeToken(match[1]);
    history.replaceState(null, '', location.pathname + location.search);
    return;
  }
  // Promote a previously stored token into this tab's session when the hash is absent
  // (plain refresh, new tab, bookmarked loopback URL).
  if (!sessionStorage.getItem(TOKEN_KEY)) {
    try {
      const saved = localStorage.getItem(TOKEN_KEY);
      if (saved) sessionStorage.setItem(TOKEN_KEY, saved);
    } catch {
      // private mode / blocked storage — token stays absent; loopback history still works
    }
  }
}

function storeToken(value) {
  sessionStorage.setItem(TOKEN_KEY, value);
  try {
    localStorage.setItem(TOKEN_KEY, value);
  } catch {
    // best-effort cross-tab persistence
  }
}

/** Persist a bearer token (URL fragment bootstrap / tests). Scrubs surrounding whitespace. */
export function applyToken(value) {
  const t = (value || '').trim();
  if (!t) return false;
  storeToken(t);
  return true;
}

/** Drop a missing/invalid token so we stop sending a bad Authorization header. */
export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY);
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    // private mode
  }
}

export function token() {
  return sessionStorage.getItem(TOKEN_KEY) || (() => {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  })();
}

export function loopback() {
  return ['127.0.0.1', 'localhost', '[::1]', '::1'].includes(location.hostname);
}

/** Latched process generation for fail-closed API calls. */
export function engineEpoch() {
  return sessionStorage.getItem(EPOCH_KEY) || null;
}

/**
 * Latch {@code engineEpoch} from a status payload. Returns {@code 'mismatch'} when the generation
 * changed (caller should hard-reload), {@code 'ok'} when unchanged or first latch, {@code 'none'}
 * when the payload has no epoch.
 */
export function noteEngineEpoch(statusOrEpoch) {
  const next =
    statusOrEpoch == null
      ? null
      : typeof statusOrEpoch === 'string'
        ? statusOrEpoch
        : statusOrEpoch.engineEpoch;
  if (next == null || next === '') return 'none';
  const prev = engineEpoch();
  if (prev == null) {
    sessionStorage.setItem(EPOCH_KEY, next);
    clearReloadLatch();
    return 'ok';
  }
  if (prev !== next) {
    // Latch the new generation before the caller reloads (mirrors handleEpochConflict) so the
    // post-reload hydrate sees a consistent epoch and clears the reload latch in one pass.
    sessionStorage.setItem(EPOCH_KEY, next);
    return 'mismatch';
  }
  // Epoch-consistent hydrate: the tab and engine agree, so any pending reload latch is stale.
  // Clearing it here keeps the flag purely as an in-flight-reload latch — a genuine restart after
  // this point must hard-refresh on first detection, not be swallowed.
  clearReloadLatch();
  return 'ok';
}

function clearReloadLatch() {
  sessionStorage.removeItem(RELOAD_FLAG);
}

/**
 * Unsaved-work probe. The editor owning a dirty buffer registers a zero-arg function
 * returning truthy while unsaved edits exist; {@link hardRefreshForEpoch} then asks before
 * discarding them instead of silently reloading over the buffer. One guard is enough — the Files
 * pane is the only editable surface.
 */
let dirtyGuard = null;
let epochReloadDeferred = false;

export function registerDirtyGuard(fn) {
  dirtyGuard = fn;
}

export function unregisterDirtyGuard(fn) {
  if (dirtyGuard === fn) dirtyGuard = null;
}

/**
 * The guard owner calls this when its buffer becomes clean (saved elsewhere, discarded, file
 * closed). If an epoch reload was deferred behind the dirty buffer, it proceeds now.
 */
export function releaseDeferredEpochReload() {
  if (!epochReloadDeferred) return;
  epochReloadDeferred = false;
  hardRefreshForEpoch();
}

function hasDirtyBuffer() {
  if (!dirtyGuard) return false;
  try {
    return !!dirtyGuard();
  } catch {
    return false;
  }
}

/**
 * Full shell reload when the engine generation under this tab has changed. Loop-safe. With a
 * dirty editor buffer, asks once before discarding; a declined reload is deferred (background
 * polls stop nagging) until the buffer is clean ({@link releaseDeferredEpochReload}) or the user
 * reloads by hand.
 */
export function hardRefreshForEpoch() {
  if (hasDirtyBuffer()) {
    if (epochReloadDeferred) return;
    const ok =
      typeof window !== 'undefined' && typeof window.confirm === 'function'
        ? window.confirm('The engine restarted and the page must reload. Discard unsaved changes?')
        : true;
    if (!ok) {
      epochReloadDeferred = true;
      return;
    }
  }
  epochReloadDeferred = false;
  if (sessionStorage.getItem(RELOAD_FLAG) === '1') {
    // A reload for this mismatch is already in flight (or the last one failed to resolve it);
    // don't loop. The latch is cleared by the next epoch-consistent hydrate (noteEngineEpoch).
    sessionStorage.removeItem(RELOAD_FLAG);
    return;
  }
  sessionStorage.setItem(RELOAD_FLAG, '1');
  location.reload();
}

function headers(includeEpoch) {
  const h = {};
  const t = token();
  if (t) h.Authorization = 'Bearer ' + t;
  if (includeEpoch !== false) {
    const ep = engineEpoch();
    if (ep) h[EPOCH_HEADER] = ep;
  }
  return h;
}

function throwHttp(resp, json) {
  const err = { status: resp.status };
  if (json && json.error) err.error = json.error;
  if (json && json.engineEpoch) err.engineEpoch = json.engineEpoch;
  throw err;
}

async function handleEpochConflict(resp) {
  if (resp.status !== 409) return false;
  let json = {};
  try {
    json = await resp.clone().json();
  } catch {
    // not JSON
  }
  if (json.error === 'engine-epoch-mismatch' || json.engineEpoch) {
    if (json.engineEpoch) sessionStorage.setItem(EPOCH_KEY, json.engineEpoch);
    hardRefreshForEpoch();
    return true;
  }
  return false;
}

/**
 * GET an /api path as parsed JSON. Throws {status, error?} on any non-2xx so callers can branch on
 * 401 or show the server's message (the engine puts a human-readable `error` in 4xx bodies).
 * Optional {@code opts.signal} (AbortSignal) cancels the fetch when a lazy panel is closed. {@code opts.bootstrap} skips the epoch header (only for GET /api/status discovery).
 */
export async function get(path, opts = {}) {
  const bootstrap = !!opts.bootstrap || path === '/api/status' || path.startsWith('/api/status?');
  const resp = await fetch(path, { headers: headers(!bootstrap), signal: opts.signal });
  if (await handleEpochConflict(resp)) throw { status: 409, error: 'engine-epoch-mismatch' };
  if (!resp.ok) {
    const json = await resp.json().catch(() => ({}));
    throwHttp(resp, json);
  }
  return resp.json();
}

/** POST a flat object to an /api path; returns parsed JSON, throws {status, error} on non-2xx. */
export async function post(path, body) {
  const resp = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers(true) },
    body: JSON.stringify(body),
  });
  if (await handleEpochConflict(resp)) throw { status: 409, error: 'engine-epoch-mismatch' };
  const json = await resp.json().catch(() => ({}));
  if (!resp.ok) throwHttp(resp, json);
  return json;
}

/** PUT a flat object to an /api path; returns parsed JSON, throws {status, error} on non-2xx. */
export async function put(path, body) {
  const resp = await fetch(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...headers(true) },
    body: JSON.stringify(body),
  });
  if (await handleEpochConflict(resp)) throw { status: 409, error: 'engine-epoch-mismatch' };
  const json = await resp.json().catch(() => ({}));
  if (!resp.ok) throwHttp(resp, json);
  return json;
}

/**
 * GET an /api path as a Blob (raw file bytes for image Preview). Throws {status, error?} on
 * non-2xx. Uses the same Authorization / epoch headers as other mutations.
 */
export async function getBlob(path, opts = {}) {
  const resp = await fetch(path, { headers: headers(true), signal: opts.signal });
  if (await handleEpochConflict(resp)) throw { status: 409, error: 'engine-epoch-mismatch' };
  if (!resp.ok) {
    const json = await resp.json().catch(() => ({}));
    throwHttp(resp, json);
  }
  return resp.blob();
}

/** GET an /api path as plain text (the log tail). Throws {status} on any non-2xx. */
export async function getText(path) {
  const resp = await fetch(path, { headers: headers(true) });
  if (await handleEpochConflict(resp)) throw { status: 409, error: 'engine-epoch-mismatch' };
  if (!resp.ok) throw { status: resp.status };
  return resp.text();
}

/** DELETE an /api path (a mutation — always token-bearing). Throws {status} on non-2xx. */
export async function del(path) {
  const resp = await fetch(path, { method: 'DELETE', headers: headers(true) });
  if (await handleEpochConflict(resp)) throw { status: 409, error: 'engine-epoch-mismatch' };
  if (!resp.ok) throw { status: resp.status };
  return resp.json().catch(() => ({}));
}

/**
 * Engine event types the dashboard listens for (EventSource needs a listener per named event).
 * Build activity is folded by fold.js; `status` / `cache` update chrome vitals.
 */
const EVENT_TYPES = [
  'request-start',
  'run-snapshot', // mid-flight catch-up on SSE connect (one frame per running job)
  'plan',
  'module-start',
  'task-start',
  'task-finish',
  'label',
  'progress',
  'workspace-progress',
  'eta',
  'output',
  'error',
  'buildplan-finish',
  'module-finish',
  'request-finish',
  'status',
  'cache',
];

/**
 * Event types that are safe to coalesce to the latest pending frame per request (or globally for
 * chrome). Collapsing these in the browser keeps the main thread free so the progress bar and ETA
 * keep painting while a storm of structural/output frames is still being parsed.
 */
const COALESCE_TYPES = new Set([
  'workspace-progress',
  'progress',
  'eta',
  'label',
  'status',
  'cache',
]);

/**
 * Open the SSE stream. `onEvent({type, data})` per engine event; `onState('live'|'offline')` as the
 * connection comes and goes. EventSource reconnects on its own after NETWORK errors only — any
 * non-200 response (503 while the engine respawns or the SSE budget is exhausted, 421 bad Host)
 * closes it permanently, so the caller's offline poll re-creates the source when it finds
 * readyState CLOSED. An HTTP-enabled engine never idles out (docs/http.md), so
 * 'offline' only ever means an explicit stop, an upgrade respawn, or a crash. EventSource cannot
 * send headers, so non-loopback origins carry the token as a query parameter.
 *
 * <p>Handlers return immediately: frames are queued and drained on animation frames so a burst of
 * compiler {@code output} cannot freeze Vue (the CLI TUI is on a separate socket and stays smooth).
 */
export function events(onEvent, onState) {
  // EventSource cannot send Authorization headers — always pass the token as a query param
  // when present (required on loopback too now that /api/* is fully gated).
  const query = token() ? '?access_token=' + encodeURIComponent(token()) : '';
  const source = new EventSource('/api/events' + query);
  source.onopen = () => onState('live');
  source.onerror = () => onState('offline');

  /** @type {{type: string, data: object}[]} */
  const queue = [];
  /** Coalesced slots: key → index in queue (or latest object replaces in place). */
  const coalesceAt = new Map();
  let drainScheduled = false;

  function coalesceKey(type, data) {
    if (!COALESCE_TYPES.has(type)) return null;
    if (type === 'status' || type === 'cache') return type;
    const rid = data && data.jid != null ? data.jid : '';
    // progress is per-module; label targets a specific STEP row — with parallel workers in
    // one plan, a (type, rid, dir) key let step B's pending label overwrite step A's before the
    // drain, leaving A's detail stale until its next tick.
    if (type === 'label') {
      return type + ':' + rid + ':' + ((data && data.dir) || '') + ':' + ((data && (data.task || data.step)) || '');
    }
    if (type === 'progress') {
      return type + ':' + rid + ':' + ((data && data.dir) || '');
    }
    return type + ':' + rid;
  }

  function enqueue(event) {
    const key = coalesceKey(event.type, event.data);
    if (key != null) {
      const idx = coalesceAt.get(key);
      if (idx != null && queue[idx]) {
        queue[idx] = event; // keep position; replace payload with newest
        return;
      }
      coalesceAt.set(key, queue.length);
    }
    queue.push(event);
  }

  function drain() {
    drainScheduled = false;
    // Bound work per turn so paint/ETA timers stay responsive under a backlog.
    const budgetMs = 6;
    const start = performance.now();
    let n = 0;
    while (queue.length && performance.now() - start < budgetMs) {
      const ev = queue.shift();
      n++;
      // Rebuild coalesce index cheaply when we drain — clear and re-index remaining.
      if (n === 1) coalesceAt.clear();
      try {
        onEvent(ev);
      } catch {
        // a bad handler must not stall the drain loop
      }
    }
    // Re-index coalesce map for anything still queued.
    if (queue.length) {
      coalesceAt.clear();
      for (let i = 0; i < queue.length; i++) {
        const k = coalesceKey(queue[i].type, queue[i].data);
        if (k != null) coalesceAt.set(k, i);
      }
      scheduleDrain();
    }
  }

  function scheduleDrain() {
    if (drainScheduled) return;
    drainScheduled = true;
    // rAF aligns with paint; fall back to macrotask when the tab is backgrounded (rAF throttles).
    if (typeof requestAnimationFrame === 'function' && !document.hidden) {
      requestAnimationFrame(drain);
    } else {
      setTimeout(drain, 0);
    }
  }

  // A drain armed on rAF never fires once the tab hides (background tabs get no animation
  // frames): drainScheduled stayed true, every later scheduleDrain() no-opped, and an overnight
  // build's frames piled up unapplied for hours. Re-arm on a macrotask at the hide
  // transition; drain() clears the flag first, so a stale rAF firing on the next show just
  // drains whatever is left. The listener unhooks itself once the stream is closed.
  const rearmOnHide = () => {
    if (source.readyState === EventSource.CLOSED) {
      document.removeEventListener('visibilitychange', rearmOnHide);
      return;
    }
    if (document.hidden && drainScheduled) setTimeout(drain, 0);
  };
  document.addEventListener('visibilitychange', rearmOnHide);

  for (const type of EVENT_TYPES) {
    source.addEventListener(type, (e) => {
      try {
        enqueue({ type, data: JSON.parse(e.data) });
        scheduleDrain();
      } catch {
        // a malformed frame is dropped, never fatal to the stream
      }
    });
  }
  return source;
}

/** Shared ECharts tooltip chrome — soft Jk Dark panel, not a harsh black slab. */
export function echartsTooltipChrome(cssVar) {
  const mono = cssVar('--mono', 'monospace');
  return {
    appendToBody: true,
    backgroundColor: cssVar('--tip-bg', '#0b0f13'),
    borderColor: cssVar('--bd', '#2a3742'),
    borderWidth: 1,
    padding: [6, 10],
    textStyle: { color: cssVar('--tx', '#cfd8dc'), fontSize: 11, fontFamily: mono },
    extraCssText:
      'border-radius:6px;box-shadow:0 10px 28px -8px rgba(0,0,0,0.55);font-family:' + mono + ';',
  };
}
