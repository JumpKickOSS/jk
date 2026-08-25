// SPDX-License-Identifier: Apache-2.0
// Folds the /api/events stream into activity cards — pure functions of (cards, event), no browser
// globals, so the logic is testable headlessly with `node --test` (see docs/webclient.md).

import { isTestFailureDiag } from './failure.js';
import { fmtDuration } from './format.js';
import { stepState } from './outcome.js';

/** Cards kept in the activity feed — a long-lived tab must not grow the page without limit. */
export const MAX_CARDS = 50;

/** Console-tail lines kept per running card. */
export const MAX_OUTPUT_LINES = 8;

/** Failure diagnostics kept per card (the server already bounds what it publishes). */
export const MAX_DIAGNOSTICS = 12;

/**
 * Client-side ceiling for rich test-failure diagnostics per module. The live SSE feed is already
 * server-capped, but journal history replay is not — a pathological record must not
 * inject thousands of snippet+stack payloads into one card.
 */
export const MAX_TEST_FAILURE_DIAGNOSTICS = 120;

/** Same set as {@code BuildHistoryKinds} — Activity tracks builds, not format/lock/cache. */
export const BUILD_LIKE_KINDS = new Set(['build', 'test', 'compile', 'native', 'image']);

export function isBuildLikeKind(kind) {
  return BUILD_LIKE_KINDS.has(kind);
}

/**
 * Fold one SSE event into the newest-first card list, mutating and returning it.
 * An event is `{type, data, at}` where `data` is the parsed flat JSON payload the engine
 * publishes and `at` is the client-clock receipt time (fold stays clock-free and pure):
 * request-start/finish carry jid/kind/dir (+ coord on start when the project's jk.toml
 * parses; + success/cancelled/millis on finish); module/step/output/plan events carry
 * jid/dir plus their specifics.
 */
/**
 * Latch the card's client-epoch start anchor. `startedAt` is engine wall clock; comparing it
 * with the browser clock on a skewed remote dashboard shifts elapsed/bar/deadline by the skew
 *. When a frame carries the engine's own `serverNow`, elapsed = serverNow - startedAt
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
      if (!isBuildLikeKind(d.kind || 'build')) break;
      // Engine startedAt (admission) beats client receipt time — late join / rehydrate must match TUI.
      const engineStart =
        typeof d.startedAt === 'number' && d.startedAt > 0 ? d.startedAt : null;
      // Already attached (SSE connect rehydrate replayed, or this tab started the job).
      const attached = cards.find((c) => c.id === d.jid);
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
        existing.id = d.jid; // prefer live jid for subsequent SSE
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
        id: d.jid,
        kind: d.kind || 'request',
        dir: d.dir || '',
        coord: d.coord || null,
        projectId: d.projectId || null,
        buildNumber: d.buildNumber || null,
        state: 'running',
        startedAt: engineStart ?? event.at ?? null,
        // Client-epoch anchor: skew-corrected when serverNow rides the frame, else a
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
        // progressPercent from engine workspace-progress — dumb client, no re-sum.
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
        const row = stepRow(card, d.dir, d.task, d.stage, event.at);
        row.state = 'running';
        row.message = ''; // new step — clear previous tick text
        row.startedAt = event.at ?? row.startedAt ?? null; // wall receipt for duration fallback
      }
      break;
    }
    case 'task-finish': {
      const card = resolveCard(cards, d);
      if (card) {
        const row = stepRow(card, d.dir, d.task, d.stage, event.at);
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
      if (card) stepRow(card, d.dir, d.task, d.stage, event.at).message = d.label || '';
      break;
    }
    case 'plan': {
      const card = resolveCard(cards, d);
      if (card) card.planWeight = d.weight || 0;
      break;
    }
    case 'progress': {
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
        // for first seed (reconnect or late join): seeding from original R0 restarted a
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
        // Always record remaining@emission for etaTotalMillis re-projections.
        card.etaMillis = d.millis;
        card.etaAt = event.at ?? null;
        // Seed R0 — and, matching the CLI's "positive re-seeds allowed pre-execute" rule, let a
        // later eta REPLACE a provisional seed until any module work has folded: a contended
        // build's coarse lock+prior figure otherwise stayed R0 for the whole run and the
        // R0-fallback bar paced against the wrong total. Mid-run, residual re-anchors
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
    case 'error': {
      const card = resolveCard(cards, d);
      if (card) {
        const mod = moduleRow(card, d.dir, event.at);
        const nd = normalizeDiagnostic(d);
        // Per-kind ceilings (server policy): a test-failure flood must not evict the
        // compile/resolve slice, and vice versa.
        const kindCount = mod.diagnostics.filter((x) => isTestFailureDiag(x) === isTestFailureDiag(nd)).length;
        if (kindCount < (isTestFailureDiag(nd) ? MAX_TEST_FAILURE_DIAGNOSTICS : MAX_DIAGNOSTICS)) {
          mod.diagnostics.push(nd);
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
        // didWork=false → pure cache check; treat as success but label checked.
        row.didWork = d.didWork !== false;
        row.state = d.success
          ? row.didWork
            ? 'success'
            : 'checked'
          : d.cancelled
            ? 'cancelled'
            : 'failed';
        row.millis = d.millis ?? row.millis;
        if (d.coord) row.coord = d.coord;
        // User-cancel stamp as soon as a module reports it — do not wait for request-finish.
        if (d.cancelled) card.cancelled = true;
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
  if (!d || d.jid == null) return;
  // A snapshot captured while the run was still live can arrive after the finish frame in a
  // reconnect race — it must never resurrect a finished card as running.
  const pre = cards.find((c) => c.id === d.jid);
  if (pre && pre.state !== 'running') return;
  // Ensure a running card exists (same paths as request-start rehydrate).
  foldEvent(cards, {
    type: 'request-start',
    data: {
      jid: d.jid,
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
  // runs without a buildNumber (e.g. lock jobs), instead of waiting for a history GET.
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
  // lose them for the rest of the run.
  const mods = historyModules({
    running: true,
    dir: d.dir || card.dir || '',
    coord: d.coord || card.coord,
    cancelled: false,
    success: false,
    startedAt: card.startedAt,
    modules: d.modules,
    tasks: d.tasks,
    diagnostics: [],
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
 * Aggregate numerator for the request bar. Prefers engine workspace-progress units;
 * falls back to summing module ticks only when no aggregate event has arrived yet.
 */
