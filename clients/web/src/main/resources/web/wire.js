// SPDX-License-Identifier: Apache-2.0
// The one place a wire event name is typed in JavaScript. The SPA is deliberately no-build-step
// and cannot import Java, so these values mirror their Java owners by hand — and the :web parity
// test (WireTokenParityTest) holds every token to its owner, so a typo or a hand-typed copy in
// another module fails the build instead of silently never matching.

/** Engine build events (values owned by EngineProtocol in shared/wire). */
export const EVENT = Object.freeze({
  moduleStart: 'module-start',
  taskStart: 'task-start',
  taskFinish: 'task-finish',
  label: 'label',
  progress: 'progress',
  workspaceProgress: 'workspace-progress',
  eta: 'eta',
  output: 'output',
  error: 'error',
  buildplanFinish: 'buildplan-finish',
  moduleFinish: 'module-finish',
  status: 'status',
  downloads: 'downloads',
});

/**
 * The dashboard's own SSE vocabulary — request lifecycle, mid-flight catch-up, chrome vitals. No
 * socket-protocol token spells these; the engine writes them as literals (SsePublisher, LiveRuns,
 * LiveVitals) or names them in EventCatalog (`decision`, `presence` / `notify`: the stream's id and
 * ping, and a browser notification to show, and `notice`: the bell's notices and seen marker), which
 * is what the parity test checks them against.
 */
export const SSE = Object.freeze({
  requestQueued: 'request-queued',
  requestStart: 'request-start',
  requestFinish: 'request-finish',
  runSnapshot: 'run-snapshot',
  plan: 'plan',
  cache: 'cache',
  decision: 'decision',
  presence: 'presence',
  notify: 'notify',
  notice: 'notice',
});

/**
 * The session log's dashboard-only frames (values owned by EventCatalog in shared/wire): a
 * session started, went idle or changed its current episode; an episode started, was retitled or
 * settled; one non-run MCP action.
 */
export const LOG = Object.freeze({
  session: 'session',
  episode: 'episode',
  action: 'action',
});

