// SPDX-License-Identifier: Apache-2.0
// Derived state of a folded card: the outcome badge, the coarse phase chain a module's steps group
// into, and the display order of module rows. Pure functions of an already-folded card — fold.js
// builds the shape, this file reads it, and nothing here touches the wire or a browser global.

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
 * <p>A real test/compile FAIL recorded before cancel still reads as failed. Modules that ended
 * because the session was cancelled ({@code state === 'cancelled'}) do not flip the badge.
 */
export function outcomeOf(card) {
  if (card.state === 'queued') return 'queued';
  if (card.state === 'running') return 'running';
  // FAIL steps / failed modules first — a cancel bit alone must not mask a real test failure.
  if (hasFailedStep(card) || (card.modules || []).some((m) => m.state === 'failed')) return 'failed';
  if (card.cancelled || (card.modules || []).some((m) => m.state === 'cancelled')) return 'cancelled';
  if (card.success === true) return 'success';
  if (card.success === false) return 'failed';
  // success + checked are both green outcomes (pure cache re-entry is "checked")
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
  // Resolve is setup noise on a happy path. Keep it only when a resolve step failed
  // so the chain starts at Generate (or the next real phase) otherwise.
  return nodes.filter((n) => !isQuietResolve(n));
}

/**
 * True for a completed-successfully {@code resolve} phase node (hide from the strip). Failed
 * resolves stay (the user must see where it broke), and so do running/cancelled ones — during a
 * cold-cache resolution (~18s) the resolve node is the ONLY live indicator; hiding it left the
 * phase chain empty until compile steps appeared.
 */
function isQuietResolve(node) {
  const phase = (node.phase || '').toLowerCase();
  if (phase !== 'resolve') return false;
  return node.state !== 'failed' && node.state !== 'running' && node.state !== 'cancelled';
}

/** Display label for a phase wire-name: capitalize the first letter ('compile' → 'Compile'). */
function phaseLabel(wire) {
  return wire ? wire.charAt(0).toUpperCase() + wire.slice(1) : '?';
}

/**
 * Stamp / resource / build-logic tails. A 1–2ms SUCCESS here is not "the compiler ran" —
 * {@link #phaseState} judges skip/success from the other steps in the phase.
 */
function isHousekeepingStep(step) {
  const n = step && step.name ? String(step.name) : '';
  return n === 'copy-resources' || n.startsWith('write-stamp') || n.startsWith('build-logic-');
}

/** A phase node's aggregate state from its steps: failed › running › skipped/cancelled › success. */
function phaseState(steps) {
  if (!steps.length) return 'running';
  if (steps.some((s) => s.state === 'failed')) return 'failed';
  if (steps.some((s) => s.state === 'running')) return 'running';
  // Judge productive work only. copy-resources SUCCESS@2ms must not turn Compile green
  // when compile-java was SKIPPED (action-cache / stamp hit).
  const primary = steps.filter((s) => !isHousekeepingStep(s));
  const judged = primary.length ? primary : steps;
  if (judged.every((s) => s.state === 'skipped')) return 'skipped';
  if (judged.every((s) => s.state === 'skipped' || s.state === 'cancelled')) return 'cancelled';
  // Idle bookkeeping only (explicit 0ms success + skips): paint the phase as skipped so
  // Generate with a 0ms empty generate is not solid "success". Missing millis is not
  // treated as idle (history/tests often omit duration).
  if (judged.every((s) => s.state === 'skipped' || (s.state === 'success' && s.millis === 0))) {
    return 'skipped';
  }
  return 'success';
}

/**
 * One line summarizing a card's module work, e.g. "3 modules · 1 failed",
 * "checked 2 modules, all up to date", "built 1 · checked 2", "built 27 modules" — '' when
 * nothing to say. Past tense for finished work so it does not read as the bare kind label
 * "build" plus a count ("build 27 modules").
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
  if (built > 0) return `built ${built} ${noun(built)}`;
  // Still running / unknown terminal mix — bare count only.
  return `${n} ${noun(n)}`;
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
export function stepState(status, millis) {
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
