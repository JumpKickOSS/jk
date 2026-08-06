// SPDX-License-Identifier: Apache-2.0
// Folds the /api/events stream into activity cards — pure functions of (cards, event), no browser
// globals, so the logic is testable headlessly with `node --test` (see docs/webclient.md).

/** Cards kept in the activity feed — a long-lived tab must not grow the page without limit. */
export const MAX_CARDS = 50;

/** Console-tail lines kept per running card. */
export const MAX_OUTPUT_LINES = 8;

/** Failure diagnostics kept per card (the server already bounds what it publishes). */
export const MAX_DIAGNOSTICS = 12;

/**
 * Fold one SSE event into the newest-first card list, mutating and returning it.
 * An event is `{type, data, at}` where `data` is the parsed flat JSON payload the engine
 * publishes and `at` is the client-clock receipt time (fold stays clock-free and pure):
 * request-start/finish carry requestId/kind/dir (+ coord on start when the project's jk.toml
 * parses; + success/cancelled/millis on finish); module/step/output/plan events carry
 * requestId/dir plus their specifics.
 */
export function foldEvent(cards, event) {
  const d = event.data || {};
  switch (event.type) {
    case 'request-start': {
      // Already attached (SSE connect rehydrate replayed, or this tab started the job).
      if (cards.some((c) => c.id === d.requestId)) break;
      // Reconcile with a durable in-flight history row (refresh / other tab) when buildNumber matches.
      const existing = cards.find(
        (c) =>
          c.state === 'running' &&
          c.dir === (d.dir || '') &&
          d.buildNumber &&
          c.buildNumber === d.buildNumber,
      );
      if (existing) {
        existing.id = d.requestId; // prefer live request id for subsequent SSE
        if (d.coord) existing.coord = d.coord;
        break;
      }
      cards.unshift({
        id: d.requestId,
        kind: d.kind || 'request',
        dir: d.dir || '',
        coord: d.coord || null,
        buildNumber: d.buildNumber || null,
        state: 'running',
        startedAt: event.at ?? null,
        finishedAt: null,
        millis: null,
        cancelled: false,
        success: null, // tri-state: null = engine didn't say (socket requests) — derive from modules
        // Every build is a list of module rows (a single-project build has one, keyed by the empty
        // SINGLE_GOAL_DIR); each row carries its OWN step chain, so the card shows a chain per
        // module rather than one merged strip.
        modules: [],
        // Fine-grained per-module plan ticks (detail only). Request-level bar uses
        // progressPercent from engine workspace-progress (JK-1120) — dumb client, no re-sum.
        mods: {},
        planWeight: 0,
        progressPercent: null, // 0–100 from workspace-progress; null until first aggregate event
        progressNum: 0,
        progressDen: 0,
        etaMillis: null,
        etaAt: null,
        output: [],
        // Failure output is kept per module (moduleRow.diagnostics), keyed by the diagnostic's dir,
        // so the dashboard nests each failure under its failed module inside "failure details".
      });
      if (cards.length > MAX_CARDS) cards.length = MAX_CARDS;
      break;
    }
    case 'module-start': {
      const card = resolveCard(cards, d);
      if (card) {
        const row = moduleRow(card, d.dir, event.at);
        row.state = 'running';
        if (d.coord) row.coord = d.coord;
      }
      break;
    }
    case 'task-start': {
      const card = resolveCard(cards, d);
      if (card) {
        const row = stepRow(card, d.dir, (d.task || d.step), d.phase, event.at);
        row.state = 'running';
        row.message = ''; // new step — clear previous tick text
      }
      break;
    }
    case 'task-finish': {
      const card = resolveCard(cards, d);
      if (card) {
        const row = stepRow(card, d.dir, (d.task || d.step), d.phase, event.at);
        row.state = stepState(d.status);
        // Keep last message for a moment of context only while running rows use it; finished
        // phases do not surface live detail.
      }
      break;
    }
    case 'label': {
      // Live step detail (test class.method, "shrinking jar", …) — CLI tree-row parity.
      const card = resolveCard(cards, d);
      if (card) stepRow(card, d.dir, (d.task || d.step), d.phase, event.at).message = d.label || '';
      break;
    }
    case 'plan': {
      const card = resolveCard(cards, d);
      if (card) card.planWeight = d.weight || 0;
      break;
    }
    case 'plan-progress': {
      const card = resolveCard(cards, d);
      // Fine-grained only — do not drive the request bar from module-local fractions.
      if (card) {
        card.mods[d.dir || ''] = { num: d.numerator || 0, den: d.denominator || 0 };
        moduleRow(card, d.dir, event.at); // bubble this module on ticks
      }
      break;
    }
    case 'workspace-progress': {
      const card = resolveCard(cards, d);
      if (card) {
        card.progressNum = d.numerator || 0;
        card.progressDen = d.denominator || 0;
        if (typeof d.progress === 'number') card.progressPercent = d.progress;
        else if (card.progressDen > 0) {
          card.progressPercent = Math.min(100, Math.round((100 * card.progressNum) / card.progressDen));
        }
      }
      break;
    }
    case 'eta': {
      const card = resolveCard(cards, d);
      if (card) {
        card.etaMillis = typeof d.millis === 'number' ? d.millis : null;
        card.etaAt = event.at ?? null;
      }
      break;
    }
    case 'output': {
      const card = resolveCard(cards, d);
      if (card && typeof d.line === 'string') {
        card.output.push({ dir: d.dir || '', line: d.line });
        if (card.output.length > MAX_OUTPUT_LINES) card.output.splice(0, card.output.length - MAX_OUTPUT_LINES);
        moduleRow(card, d.dir, event.at); // console output → active module floats up
      }
      break;
    }
    case 'diagnostic': {
      const card = resolveCard(cards, d);
      if (card) {
        const mod = moduleRow(card, d.dir, event.at);
        if (mod.diagnostics.length < MAX_DIAGNOSTICS) {
          mod.diagnostics.push({
            step: d.task || d.step || d.step || '',
            code: d.code || '',
            message: d.message || '',
            test: d.test || '',
            exceptionClass: d.exceptionClass || '',
          });
        }
      }
      break;
    }
    case 'buildplan-finish': {
      const card = resolveCard(cards, d);
      if (card) {
        const row = moduleRow(card, d.dir, event.at);
        row.state = d.success ? 'success' : 'failed';
      }
      break;
    }
    case 'module-finish': {
      const card = resolveCard(cards, d);
      if (card) {
        const row = moduleRow(card, d.dir, event.at);
        // didWork=false → pure cache check (JK-1296); treat as success but label checked.
        row.didWork = d.didWork !== false;
        row.state = d.success ? (row.didWork ? 'success' : 'checked') : 'failed';
        row.millis = d.millis ?? row.millis;
        if (d.coord) row.coord = d.coord;
      }
      break;
    }
    case 'request-finish': {
      const card = resolveCard(cards, d);
      if (card) {
        card.state = 'finished';
        card.finishedAt = event.at ?? null;
        card.millis = d.millis ?? null;
        card.cancelled = !!d.cancelled;
        card.success = typeof d.success === 'boolean' ? d.success : null;
        card.output = []; // the console tail is an in-flight affordance; finished cards are compact
        card.etaMillis = null; // the countdown is an in-flight affordance; a finished card is 100%
        card.io = ioOf(d); // byte counters, absent when the run moved nothing
      }
      break;
    }
    default:
      break; // unknown event types are future vocabulary, never an error
  }
  return cards;
}

