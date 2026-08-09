// SPDX-License-Identifier: Apache-2.0
// The only file that talks HTTP: token bootstrap, fetch wrappers, and the SSE client.
// See docs/webclient.md and docs/http.md (auth tiers, #t= fragment bootstrap).

const TOKEN_KEY = 'jk-http-token';
const EPOCH_KEY = 'jk-engine-epoch';
const EPOCH_HEADER = 'X-Jk-Engine-Epoch';

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

/** Latched process generation for fail-closed API calls (JK-1724). */
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
    return 'ok';
  }
  if (prev !== next) return 'mismatch';
  return 'ok';
}

/** Full shell reload when the engine generation under this tab has changed. Loop-safe. */
export function hardRefreshForEpoch() {
  const flag = 'jk-epoch-reload';
  if (sessionStorage.getItem(flag) === '1') {
    sessionStorage.removeItem(flag);
    return;
  }
  sessionStorage.setItem(flag, '1');
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
 * GET an /api path as parsed JSON. Throws {status} on any non-2xx so callers can branch on 401.
 * Optional {@code opts.signal} (AbortSignal) cancels the fetch when a lazy panel is closed.
 * {@code opts.bootstrap} skips the epoch header (only for GET /api/status discovery).
 */
export async function get(path, opts = {}) {
  const bootstrap = !!opts.bootstrap || path === '/api/status' || path.startsWith('/api/status?');
  const resp = await fetch(path, { headers: headers(!bootstrap), signal: opts.signal });
  if (await handleEpochConflict(resp)) throw { status: 409, error: 'engine-epoch-mismatch' };
  if (!resp.ok) throw { status: resp.status };
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
 * Build activity is folded by fold.js; `status` / `cache` update chrome vitals (JK-1495+).
 */
const EVENT_TYPES = [
  'request-start',
  'plan',
  'module-start',
  'task-start',
  'task-finish',
  'label',
  'plan-progress',
  'workspace-progress',
  'eta',
  'output',
  'diagnostic',
  'buildplan-finish',
  'module-finish',
  'request-finish',
  'status',
  'cache',
];

/**
 * Open the SSE stream. `onEvent({type, data})` per engine event; `onState('live'|'offline')` as the
 * connection comes and goes. EventSource reconnects on its own after NETWORK errors only — any
 * non-200 response (503 while the engine respawns or the SSE budget is exhausted, 421 bad Host)
 * closes it permanently, so the caller's offline poll re-creates the source when it finds
 * readyState CLOSED (JK-1518). An HTTP-enabled engine never idles out (docs/http.md), so
 * 'offline' only ever means an explicit stop, an upgrade respawn, or a crash. EventSource cannot
 * send headers, so non-loopback origins carry the token as a query parameter.
 */
export function events(onEvent, onState) {
  // EventSource cannot send Authorization headers — always pass the token as a query param
  // when present (required on loopback too now that /api/* is fully gated).
  const query = token() ? '?access_token=' + encodeURIComponent(token()) : '';
  const source = new EventSource('/api/events' + query);
  source.onopen = () => onState('live');
  source.onerror = () => onState('offline');
  for (const type of EVENT_TYPES) {
    source.addEventListener(type, (e) => {
      try {
        onEvent({ type, data: JSON.parse(e.data) });
      } catch {
        // a malformed frame is dropped, never fatal to the stream
      }
    });
  }
  return source;
}

/** Shared ECharts tooltip chrome (JK-1726) — soft Jk Dark panel, not a harsh black slab. */
export function echartsTooltipChrome(cssVar) {
  const mono = cssVar('--mono', 'monospace');
  return {
    appendToBody: true,
    backgroundColor: cssVar('--s2', '#1c2630'),
    borderColor: cssVar('--bd', '#2a3742'),
    borderWidth: 1,
    padding: [6, 10],
    textStyle: { color: cssVar('--tx', '#cfd8dc'), fontSize: 11, fontFamily: mono },
    extraCssText:
      'border-radius:6px;box-shadow:0 10px 28px -8px rgba(0,0,0,0.55);font-family:' + mono + ';',
  };
}