/**
 * Run-wide ETA total for a card, in ms — or null when the card has no usable ETA. `etaMillis` is
 * REMAINING work at emission time (BuildService remaining-work semantics), so the total is
 * (etaAt - startedAt) + etaMillis: the same elapsed+remaining conversion the CLI does. Without
 * it, a slow lock/prepare window or an ETA re-projection double-counts already-elapsed time and
 * the countdown hits "0s" while the build is on schedule. Journal-seeded cards carry
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
 *. The ±2s time window remains only as a fallback for cards without a buildNumber.
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
        // match a live SSE card to a durable in-flight journal row
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
        // must not leave the card spinning forever — history is the durable truth.
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
      // Enriched history carries the engine jid (stored records keep requestId) — rebind a stub.
      const liveId = rec.jid ?? rec.requestId;
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
    // write) — seeding it would add a phantom running row next to the finished card.
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
export function historyCard(rec) {
  const running = !!rec.running;
  // Prefer the live engine jid (enriched by GET /api/history) so SSE events rebind without
  // waiting for a second request-start after a hard refresh mid-build.
  const liveId = rec.jid ?? rec.requestId;
  const id = running && typeof liveId === 'number' && liveId > 0 ? liveId : 'h:' + rec.id;
  const progressPercent = typeof rec.progress === 'number' ? rec.progress : null;
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
    etaMillis: null,
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
  // r0At seeds below land in the client epoch.
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
    step: d.task || '',
    code: d.code || '',
    message: d.message || '',
    test: d.test || '',
    exceptionClass: d.exceptionClass || '',
    module: d.module || '',
    engine: d.engine || '',
    className: d.testClass || d.class || '',
    method: d.method || '',
    stack: d.stack || '',
    file: d.file || '',
    line: typeof d.line === 'number' ? d.line : 0,
    col: typeof d.col === 'number' ? d.col : 0,
    snippetStart: typeof d.snippetStart === 'number' ? d.snippetStart : 0,
    snippet,
    worker: typeof d.worker === 'number' ? d.worker : 0,
  };
}

/**
 * Module rows for a persisted record, matching the live card shape (each with its own step chain).
 * A workspace record has `modules[]` each carrying `tasks`; a single-project record has no modules
 * and its tasks at the top level — synthesize one row from them so backfilled cards match live.
 * In-flight enriched rows may include {@code RUN} tasks and unfinished modules (no success yet).
 */