/**
 * Aggregate numerator for the request bar (JK-1120). Prefers engine workspace-progress units;
 * falls back to summing module ticks only when no aggregate event has arrived yet.
 */
/**
 * Run-wide ETA total for a card, in ms — or null when the card has no usable ETA. `etaMillis` is
 * REMAINING work at emission time (BuildService remaining-work semantics), so the total is
 * (etaAt - startedAt) + etaMillis: the same elapsed+remaining conversion the CLI does. Without
 * it, a slow lock/prepare window or an ETA re-projection double-counts already-elapsed time and
 * the countdown hits "0s" while the build is on schedule (JK-1517). Journal-seeded cards carry
 * no etaAt: their etaMillis is treated as the total (legacy shape).
 */
export function etaTotalMillis(card) {
  if (typeof card.etaMillis !== 'number' || card.etaMillis <= 0) return null;
  if (card.etaAt != null && card.startedAt != null) {
    return card.etaAt - card.startedAt + card.etaMillis;
  }
  return card.etaMillis;
}

export function weightNumerator(card) {
  if (card.progressDen > 0 || card.progressPercent != null) return card.progressNum || 0;
  let n = 0;
  for (const dir in card.mods || {}) n += card.mods[dir].num || 0;
  return n;
}

/**
 * Aggregate denominator for the request bar. Prefers engine workspace-progress; else plan weight
 * or sum of module dens (legacy fallback).
 */
