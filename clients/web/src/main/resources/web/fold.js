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
/**
 * Latch the card's client-epoch start anchor. `startedAt` is engine wall clock; comparing it
 * with the browser clock on a skewed remote dashboard shifts elapsed/bar/deadline by the skew
 * (JK-1839). When a frame carries the engine's own `serverNow`, elapsed = serverNow - startedAt
 * is skew-free and receipt time converts it to the client epoch. Earliest wins, like startedAt.
 */
function noteStartAnchor(card, d, at) {
  if (typeof d.startedAt !== 'number' || d.startedAt <= 0 || at == null) return;
  if (typeof d.serverNow !== 'number' || d.serverNow < d.startedAt) return;
  const clientStart = at - (d.serverNow - d.startedAt);
  if (card.startedAtClient == null || clientStart < card.startedAtClient) {
    card.startedAtClient = clientStart;
  }
}

/** Client-epoch start for elapsed math — engine-epoch startedAt is only a last resort. */
export function startAnchor(card) {
  return card.startedAtClient ?? card.startedAt ?? null;
}

export function foldEvent(cards, event) {
  const d = event.data || {};
  switch (event.type) {
    case 'request-start': {
      // Engine startedAt (admission) beats client receipt time — late join / rehydrate must match TUI.
      const engineStart =
        typeof d.startedAt === 'number' && d.startedAt > 0 ? d.startedAt : null;
      // Already attached (SSE connect rehydrate replayed, or this tab started the job).
      const attached = cards.find((c) => c.id === d.requestId);
      if (attached) {
        if (engineStart != null && (attached.startedAt == null || engineStart < attached.startedAt)) {
          attached.startedAt = engineStart;
        }
        noteStartAnchor(attached, d, event.at);
        if (attached.startedAtClient == null && event.at != null) attached.startedAtClient = event.at;
        if (d.coord) attached.coord = d.coord;
        if (d.projectId) attached.projectId = d.projectId;
        if (d.buildNumber) attached.buildNumber = d.buildNumber;
        if (typeof d.progress === 'number') {
          attached.progressPercent = d.progress;
          if (typeof attached.peakPct !== 'number' || d.progress > attached.peakPct) {
            attached.peakPct = Math.min(99, Math.round(d.progress));
          }
        }
        break;
      }
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
        if (d.projectId) existing.projectId = d.projectId;
        if (engineStart != null && (existing.startedAt == null || engineStart < existing.startedAt)) {
          existing.startedAt = engineStart;
        }
        noteStartAnchor(existing, d, event.at);
        if (typeof d.progress === 'number') {
          existing.progressPercent = d.progress;
          if (typeof existing.peakPct !== 'number' || d.progress > existing.peakPct) {
            existing.peakPct = Math.min(99, Math.round(d.progress));
          }
        }
        break;
      }
      cards.unshift({
        id: d.requestId,
        kind: d.kind || 'request',
        dir: d.dir || '',
        coord: d.coord || null,
        projectId: d.projectId || null,
        buildNumber: d.buildNumber || null,
        state: 'running',
        startedAt: engineStart ?? event.at ?? null,
        // Client-epoch anchor (JK-1839): skew-corrected when serverNow rides the frame, else a
        // live request-start's receipt time is the admission instant to within transit latency.
        startedAtClient:
          engineStart != null && typeof d.serverNow === 'number' && d.serverNow >= engineStart
            ? (event.at ?? Date.now()) - (d.serverNow - engineStart)
            : event.at ?? null,
        finishedAt: null,
        millis: null,
        cancelled: false,
        success: null, // tri-state: null = engine didn't say (socket requests) — derive from modules
        // Every build is a list of module rows (a single-project build has one, keyed by the empty
        // SINGLE_PLAN_DIR); each row carries its OWN step chain, so the card shows a chain per
        // module rather than one merged strip.
        modules: [],
        // Fine-grained per-module plan ticks (detail only). Request-level bar uses
        // progressPercent from engine workspace-progress (JK-1120) — dumb client, no re-sum.
        mods: {},
        planWeight: 0,
        progressPercent: typeof d.progress === 'number' ? d.progress : null,
        progressNum: 0,
        progressDen: 0,
        peakPct: typeof d.progress === 'number' ? Math.min(99, Math.round(d.progress)) : undefined,
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
        const row = stepRow(card, d.dir, (d.task || d.step), d.stage, event.at);
        row.state = 'running';
        row.message = ''; // new step — clear previous tick text
        row.startedAt = event.at ?? row.startedAt ?? null; // wall receipt for duration fallback
      }
      break;
    }
    case 'task-finish': {
      const card = resolveCard(cards, d);
      if (card) {
        const row = stepRow(card, d.dir, (d.task || d.step), d.stage, event.at);
        // Engine carries millis (additive); duration_ms is the CLI jsonl alias; else receipt delta.
        row.millis = stepMillisOf(d, row, event.at);
        row.state = stepState(d.status, row.millis);
        // Keep last message for a moment of context only while running rows use it; finished
        // phases do not surface live detail.
      }
      break;
    }
    case 'label': {
      // Live step detail (test class.method, "shrinking jar", …) — CLI tree-row parity.
      const card = resolveCard(cards, d);
      if (card) stepRow(card, d.dir, (d.task || d.step), d.stage, event.at).message = d.label || '';
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
        if (typeof d.progress === 'number') {
          card.progressPercent = d.progress;
          // Floor the painted bar at the engine's weighted % so a late-join clock seed cannot
          // flash 0% when the build is already mid-flight (then clock/adaptive climb from there).
          const floor = Math.min(99, Math.round(d.progress));
          if (typeof card.peakPct !== 'number' || floor > card.peakPct) card.peakPct = floor;
        } else if (card.progressDen > 0) {
          card.progressPercent = Math.min(100, Math.round((100 * card.progressNum) / card.progressDen));
        }
        // Residual RemainingWork: adaptive bar + countdown re-anchor. Prefer CURRENT remainingMs
        // for first seed (reconnect/late join — JK-1820): seeding from original R0 restarted a
        // full-length countdown mid-build. A fresh run's first snapshot has remainingMs == R0.
        const rem = typeof d.remainingMs === 'number' && d.remainingMs >= 0 ? d.remainingMs : null;
        if (rem != null) {
          card.residualRemainingMs = rem;
          card.residualAt = event.at ?? Date.now();
        }
        if (card.r0Ms == null) {
          if (rem != null) {
            // remainingMs 0 = effectively done — leave unseeded rather than count down R0.
            if (rem > 0) {
              card.r0Ms = rem;
              // Anchor R0 to engine start when known so clock progress = elapsed/(elapsed+remaining)
              // matches the TUI after a mid-build join (not "since this tab connected").
              card.r0At = startAnchor(card) ?? event.at ?? Date.now();
            }
          } else if (typeof d.R0 === 'number' && d.R0 > 0) {
            card.r0Ms = d.R0;
            card.r0At = startAnchor(card) ?? event.at ?? Date.now();
          }
        }
      }
      break;
    }
    case 'eta': {
      const card = resolveCard(cards, d);
      if (card && typeof d.millis === 'number') {
        // Always record remaining@emission for etaTotalMillis (JK-1517 re-projections).
        card.etaMillis = d.millis;
        card.etaAt = event.at ?? null;
        // Seed R0 — and, matching the CLI's "positive re-seeds allowed pre-execute" rule, let a
        // later eta REPLACE a provisional seed until any module work has folded: a contended
        // build's coarse lock+prior figure otherwise stayed R0 for the whole run and the
        // R0-fallback bar paced against the wrong total (JK-1854). Mid-run, residual re-anchors
        // via residualRemainingMs/residualAt and R0 stays frozen.
        const preExecute = card.modules.length === 0 && !(card.progressNum > 0);
        if (d.millis > 0 && (card.r0Ms == null || preExecute)) {
          card.r0Ms = d.millis;
          card.r0At = startAnchor(card) ?? event.at ?? Date.now();
          if (card.residualRemainingMs == null || preExecute) {
            card.residualRemainingMs = d.millis;
            card.residualAt = event.at ?? Date.now();
          }
        }
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
          mod.diagnostics.push(normalizeDiagnostic(d));
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
        if (d.projectId && !card.projectId) card.projectId = d.projectId;
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
    case 'run-snapshot': {
      // One compact mid-flight catch-up frame (SSE connect). Prefer this over N task events so
      // live workspace-progress/eta are never stuck behind a phase-replay backlog.
      applyRunSnapshot(cards, d, event.at);
      break;
    }
    default:
      break; // unknown event types are future vocabulary, never an error
  }
  return cards;
}

/**
 * Atomically apply an engine mid-flight snapshot: identity, progress/ETA anchors, and phase
 * chains. Idempotent with history seed and subsequent live events.
 */
function applyRunSnapshot(cards, d, at) {
  if (!d || d.requestId == null) return;
  // A snapshot captured while the run was still live can arrive after the finish frame in a
  // reconnect race — it must never resurrect a finished card as running (JK-1837).
  const pre = cards.find((c) => c.id === d.requestId);
  if (pre && pre.state !== 'running') return;
  // Ensure a running card exists (same paths as request-start rehydrate).
  foldEvent(cards, {
    type: 'request-start',
    data: {
      requestId: d.requestId,
      jid: d.jid ?? d.requestId,
      kind: d.kind,
      dir: d.dir,
      coord: d.coord,
      projectId: d.projectId,
      buildNumber: d.buildNumber,
      startedAt: d.startedAt,
      progress: d.progress,
    },
    at,
  });
  const card = resolveCard(cards, d);
  if (!card) return;
  // The engine sends the journal id — apply it so dedupe/delete reconciliation works even for
  // runs without a buildNumber (e.g. lock jobs), instead of waiting for a history GET (JK-1846).
  if (typeof d.historyId === 'string' && d.historyId && !card.historyId) card.historyId = d.historyId;
  if (typeof d.startedAt === 'number' && d.startedAt > 0) {
    if (card.startedAt == null || d.startedAt < card.startedAt) card.startedAt = d.startedAt;
  }
  noteStartAnchor(card, d, at);
  if (typeof d.progress === 'number') {
    card.progressPercent = d.progress;
    const floor = Math.min(99, Math.round(d.progress));
    if (typeof card.peakPct !== 'number' || floor > card.peakPct) card.peakPct = floor;
  }
  if (typeof d.numerator === 'number') card.progressNum = d.numerator;
  if (typeof d.denominator === 'number' && d.denominator > 0) card.progressDen = d.denominator;
  applyLiveEtaFields(card, d);
  // Take the snapshot's phase chains when present — they are the engine's current truth for
  // chains and progress (history stub is empty; live card may still be empty if this is the
  // first frame) — but MERGE into existing rows: the snapshot never carries diagnostics or
  // didWork, which are published exactly once as live events, so a reconnect replace would
  // lose them for the rest of the run (JK-1834).
  const mods = historyModules({
    running: true,
    dir: d.dir || card.dir || '',
    coord: d.coord || card.coord,
    cancelled: false,
    success: false,
    startedAt: card.startedAt,
    modules: d.modules,
    tasks: d.tasks,
    steps: d.steps,
    diagnostics: d.diagnostics,
  });
  if (mods.length > 0) card.modules = mergeSnapshotModules(card.modules, mods);
}

/**
 * Merge snapshot module rows into a card's existing rows. Snapshot wins on chains/progress;
 * live-only facts survive: diagnostics, didWork/checked, an already-reported failure, step
 * messages, and rows only the live stream knows (e.g. the "" output bucket).
 */
function mergeSnapshotModules(existing, snapshot) {
  if (!existing || existing.length === 0) return snapshot;
  const byDir = new Map(existing.map((m) => [m.dir, m]));
  const merged = snapshot.map((next) => {
    const prev = byDir.get(next.dir);
    if (!prev) return next;
    byDir.delete(next.dir);
    if ((!next.diagnostics || next.diagnostics.length === 0) && prev.diagnostics && prev.diagnostics.length > 0) {
      next.diagnostics = prev.diagnostics;
    }
    if (prev.didWork !== undefined) {
      next.didWork = prev.didWork;
      if (next.state === 'success' && prev.didWork === false) next.state = 'checked';
    }
    // A failure the live stream already reported (module-finish success=false) outranks a
    // snapshot that cannot see module-level failures without a FAIL task.
    if (prev.state === 'failed' && next.state !== 'failed') next.state = 'failed';
    if ((next.steps || []).length > 0 && (prev.steps || []).length > 0) {
      const prevSteps = new Map(prev.steps.map((s) => [s.name, s]));
      for (const s of next.steps) {
        const ps = prevSteps.get(s.name);
        if (ps && !s.message && ps.message) s.message = ps.message;
      }
    }
    if (typeof prev.lastActivity === 'number' && prev.lastActivity > next.lastActivity) {
      next.lastActivity = prev.lastActivity;
    }
    return next;
  });
  for (const leftover of byDir.values()) merged.push(leftover);
  return merged;
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
  const anchor = startAnchor(card);
  if (card.etaAt != null && anchor != null) {
    return card.etaAt - anchor + card.etaMillis;
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
      if (rec.projectId && !live.projectId) live.projectId = rec.projectId;
      if (rec.running) live.state = 'running';
      else if (live.state === 'running') {
        // The journal says this run is over: a finish frame lost to a connect/reconnect race
        // (JK-1837) must not leave the card spinning forever — history is the durable truth.
        live.state = 'finished';
        live.finishedAt = rec.finishedAt || live.finishedAt || null;
        live.millis = rec.millis ?? live.millis;
        live.cancelled = !!rec.cancelled;
        if (typeof rec.success === 'boolean') live.success = rec.success;
        live.output = [];
        live.etaMillis = null;
        const finals = historyModules(rec);
        if (finals.length > 0) live.modules = mergeSnapshotModules(live.modules, finals);
      }
      // Enriched history may carry the engine requestId — rebind a journal stub for SSE.
      const liveId = rec.requestId ?? rec.jid;
      if (rec.running && typeof liveId === 'number' && liveId > 0) live.id = liveId;
      // Prefer engine admission time over browser receipt of a late request-start.
      if (typeof rec.startedAt === 'number' && rec.startedAt > 0) {
        if (live.startedAt == null || rec.startedAt < live.startedAt) live.startedAt = rec.startedAt;
      }
      noteStartAnchor(live, rec, Date.now());
      if (typeof rec.progress === 'number') {
        live.progressPercent = rec.progress;
        const floor = Math.min(99, Math.round(rec.progress));
        if (typeof live.peakPct !== 'number' || floor > live.peakPct) live.peakPct = floor;
      }
      if (typeof rec.numerator === 'number') live.progressNum = rec.numerator;
      if (typeof rec.denominator === 'number' && rec.denominator > 0) live.progressDen = rec.denominator;
      applyLiveEtaFields(live, rec);
      // Enriched mid-flight modules/tasks fill empty phase chains (journal stub is empty until complete).
      if (rec.running && (!live.modules || live.modules.length === 0)) {
        const mods = historyModules(rec);
        if (mods.length > 0) live.modules = mods;
      }
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
  const card = {
    id,
    historyId: rec.id,
    buildNumber: rec.buildNumber || null,
    kind: rec.kind || 'build',
    dir: rec.dir || '',
    coord: rec.coord || null,
    projectId: rec.projectId || null,
    state: running ? 'running' : 'finished',
    startedAt: rec.startedAt ?? null,
    startedAtClient: null,
    finishedAt: running ? null : rec.finishedAt ?? null,
    millis: running ? null : rec.millis ?? null,
    cancelled: !!rec.cancelled,
    success: running ? null : typeof rec.success === 'boolean' ? rec.success : null,
    modules: historyModules(rec),
    output: [],
    mods: {},
    planWeight: 0,
    progressPercent,
    progressNum: typeof rec.numerator === 'number' ? rec.numerator : 0,
    progressDen: typeof rec.denominator === 'number' ? rec.denominator : 0,
    peakPct: typeof progressPercent === 'number' ? Math.min(99, Math.round(progressPercent)) : undefined,
    etaMillis: typeof rec.etaMillis === 'number' ? rec.etaMillis : null,
    etaAt: null,
    io: rec.io ? normalizeIo(rec.io) : null,
  };
  if (running) applyLiveEtaFields(card, rec);
  return card;
}

/**
 * Apply engine residual / R0 fields from an enriched history row (or equivalent) so countdown and
 * clock progress match a tab that watched from request-start. {@code remainingMs} is current as of
 * this response — stamp residualAt to now so deadline = elapsed + remaining.
 */
function applyLiveEtaFields(card, rec) {
  if (!card || !rec) return;
  const rem = typeof rec.remainingMs === 'number' && rec.remainingMs >= 0 ? rec.remainingMs : null;
  const now = Date.now();
  // Latch the client-epoch anchor when the payload carries serverNow (no-op otherwise) so the
  // r0At seeds below land in the client epoch (JK-1839).
  noteStartAnchor(card, rec, now);
  if (rem != null) {
    card.residualRemainingMs = rem;
    card.residualAt = now;
  }
  if (card.r0Ms == null) {
    if (typeof rec.R0 === 'number' && rec.R0 > 0) {
      card.r0Ms = rec.R0;
      card.r0At = startAnchor(card) ?? now;
    } else if (rem != null && rem > 0 && startAnchor(card) != null) {
      // No original R0 on the wire: synthesize total ≈ elapsed + remaining so auto clock mode
      // engages and paints elapsed/(elapsed+remaining) instead of a 0% late-join flash.
      card.r0Ms = Math.max(rem, now - startAnchor(card) + rem);
      card.r0At = startAnchor(card);
    } else if (rem != null && rem > 0) {
      card.r0Ms = rem;
      card.r0At = now;
    }
  }
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
    .map((d) => normalizeDiagnostic(d));
}

/** Normalize a wire/journal diagnostic into the client shape (incl. test-failure enrichment). */
export function normalizeDiagnostic(d) {
  const snippet = Array.isArray(d.snippet)
    ? d.snippet.map((s) => String(s))
    : [];
  return {
    step: d.task || d.step || '',
    code: d.code || '',
    message: d.message || '',
    test: d.test || '',
    exceptionClass: d.exceptionClass || '',
    module: d.module || '',
    engine: d.engine || '',
    className: d.class || d.className || '',
    method: d.method || '',
    stack: d.stack || (d.throwable && d.throwable.stack) || '',
    file: d.file || '',
    line: typeof d.line === 'number' ? d.line : 0,
    snippetStart: typeof d.snippetStart === 'number' ? d.snippetStart : 0,
    snippet,
    worker: typeof d.worker === 'number' ? d.worker : 0,
  };
}

/**
 * Module rows for a persisted record, matching the live card shape (each with its own step chain).
 * A workspace record has `modules[]` each carrying `steps`; a single-project record has no modules
 * and its steps at the top level — synthesize one row from them so backfilled cards match live.
 * In-flight enriched rows may include {@code RUN} tasks and unfinished modules (no success yet).
 */
function historyModules(rec) {
  const running = !!rec.running;
  const toSteps = (ps) =>
    (ps || []).map((p) => {
      // Absent or negative millis = unknown duration (renders plain); 0 is the true-no-op
      // signal that renders dashed (JK-1855 — the journal no longer stamps unknown as 0).
      const millis = typeof p.millis === 'number' && p.millis >= 0 ? p.millis : null;
      return {
        name: p.name || '?',
        state: stepState(p.status, millis),
        phase: p.stage || p.group || p.phase || '',
        
        millis,
        message: '',
      };
    });
  // finishedAt / startedAt give a stable lastActivity for display order after backfill.
  const activity = rec.finishedAt || rec.startedAt || 0;
  if ((rec.modules || []).length > 0) {
    return rec.modules.map((m, i) => {
      const steps = toSteps(m.tasks || m.steps);
      let state;
      if (typeof m.finished === 'boolean') {
        // Engine's explicit lifecycle bit (JK-1846): success=false alone was ambiguous between
        // "still running" and "failed", and a module-level failure with no FAIL task was
        // misclassified as running by the status-guessing below.
        state = !m.finished
          ? steps.some((s) => s.state === 'failed')
            ? 'failed'
            : 'running'
          : m.success
            ? m.didWork === false
              ? 'checked'
              : 'success'
            : 'failed';
      } else if (running && !m.success && steps.some((s) => s.state === 'running')) {
        state = 'running';
      } else if (running && !m.success && steps.length > 0 && !steps.every((s) => s.state === 'failed' || s.state === 'cancelled')) {
        // Enriched mid-flight module: finished steps only so far, still in progress.
        state = steps.some((s) => s.state === 'failed') ? 'failed' : 'running';
      } else if (m.success) {
        state = 'success';
      } else if (steps.some((s) => s.state === 'failed')) {
        state = 'failed';
      } else if (rec.cancelled || steps.some((s) => s.state === 'cancelled')) {
        state = 'cancelled';
      } else if (running) {
        state = 'running';
      } else {
        state = 'failed';
      }
      const row = {
        dir: m.dir || '',
        coord: m.coord || null,
        state,
        millis: m.millis ?? null,
        steps,
        diagnostics: historyDiags(rec.diagnostics, m.dir || ''),
        // Preserve journal order as a tie-break (later modules slightly higher lastActivity).
        lastActivity: activity + i,
      };
      // Carry the engine's cache-check bit so checked rendering survives merges (JK-1834/1846).
      if (typeof m.didWork === 'boolean') row.didWork = m.didWork;
      return row;
    });
  }
  // Single-project: no modules, steps at top level. Its diagnostics live in the "" bucket, so take
  // every error the record carries (there is only one module to own them).
  const steps = toSteps(rec.tasks || rec.steps);
  if (steps.length === 0 && running) return []; // empty stub — wait for SSE / rehydrate phases
  let state;
  if (running) {
    // Any failed step marks the run; otherwise it is running (the zero-step stub returned above).
    state = steps.some((s) => s.state === 'failed') ? 'failed' : 'running';
  } else {
    state = steps.some((s) => s.state === 'failed')
      ? 'failed'
      : rec.cancelled
        ? 'cancelled'
        : rec.success === false
          ? 'failed'
          : 'success';
  }
  return [{
    // Live single-plan events use the empty SINGLE_PLAN_DIR; keep mid-flight seeds on the same
    // key so SSE rehydrate does not open a second module row next to a history-seeded chain.
    dir: running ? '' : (rec.dir || ''),
    coord: rec.coord || null,
    state,
    millis: rec.millis ?? null,
    steps,
    diagnostics: (rec.diagnostics || [])
      .filter((d) => d.severity !== 'warning')
      .map((d) => normalizeDiagnostic(d)),
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
  // Idle bookkeeping only (explicit 0ms success + skips): paint the phase as skipped so
  // Compile/Generate with a skipped compile-java and a 0ms write-stamp is not solid "success".
  // Missing millis is not treated as idle (history/tests often omit duration).
  // (No 'checked' alternative here: stepState never yields it — checked is a MODULE state from
  // module-finish didWork=false; the step-level clause was dead, JK-1858.)
  if (steps.every((s) => s.state === 'skipped' || (s.state === 'success' && s.millis === 0))) {
    return 'skipped';
  }
  return 'success'; // all terminal, at least one success with real wall-clock
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
 * emits its step/plan events under the empty SINGLE_PLAN_DIR, so it gets exactly one row keyed by
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
    row = {
      name: step || '?',
      state: 'running',
      phase: phase || '',
      message: '',
      millis: null,
      startedAt: at ?? null,
    };
    mod.steps.push(row);
  } else if (phase && !row.phase) {
    row.phase = phase; // a later event carried the phase the first one omitted
  }
  return row;
}

/**
 * Step wall-clock from a finish payload: prefer engine {@code millis}, then CLI {@code duration_ms},
 * else client receipt delta from {@code task-start} (best-effort for older engines).
 */
function stepMillisOf(d, row, at) {
  if (typeof d.millis === 'number' && Number.isFinite(d.millis)) return Math.max(0, d.millis);
  if (typeof d.duration_ms === 'number' && Number.isFinite(d.duration_ms)) {
    return Math.max(0, d.duration_ms);
  }
  if (row && row.startedAt != null && at != null && Number.isFinite(at) && Number.isFinite(row.startedAt)) {
    return Math.max(0, at - row.startedAt);
  }
  return row && row.millis != null ? row.millis : null;
}

/**
 * Compact duration for phase/step tooltips: {@code 360ms}, {@code 1.2s}, {@code 1m 5s}.
 * Slightly tighter than the card-level {@code duration()} (no space before the unit).
 */
export function fmtStepMillis(millis) {
  if (millis == null || !Number.isFinite(millis)) return '';
  const ms = Math.max(0, Math.round(millis));
  if (ms < 1000) return ms + 'ms';
  if (ms < 60_000) {
    const s = ms / 1000;
    // One decimal under 10s ("1.2s"), whole seconds from there ("12s").
    return (s < 10 ? s.toFixed(1) : String(Math.round(s))) + 's';
  }
  const totalSec = Math.floor(ms / 1000);
  return Math.floor(totalSec / 60) + 'm ' + (totalSec % 60) + 's';
}

/**
 * One step's tooltip fragment: {@code compile-tests (212ms)}, or just the name while still running.
 */
export function stepTimingLabel(step) {
  if (!step || !step.name) return '';
  const t = fmtStepMillis(step.millis);
  return t ? step.name + ' (' + t + ')' : step.name;
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

/**
 * Engine StepStatus → chain-node state. {@code millis === 0} SUCCESS is painted skipped: pure
 * no-ops (stamp unchanged, empty generate, ensure-jdk already present) often still land as
 * SUCCESS when older engines omit {@code ctx.cached()}; 0ms wall matches the skip mental model.
 */
function stepState(status, millis) {
  switch (status) {
    case 'SUCCESS':
    case 'success':
      if (millis === 0) return 'skipped';
      return 'success';
    case 'FAIL':
    case 'failed':
      return 'failed';
    case 'CANCELLED':
    case 'cancelled':
      return 'cancelled';
    case 'SKIPPED':
    case 'skipped':
      return 'skipped';
    default:
      return 'running';
  }
}

/**
 * Live detail after the running phase node (CLI tree-row parity). Strips a leading
 * {@code module :: } prefix when the engine embeds the coordinate in test labels, and
 * shortens any package FQCNs so the UI never paints wire-shaped type names.
 */
export function detailForDisplay(module, message) {
  if (message == null || message === '') return '';
  let msg = String(message).trim();
  const mod = module == null ? '' : String(module).trim();
  if (mod && msg.startsWith(mod + ' :: ')) {
    msg = msg.slice(mod.length + 4).trim();
  }
  return shortDisplayLabel(msg);
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
 * FQCNs in the text are shortened before segmentation.
 */
export function detailSegments(detail) {
  if (detail == null || detail === '') return [];
  let body = shortDisplayLabel(String(detail));
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

// ---- test-failure report (CLI TestFailureHighlight parity, no thick rail) ----

/** Simple class name from FQCN. */
export function simpleTypeName(fqcn) {
  if (!fqcn) return '';
  const s = String(fqcn);
  const d = s.lastIndexOf('.');
  return d >= 0 ? s.slice(d + 1) : s;
}

/**
 * Human-facing member label: drop package FQCNs so the UI never paints wire-shaped names.
 * {@code cc.jumpkick.FooTest.bar(java.nio.file.Path)} → {@code FooTest.bar(Path)}.
 * Preserves a trailing {@code  [wN]} worker tag when present. Leaves ordinary prose, versions,
 * and jar names untouched.
 */
export function shortDisplayLabel(raw) {
  if (raw == null || raw === '') return raw == null ? '' : raw;
  let worker = '';
  let body = String(raw).trim();
  const w = body.lastIndexOf('  [w');
  if (w > 0 && body.endsWith(']')) {
    worker = body.slice(w);
    body = body.slice(0, w).trim();
  }
  if (!looksLikeJavaishLabel(body)) return worker ? body + worker : body;
  body = simplifyMethodParams(body);
  const paren = body.indexOf('(');
  const searchEnd = paren >= 0 ? paren : body.length;
  const dot = body.lastIndexOf('.', searchEnd - 1);
  if (dot > 0 && dot < body.length - 1) {
    const after = body.slice(dot + 1, searchEnd);
    if (after && (/^[a-z_]/.test(after) || after.startsWith('<'))) {
      const cls = simpleTypeName(body.slice(0, dot));
      const method = simplifyMethodParams(body.slice(dot + 1));
      body = cls ? cls + '.' + method : method;
    } else {
      body = simpleTypeName(body.slice(0, searchEnd)) + body.slice(searchEnd);
    }
  }
  return worker ? body + worker : body;
}

/** True for Java member / type labels we may shorten — not versions, jar names, or free prose. */
export function looksLikeJavaishLabel(body) {
  if (!body) return false;
  // Spaces only allowed inside a trailing param list: Foo.bar(A, B).
  const open = body.indexOf('(');
  const head = open >= 0 ? body.slice(0, open) : body;
  if (head.indexOf(' ') >= 0) return false;
  if (open >= 0) {
    const close = body.lastIndexOf(')');
    if (close < open) return false;
    if (close + 1 < body.length && body.slice(close + 1).indexOf(' ') >= 0) return false;
  }
  const c0 = body[0];
  if (!/[A-Za-z_$]/.test(c0)) return false;
  if (open >= 0) {
    return body.indexOf('.') >= 0 || /[A-Z]/.test(c0);
  }
  if (body.indexOf('.') < 0) return /[A-Z]/.test(c0);
  return /^(?:[a-z][\w$]*\.)*[A-Z][\w$]*(?:\.[A-Za-z_][\w$]*)?$/.test(body);
}

/** {@code SimpleClass.method()} / {@code SimpleClass.method(Path)} — package stripped, params simplified. */
export function shortTestLabel(d) {
  let cls = simpleTypeName(d.className || d.class || '');
  let method = (d.method || d.test || '').trim();
  const gt = method.lastIndexOf(' > ');
  if (gt >= 0) method = method.slice(gt + 3).trim();
  method = simplifyMethodParams(method);
  // method may still look like Class.method / pkg.Class.method — peel the class segment.
  const paren = method.indexOf('(');
  let dot = method.lastIndexOf('.');
  if (paren >= 0 && dot > paren) dot = method.lastIndexOf('.', paren);
  if (dot > 0 && dot < method.length - 1) {
    const after = method.slice(dot + 1, paren >= 0 ? paren : method.length);
    if (after && (/^[a-z_]/.test(after) || after.startsWith('<'))) {
      if (!cls) cls = simpleTypeName(method.slice(0, dot));
      method = method.slice(dot + 1);
    }
  }
  method = simplifyMethodParams(method);
  if (method && method.indexOf('(') < 0 && method !== '(test run)') method += '()';
  if (!cls && !method) return shortDisplayLabel(d.test || '') || '?';
  let label = !cls ? shortDisplayLabel(method) : !method ? cls : shortDisplayLabel(cls + '.' + method);
  const worker = typeof d.worker === 'number' ? d.worker : 0;
  if (worker > 0 && !label.includes('  [w')) label = label + '  [w' + worker + ']';
  return label;
}

/** Keep {@code (…)} but strip package prefixes inside params. */
export function simplifyMethodParams(method) {
  if (!method) return '';
  const open = method.indexOf('(');
  const close = method.lastIndexOf(')');
  if (open < 0 || close <= open) return String(method).trim();
  const name = method.slice(0, open).trim();
  const inside = method.slice(open + 1, close).trim();
  if (!inside) return name + '()';
  const parts = inside.split(',').map((raw) => {
    let p = raw.trim();
    let suffix = '';
    while (p.endsWith('...') || p.endsWith('[]')) {
      if (p.endsWith('...')) {
        suffix = '...' + suffix;
        p = p.slice(0, -3).trim();
      } else {
        suffix = '[]' + suffix;
        p = p.slice(0, -2).trim();
      }
    }
    const d = p.lastIndexOf('.');
    if (d >= 0) p = p.slice(d + 1);
    return p + suffix;
  });
  return name + '(' + parts.join(', ') + ')';
}

/**
 * Parse AssertJ-style messages into { desc, expected, actual } or null.
 * Matches CLI {@code tryPaintAssertJ}.
 */
export function parseAssertJMessage(message) {
  if (!message) return null;
  let rest = String(message).trim();
  let desc = null;
  if (rest.startsWith('[')) {
    const close = rest.indexOf(']');
    if (close > 0) {
      desc = rest.slice(1, close).trim();
      rest = rest.slice(close + 1).trim();
    }
  }
  let m = rest.match(/^expected:\s*([^\n]+?)\s*\n\s*but was:\s*([^\n]+?)\s*$/i);
  if (!m) {
    m = rest.match(/^expected:\s*(.+?)\s+but was:\s*(.+?)\s*$/i);
  }
  if (!m) return null;
  return { desc, expected: stripValueQuotes(m[1].trim()), actual: stripValueQuotes(m[2].trim()) };
}

function stripValueQuotes(v) {
  if (!v) return '';
  const s = v.trim();
  if (s.length >= 2) {
    const a = s[0];
    const b = s[s.length - 1];
    if ((a === '"' && b === '"') || (a === "'" && b === "'")) return s.slice(1, -1);
    if (a === '<' && b === '>') return s.slice(1, -1);
  }
  return s;
}

/**
 * Build a structured report model for a test-failure diagnostic (CLI flat report, no rail).
 * Non-test-failure diags return null — callers keep the legacy one-line render.
 *
 * @param {object} d normalized diagnostic
 * @param {{ count?: number, showHeader?: boolean }} [opts]
 *   {@code count} — total test failures in the module (header "N test failed").
 *   {@code showHeader} — false for subsequent failures in the same module so only the first
 *   report carries {@code ✘ Test failure in … › N tests failed} (CLI multi-failure parity).
 */
export function testFailureReport(d, opts) {
  if (!d || d.code !== 'test-failure') return null;
  const count = opts && opts.count > 0 ? opts.count : 1;
  const showHeader = !opts || opts.showHeader !== false;
  const assertj = parseAssertJMessage(d.message);
  const simpleEx = simpleTypeName(d.exceptionClass);
  const label = shortTestLabel(d);
  const snippet = Array.isArray(d.snippet) ? d.snippet : [];
  const start = d.snippetStart > 0 ? d.snippetStart : 1;
  const errorLine = d.line > 0 ? d.line : 0;
  let maxCode = 0;
  for (const line of snippet) maxCode = Math.max(maxCode, String(line).length);
  const rows = snippet.map((code, i) => {
    const num = start + i;
    const text = String(code);
    const pad = Math.max(0, maxCode - text.length);
    return {
      num,
      error: errorLine > 0 && num === errorLine,
      code: text,
      pad,
    };
  });
  return {
    module: d.module || '',
    count,
    showHeader,
    label,
    assertj,
    message: d.message || '',
    file: d.file || '',
    line: errorLine,
    exceptionClass: simpleEx,
    rows,
  };
}

/** True when the diagnostic should use the rich test-failure report. */
export function isTestFailureDiag(d) {
  return !!(d && d.code === 'test-failure');
}