function historyModules(rec) {
  const running = !!rec.running;
  const toSteps = (ps) =>
    (ps || []).map((p) => {
      // Absent or negative millis = unknown duration (renders plain); 0 is the true-no-op
      // signal that renders dashed (the journal no longer stamps unknown as 0).
      const millis = typeof p.millis === 'number' && p.millis >= 0 ? p.millis : null;
      return {
        name: p.name || '?',
        state: stepState(p.status, millis),
        phase: p.stage || '',
        millis,
        message: '',
      };
    });
  // finishedAt / startedAt give a stable lastActivity for display order after backfill.
  const activity = rec.finishedAt || rec.startedAt || 0;
  if ((rec.modules || []).length > 0) {
    return rec.modules.map((m, i) => {
      const steps = toSteps(m.tasks);
      let state;
      if (typeof m.finished === 'boolean') {
        // Engine's explicit lifecycle bit: success=false alone was ambiguous between
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
            : steps.some((s) => s.state === 'failed')
              // A real FAIL recorded before the cancel still reads as failed — same precedence
              // as outcomeOf and the legacy branch below: a compile failure followed
              // by a workspace cancel must not gray out to 'cancelled' on reload.
              ? 'failed'
              : rec.cancelled || m.cancelled || steps.some((s) => s.state === 'cancelled')
                ? 'cancelled'
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
        // Same per-kind ceilings as the single-project path below: a pathological
        // workspace record must not inject thousands of snippet+stack payloads into one card.
        diagnostics: boundDiagnostics(historyDiags(rec.diagnostics, m.dir || '')),
        // Preserve journal order as a tie-break (later modules slightly higher lastActivity).
        lastActivity: activity + i,
      };
      // Carry the engine's cache-check bit so checked rendering survives merges.
      if (typeof m.didWork === 'boolean') row.didWork = m.didWork;
      return row;
    });
  }
  // Single-project: no modules, tasks at top level. Its diagnostics live in the "" bucket, so take
  // every error the record carries (there is only one module to own them).
  const steps = toSteps(rec.tasks);
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
    diagnostics: boundDiagnostics((rec.diagnostics || [])
      .filter((d) => d.severity !== 'warning')
      .map((d) => normalizeDiagnostic(d))),
    lastActivity: activity,
  }];
}

/** Apply the per-kind diagnostic ceilings (same policy as the live path) to a replayed list. */
function boundDiagnostics(list) {
  const out = [];
  let tests = 0;
  let other = 0;
  for (const d of list) {
    if (isTestFailureDiag(d)) {
      if (tests < MAX_TEST_FAILURE_DIAGNOSTICS) { out.push(d); tests++; }
    } else if (other < MAX_DIAGNOSTICS) {
      out.push(d); other++;
    }
  }
  return out;
}


/**
 * Find the card for an SSE payload. Live cards key on the numeric engine {@code jid}.
 * After a hard refresh mid-build the journal seeds a stub with id {@code h:<historyId>} — rebind
 * that stub to the live jid on the first matching event so progress/ETA/finish attach.
 */
function resolveCard(cards, d) {
  const requestId = d && d.jid;
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
function byId(cards, jid) {
  return resolveCard(cards, { jid });
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
 * Step wall-clock from a finish payload: the engine's {@code millis} when it sent one, else the
 * client's receipt delta from {@code task-start}.
 */
function stepMillisOf(d, row, at) {
  if (typeof d.millis === 'number' && Number.isFinite(d.millis)) return Math.max(0, d.millis);
  if (row && row.startedAt != null && at != null && Number.isFinite(at) && Number.isFinite(row.startedAt)) {
    return Math.max(0, at - row.startedAt);
  }
  return row && row.millis != null ? row.millis : null;
}


/**
 * One step's tooltip fragment: {@code compile-tests (212ms)}, or just the name while still running.
 */
export function stepTimingLabel(step) {
  if (!step || !step.name) return '';
  const t = fmtDuration(step.millis, { compact: true });
  return t ? step.name + ' (' + t + ')' : step.name;
}