export function weightDenominator(card) {
  if (card.progressDen > 0) return card.progressDen;
  let den = 0;
  for (const dir in card.mods || {}) den += card.mods[dir].den || 0;
  return Math.max(card.planWeight || 0, den);
}

/**
 * Seed the feed with persisted history records (the `/api/history` array of full `record.json`
 * objects) so a reload or an engine restart doesn't lose past builds. Idempotent and safe to call
 * repeatedly (on load and after every reconnect): a run already present as a live SSE card is not
 * duplicated — instead the live card is tagged with its `historyId` so it becomes deletable. Live
 * cards key on the numeric engine request id (which resets per engine start); history entries key on
 * the durable string id, so the two never collide and are reconciled here by (dir, buildNumber) —
 * structurally, since `finishedAt` on a live card is browser receipt time while the record carries
 * engine time, and clock skew on a remote dashboard would otherwise duplicate every finished build
 * (JK-1519). The ±2s time window remains only as a fallback for cards without a buildNumber.
 */
export function seedFromHistory(cards, records) {
  for (const rec of records || []) {
    if (!rec || !rec.id) continue;
    const live = cards.find(
      (c) =>
        c.historyId === rec.id ||
        // A stale running:true stub (journal write racing the reconcile) must not rebind a
        // just-finished live card and flip it back to running — hence the state agreement guard.
        (typeof c.id === 'number' &&
          c.dir === rec.dir &&
          rec.buildNumber &&
          c.buildNumber === rec.buildNumber &&
          (!rec.running || c.state === 'running')) ||
        (typeof c.id === 'number' &&
          c.dir === rec.dir &&
          c.finishedAt != null &&
          Math.abs(c.finishedAt - rec.finishedAt) < 2000) ||
        // JK-1251: match a live SSE card to a durable in-flight journal row
        (c.state === 'running' &&
          rec.running &&
          c.dir === rec.dir &&
          rec.buildNumber &&
          c.buildNumber === rec.buildNumber),
    );
    if (live) {
      live.historyId = rec.id; // reconcile: the live card is this run — make it deletable
      if (rec.buildNumber) live.buildNumber = rec.buildNumber; // and pick up its assigned #number
      if (rec.running) live.state = 'running';
      // Enriched history may carry the engine requestId — rebind a journal stub for SSE.
      const liveId = rec.requestId ?? rec.jid;
      if (rec.running && typeof liveId === 'number' && liveId > 0) live.id = liveId;
      if (typeof rec.progress === 'number') live.progressPercent = rec.progress;
      continue;
    }
    if (cards.some((c) => c.id === 'h:' + rec.id)) continue; // already seeded
    // A running stub for a run this tab already saw finish is stale (reconcile raced the journal
    // write) — seeding it would add a phantom running row next to the finished card (JK-1519).
    if (
      rec.running &&
      rec.buildNumber &&
      cards.some((c) => typeof c.id === 'number' && c.dir === rec.dir && c.buildNumber === rec.buildNumber)
    ) {
      continue;
    }
    cards.push(historyCard(rec));
  }
  cards.sort((a, b) => (b.finishedAt ?? b.startedAt ?? 0) - (a.finishedAt ?? a.startedAt ?? 0));
  if (cards.length > MAX_CARDS) cards.length = MAX_CARDS;
  return cards;
}

/** One persisted record → a card matching {@link foldEvent}'s shape (finished or still running). */
function historyCard(rec) {
  const running = !!rec.running;
  // Prefer live engine requestId (enriched by GET /api/history) so SSE events rebind without
  // waiting for a second request-start after a hard refresh mid-build.
  const liveId = rec.requestId ?? rec.jid;
  const id = running && typeof liveId === 'number' && liveId > 0 ? liveId : 'h:' + rec.id;
  let progressPercent = null;
  if (typeof rec.progress === 'number') progressPercent = rec.progress;
  else if (typeof rec.progressPercent === 'number') progressPercent = rec.progressPercent;
  return {
    id,
    historyId: rec.id,
    buildNumber: rec.buildNumber || null,
    kind: rec.kind || 'build',
    dir: rec.dir || '',
    coord: rec.coord || null,
    state: running ? 'running' : 'finished',
    startedAt: rec.startedAt ?? null,
    finishedAt: running ? null : rec.finishedAt ?? null,
    millis: running ? null : rec.millis ?? null,
    cancelled: !!rec.cancelled,
    success: running ? null : typeof rec.success === 'boolean' ? rec.success : null,
    modules: historyModules(rec),
    output: [],
    mods: {},
    planWeight: 0,
    progressPercent,
    progressNum: 0,
    progressDen: 0,
    etaMillis: typeof rec.etaMillis === 'number' ? rec.etaMillis : null,
    etaAt: null,
    io: rec.io ? normalizeIo(rec.io) : null,
  };
}

/**
 * Byte counters off a `request-finish` event. The engine keeps the SSE payload flat (one scalar per
 * key, like the wire protocol), so the four counters arrive as `*Bytes` fields; `record.json` nests
 * them under `io` instead (see {@link normalizeIo}). Null when the run moved nothing — the engine
 * omits the fields rather than sending zeros.
 */
function ioOf(d) {
  const io = normalizeIo({
    remoteUp: d.remoteUpBytes,
    remoteDown: d.remoteDownBytes,
    localUp: d.localUpBytes,
    localDown: d.localDownBytes,
  });
  return io.remoteUp || io.remoteDown || io.localUp || io.localDown ? io : null;
}

/** The four counters as numbers, defaulting anything missing/non-numeric to 0. */
function normalizeIo(io) {
  const n = (v) => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : 0);
  return { remoteUp: n(io.remoteUp), remoteDown: n(io.remoteDown), localUp: n(io.localUp), localDown: n(io.localDown) };
}

/**
 * The card's I/O rows, newest-relevant first: `remote` (network) then `local` (build cache), each
 * `{scope, label, up, down}` — `scope` is the stable key the UI branches on, `label` the text it
 * renders. A scope with no traffic in either direction is dropped rather than rendered as zeros, so a
 * fully-cached offline build shows one line and a run that moved nothing shows none.
 */
export function ioLines(card) {
  const io = card && card.io;
  if (!io) return [];
  const lines = [];
  if (io.remoteUp || io.remoteDown) {
    lines.push({ scope: 'remote', label: 'remote data', up: io.remoteUp, down: io.remoteDown });
  }
  if (io.localUp || io.localDown) {
    lines.push({ scope: 'local', label: 'local data', up: io.localUp, down: io.localDown });
  }
  return lines;
}

/**
 * Human byte size, 1024-based, picking the unit that keeps the number small: `512 B`, `100 KiB`,
 * `12.4 MiB`, `1.5 GiB` — never `1533 MiB`. One decimal below 100, whole numbers above it, and a
 * trailing `.0` is dropped.
 */
export function fmtBytes(bytes) {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return Math.round(bytes) + ' B';
  const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let v = bytes;
  let u = -1;
  do {
    v /= 1024;
    u++;
  } while (v >= 1024 && u < units.length - 1);
  const n = v >= 100 ? String(Math.round(v)) : v.toFixed(1).replace(/\.0$/, '');
  return n + ' ' + units[u];
}

/** A persisted diagnostic → the client's flat failure-output shape (errors only; warnings dropped). */
function historyDiags(diags, dir) {
  return (diags || [])
    .filter((d) => d.severity !== 'warning' && (d.dir || '') === (dir || ''))
    .map((d) => ({
      step: d.task || d.step || d.step || '',
      code: d.code || '',
      message: d.message || '',
      test: d.test || '',
      exceptionClass: d.exceptionClass || '',
    }));
}

/**
 * Module rows for a persisted record, matching the live card shape (each with its own step chain).
 * A workspace record has `modules[]` each carrying `steps`; a single-project record has no modules
 * and its steps at the top level — synthesize one row from them so backfilled cards match live.
 */
function historyModules(rec) {
  const toSteps = (ps) => (ps || []).map((p) => ({ name: p.name || '?', state: stepState(p.status), phase: p.phase || '' }));
  // finishedAt / startedAt give a stable lastActivity for display order after backfill.
  const activity = rec.finishedAt || rec.startedAt || 0;
  if ((rec.modules || []).length > 0) {
    return rec.modules.map((m, i) => {
      const steps = toSteps(m.steps);
      return {
        dir: m.dir || '',
        coord: m.coord || null,
        // FAIL beats cancel; cancel-only modules (user kill mid-flight) stay cancelled.
        state: m.success
          ? 'success'
          : steps.some((s) => s.state === 'failed')
            ? 'failed'
            : rec.cancelled || steps.some((s) => s.state === 'cancelled')
              ? 'cancelled'
              : 'failed',
        millis: m.millis ?? null,
        steps,
        diagnostics: historyDiags(rec.diagnostics, m.dir || ''),
        // Preserve journal order as a tie-break (later modules slightly higher lastActivity).
        lastActivity: activity + i,
      };
    });
  }
  // Single-project: no modules, steps at top level. Its diagnostics live in the "" bucket, so take
  // every error the record carries (there is only one module to own them).
  const steps = toSteps(rec.steps);
  return [{
    dir: rec.dir || '',
    coord: rec.coord || null,
    // FAIL steps beat a cancel bit (journal/EOF races used to stamp cancelled on test failures).
    state: steps.some((s) => s.state === 'failed')
      ? 'failed'
      : rec.cancelled
        ? 'cancelled'
        : rec.success === false
          ? 'failed'
          : 'success',
    millis: rec.millis ?? null,
    steps,
    diagnostics: (rec.diagnostics || [])
      .filter((d) => d.severity !== 'warning')
      .map((d) => ({
        step: d.task || d.step || d.step || '',
        code: d.code || '',
        message: d.message || '',
        test: d.test || '',
        exceptionClass: d.exceptionClass || '',
      })),
    lastActivity: activity,
  }];
}

/** True when any step actually failed (FAIL) — not merely cancelled mid-flight. */
function hasFailedStep(card) {
  for (const m of card.modules || []) {
    for (const s of m.steps || []) {
      if (s.state === 'failed') return true;
    }
  }
  return false;
}

/**
 * A finished card's outcome badge: the engine's explicit success when it sent one (HTTP-triggered
 * builds do), else derived from module rows (socket requests encode their outcome in wire
 * messages, not events): any failed module → failed; all finished and some succeeded → success.
 *
 * <p>FAIL steps / failed modules take priority over {@code cancelled}. Cooperative fail-fast and
 * post-finish socket EOF can leave {@code cancelled=true} on a run that actually finished with
 * test/compile failures — those must read as failed, not cancelled.
 */
export function outcomeOf(card) {
  if (card.state === 'running') return 'running';
  // FAIL steps / failed modules first — a cancel bit alone must not mask a real test failure.
  if (hasFailedStep(card) || (card.modules || []).some((m) => m.state === 'failed')) return 'failed';
  if (card.cancelled || (card.modules || []).some((m) => m.state === 'cancelled')) return 'cancelled';
  if (card.success === true) return 'success';
  if (card.success === false) return 'failed';
  // success + checked are both green outcomes (JK-1296: pure cache re-entry is "checked")
  if (
    card.modules.length > 0
    && card.modules.every((m) => m.state === 'success' || m.state === 'checked')
  ) {
    return 'success';
  }
  return 'finished';
}

/**
 * Group a module's step rows into a coarse **phase-chain**, in first-encounter order (which is
 * plan order): the first step of a not-yet-seen phase appends a phase node; later steps of that
 * phase attach to it. The client stays "dumb" — it keys on whatever `phase` wire-string the steps
 * carry, so a future/plugin phase just appears as its own node with no code change here. A step with
 * no phase (`''`) forms a node keyed by its own name so it is never dropped (shouldn't happen once
 * every emitted step is phase-tagged). Each node's `state` is derived from its steps
 * (failed › running › skipped/cancelled › success) and it keeps its `steps` for click-to-expand.
 */
export function phaseChainOf(module) {
  const nodes = [];
  const byKey = new Map();
  for (const s of (module && module.steps) || []) {
    const key = s.phase || s.name; // '' phase → keyed by the step name (last-resort, never merged)
    let node = byKey.get(key);
    if (!node) {
      node = { key, phase: s.phase || '', label: phaseLabel(s.phase || s.name), steps: [], state: 'running' };
      byKey.set(key, node);
      nodes.push(node);
    }
    node.steps.push(s);
  }
  for (const node of nodes) node.state = phaseState(node.steps);
  return nodes;
}

/** Display label for a phase wire-name: capitalize the first letter ('compile' → 'Compile'). */
function phaseLabel(wire) {
  return wire ? wire.charAt(0).toUpperCase() + wire.slice(1) : '?';
}

/** A phase node's aggregate state from its steps: failed › running › skipped/cancelled › success. */
function phaseState(steps) {
  if (!steps.length) return 'running';
  if (steps.some((s) => s.state === 'failed')) return 'failed';
  if (steps.some((s) => s.state === 'running')) return 'running';
  if (steps.every((s) => s.state === 'skipped')) return 'skipped';
  if (steps.every((s) => s.state === 'skipped' || s.state === 'cancelled')) return 'cancelled';
  return 'success'; // all terminal, at least one success
}

/**
 * One line summarizing a card's module work, e.g. "3 modules · 1 failed",
 * "checked 2 modules, all up to date", "built 1 · checked 2" — '' when nothing to say.
 */
export function moduleSummary(card) {
  const n = card.modules.length;
  if (n === 0) return '';
  const failed = card.modules.filter((m) => m.state === 'failed').length;
  const checked = card.modules.filter((m) => m.state === 'checked').length;
  const built = card.modules.filter((m) => m.state === 'success').length;
  const noun = (k) => (k === 1 ? 'module' : 'modules');
  if (failed > 0) return `${n} ${noun(n)} · ${failed} failed`;
  if (built === 0 && checked > 0) return `checked ${checked} ${noun(checked)}, all up to date`;
  if (built > 0 && checked > 0) return `built ${built} · checked ${checked}`;
  return `${n} ${noun(n)}`;
}

/**
 * Find the card for an SSE payload. Live cards key on the numeric engine {@code requestId}.
 * After a hard refresh mid-build the journal seeds a stub with id {@code h:<historyId>} — rebind
 * that stub to the live requestId on the first matching event so progress/ETA/finish attach.
 */
function resolveCard(cards, d) {
  const requestId = d && d.requestId;
  if (requestId == null) return null;
  const exact = cards.find((c) => c.id === requestId);
  if (exact) return exact;

  const stubs = cards.filter(
    (c) => c.state === 'running' && typeof c.id === 'string' && String(c.id).startsWith('h:'),
  );
  if (stubs.length === 0) return null;

  // Prefer durable buildNumber when the event carries it (request-start rehydrate).
  if (d.buildNumber) {
    const byNum = stubs.find((c) => c.buildNumber === d.buildNumber && (!d.dir || c.dir === d.dir));
    if (byNum) {
      byNum.id = requestId;
      if (d.coord) byNum.coord = d.coord;
      return byNum;
    }
  }

  const eventDir = d.dir || '';
  // Workspace-root match (workspace-progress, request-finish) or module under the workspace.
  const byDir = stubs.find((c) => {
    if (!c.dir) return false;
    if (c.dir === eventDir) return true;
    if (eventDir && (eventDir.startsWith(c.dir + '/') || c.dir.startsWith(eventDir + '/'))) return true;
    return false;
  });
  if (byDir) {
    byDir.id = requestId;
    return byDir;
  }

  // Sole in-flight history stub — safe rebind when only one build is active.
  if (stubs.length === 1) {
    stubs[0].id = requestId;
    return stubs[0];
  }
  return null;
}

/** @deprecated use {@link resolveCard} — kept name for any external callers. */
function byId(cards, requestId) {
  return resolveCard(cards, { requestId });
}

/**
 * The card's row for a module dir, created on first sight. A single-plan (single-project) build
 * emits its step/plan events under the empty SINGLE_GOAL_DIR, so it gets exactly one row keyed by
 * `''`. Each row owns its step chain (`steps`). {@code at} is the event receipt time — bumps
 * {@code lastActivity} so the UI can float active modules (CLI newest-at-top).
 */
function moduleRow(card, dir, at) {
  const key = dir || '';
  let row = card.modules.find((m) => m.dir === key);
  if (!row) {
    row = {
      dir: key,
      coord: null,
      state: 'running',
      millis: null,
      steps: [],
      diagnostics: [],
      lastActivity: at ?? 0,
    };
    card.modules.push(row);
  } else if (at != null) {
    row.lastActivity = at;
  }
  return row;
}

/**
 * The chain entry for a step name WITHIN its module (keyed by the event's dir), created in arrival
 * order. Unlike the old global-by-name folding, each module keeps its own chain, so the dashboard
 * shows one lock→compile→test→build strip per module. `phase` (the step's plan phase wire-name,
 * or '' when unset) rides the row so the UI can render the phase/step hierarchy.
 */
function stepRow(card, dir, step, phase, at) {
  const mod = moduleRow(card, dir, at);
  let row = mod.steps.find((p) => p.name === step);
  if (!row) {
    row = { name: step || '?', state: 'running', phase: phase || '', message: '' };
    mod.steps.push(row);
  } else if (phase && !row.phase) {
    row.phase = phase; // a later event carried the phase the first one omitted
  }
  return row;
}

/**
 * Display order for multi-module cards (CLI tree parity): running first (most recently active
 * first), then failed, then finished (success / checked / cancelled) at the bottom.
 */
export function orderedModules(modules) {
  return (modules || []).slice().sort((a, b) => {
    const ra = moduleOrderRank(a);
    const rb = moduleOrderRank(b);
    if (ra !== rb) return ra - rb;
    return (b.lastActivity || 0) - (a.lastActivity || 0);
  });
}

/** 0 = active, 1 = failed, 2 = finished. */
function moduleOrderRank(m) {
  if (!m) return 2;
  if (m.state === 'running') return 0;
  if (m.state === 'failed' || (m.diagnostics && m.diagnostics.length > 0)) return 1;
  return 2;
}

/** Engine StepStatus → chain-node state. */
function stepState(status) {
  switch (status) {
    case 'SUCCESS':
      return 'success';
    case 'FAIL':
      return 'failed';
    case 'CANCELLED':
      return 'cancelled';
    case 'SKIPPED':
      return 'skipped';
    default:
      return 'running';
  }
}

/**
 * Live detail after the running phase node (CLI tree-row parity). Strips a leading
 * {@code module :: } prefix when the engine embeds the coordinate in test labels.
 */
export function detailForDisplay(module, message) {
  if (message == null || message === '') return '';
  let msg = String(message).trim();
  const mod = module == null ? '' : String(module).trim();
  if (mod && msg.startsWith(mod + ' :: ')) {
    msg = msg.slice(mod.length + 4).trim();
  }
  return msg;
}

/**
 * The live message of the rightmost running phase's running step (or ''), after
 * {@link detailForDisplay}. {@code module} is the coord used to strip redundant prefixes.
 */
export function liveStepDetail(module, steps) {
  const phases = phaseChainOf({ steps: steps || [] });
  for (let i = phases.length - 1; i >= 0; i--) {
    const p = phases[i];
    if (p.state !== 'running') continue;
    for (let j = p.steps.length - 1; j >= 0; j--) {
      const s = p.steps[j];
      if (s.state === 'running' && s.message) {
        return detailForDisplay(module, s.message);
      }
    }
  }
  return '';
}

/** {@code FooTest}, {@code FooTest.bar()}, or {@code FooTest.bar(Path)} — not free prose. */
const JAVA_MEMBER = /^[A-Z][\w$]*(?:\.[A-Za-z_][\w$]*(?:\([^)]*\))?)?$/;

export function looksLikeJavaMember(s) {
  return !!(s && JAVA_MEMBER.test(s));
}

/**
 * Color segments for a live step detail (CLI {@code colorDetail} roles).
 * Each segment is {@code { text, cls }} with cls in:
 * {@code det-type | det-fn | det-num | det-path | det-coord | det-mid | det-dim}.
 */
export function detailSegments(detail) {
  if (detail == null || detail === '') return [];
  let body = String(detail);
  let worker = '';
  // progressLabel appends "  [w2]" — keep it outside the Java highlighter.
  const w = body.lastIndexOf('  [w');
  if (w > 0 && body.endsWith(']')) {
    worker = body.slice(w);
    body = body.slice(0, w);
  }
  const segs = looksLikeJavaMember(body) ? javaMemberSegments(body) : proseSegments(body);
  if (worker) segs.push({ text: worker, cls: 'det-mid' });
  return segs;
}

/** Capitalized id → type; lower id before `(` → function; else mid-gray. */
function javaMemberSegments(s) {
  const segs = [];
  let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (/[A-Z]/.test(c)) {
      let j = i + 1;
      while (j < s.length && /[\w$]/.test(s[j])) j++;
      segs.push({ text: s.slice(i, j), cls: 'det-type' });
      i = j;
      continue;
    }
    if (/[a-z_]/.test(c)) {
      let j = i + 1;
      while (j < s.length && /[\w$]/.test(s[j])) j++;
      let k = j;
      while (k < s.length && s[k] === ' ') k++;
      const cls = k < s.length && s[k] === '(' ? 'det-fn' : 'det-mid';
      segs.push({ text: s.slice(i, j), cls });
      i = j;
      continue;
    }
    segs.push({ text: c, cls: 'det-mid' });
    i++;
  }
  return segs;
}

/**
 * Prose detail: mid-gray body with numbers, path-like tokens, and g:a coords picked out
 * (simplified CLI {@code colorProseDetail}).
 */
function proseSegments(text) {
  const segs = [];
  const re =
    /(\b\d+(?:\.\d+)?\b)|((?:~\/|\/|\.\/|\.\.\/)[\w./+\-]+|[\w.-]+\.(?:jar|war|ear|zip|class|kt|java|groovy)\b)|(\b[\w.-]+:[\w.-]+(?::[\w.-]+)?\b)|([^\s]+)|(\s+)/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    if (m[1] != null) segs.push({ text: m[1], cls: 'det-num' });
    else if (m[2] != null) segs.push({ text: m[2], cls: 'det-path' });
    else if (m[3] != null && m[3].includes(':')) segs.push({ text: m[3], cls: 'det-coord' });
    else if (m[4] != null) segs.push({ text: m[4], cls: 'det-mid' });
    else if (m[5] != null) segs.push({ text: m[5], cls: 'det-mid' });
  }
  return segs;
}
