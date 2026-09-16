// SPDX-License-Identifier: Apache-2.0
// The Activity feed's view of one folded card: how far along it is, what its ETA face reads, which
// badge it wears, and which of its modules and diagnostics are worth rendering. Spread into the
// root component's `methods`, so `this` is the root component and `this.now` is its 1s clock.

import { compilerFailureReports, isCompilerDiag, isTestFailureDiag, testFailureReport } from './failure.js';
import { etaTotalMillis, ioLines, weightDenominator, weightNumerator } from './fold.js';
import { fmtBytes, fmtClockSeconds } from './format.js';
import { detailSegments } from './label.js';
import { moduleSummary, orderedModules, outcomeOf } from './outcome.js';

/** Memoized test-failure report models, keyed by the (immutable) diagnostic object. */
const TF_REPORT_CACHE = new WeakMap();
/** Memoized compile-failure report models, keyed by the (immutable) diagnostic object. */
const CF_REPORT_CACHE = new WeakMap();

export const cardMethods = {
  // The <jk-icon> name for a build/project state (badges + pills). Running gets a play triangle (the
  // Activity feed shows a spinner instead); 'issue' (alert) is reserved for the audit/CVE signal —
  // nothing drives it yet, but the state, colour, and chip are wired so a future signal only sets it.
  stateIcon(state) {
    return { running: 'play', success: 'check', failed: 'x', cancelled: 'ban', issue: 'alert', finished: 'dot' }[state] || 'dot';
  },
  // Staggered entrance delay for the .rise-in cascade on feed items, capped so long lists don't
  // wait seconds. New SSE cards prepend at index 0, so they land immediately.
  riseDelay(i) {
    return Math.min(i, 8) * 0.04 + 's';
  },
  // Progress % — same strategies as CLI (clock vs weighted). Default AUTO: open-loop
  // elapsed/R0 when R0 is known (smooth + aligned with countdown); else weight slices.
  // Override: localStorage.jkProgressMode = 'clock' | 'weighted' | 'auto'
  progressMode() {
    try {
      const m = (localStorage.getItem('jkProgressMode') || 'auto').toLowerCase();
      if (m === 'clock' || m === 'weighted' || m === 'auto') return m;
    } catch (_) {}
    return 'auto';
  },
  progress(card) {
    if (this.outcome(card) !== 'running') return 100;
    const pct = this.rawProgress(card);
    // Monotonic floor across the weighted→clock takeover: the clock fill starts
    // near 0 when R0 seeds mid-preflight — never repaint below the card's displayed peak.
    if (typeof card.peakPct === 'number' && card.peakPct > pct) return card.peakPct;
    card.peakPct = pct;
    return pct;
  },
  rawProgress(card) {
    const mode = this.progressMode();
    const haveR0 = typeof card.r0Ms === 'number' && card.r0Ms > 0 && card.r0At != null;
    const haveResidual = typeof card.residualRemainingMs === 'number' && card.residualRemainingMs >= 0;
    // Forced clock also paints from residual alone (no R0 seed) — same fallback ladder as the
    // engine/CLI ProgressBarMode.select; with neither signal, weighted below.
    const useClock = (mode === 'clock' && (haveR0 || haveResidual)) || (mode === 'auto' && haveR0);
    if (useClock) {
      // Prefer engine admission time so a mid-build join does not restart the bar at 0%.
      // Fall back to r0At (first seed receipt) for tabs that watched from the first tick.
      const base =
        card.startedAtClient != null
          ? card.startedAtClient
          : card.startedAt != null
            ? card.startedAt
            : haveR0
              ? card.r0At
              : this.now;
      const since = Math.max(0, this.now - (base != null ? base : this.now));
      // Adaptive: elapsed / (elapsed + residual). Residual firms up as work completes —
      // same oracle the countdown re-anchors to (ends on time with residual → 0).
      let raw;
      if (haveResidual) {
        const denom = since + card.residualRemainingMs;
        raw = denom <= 0 ? 0.99 : since / denom;
      } else {
        raw = since / card.r0Ms;
      }
      raw = Math.min(0.99, Math.max(0, raw));
      return Math.min(99, Math.round(raw * 100));
    }
    // Weighted fallback (or forced weighted): engine progressPercent / num/den
    if (typeof card.progressPercent === 'number') {
      return Math.min(99, Math.round(card.progressPercent));
    }
    const den = weightDenominator(card);
    if (den <= 0) return 0;
    return Math.min(99, Math.round((100 * weightNumerator(card)) / den));
  },
  // Live ETA dual-clock (CLI parity). Both faces share one whole-second elapsed counter so they
  // tick on the same paint — flooring remaining-ms and elapsed-ms independently desynced them.
  // Countdown re-anchors to residual RemainingWork so it eases into R(t), paints "0s" at
  // the deadline, then counts the miss as "+Ns". Elapsed is always full run time, no plus.
  // No seed → count-up only.
  hasEta(card) {
    if (this.outcome(card) !== 'running') return false;
    const haveR0 = typeof card.r0Ms === 'number' && card.r0Ms > 0 && card.r0At != null;
    const haveResidual =
      typeof card.residualRemainingMs === 'number' &&
      card.residualRemainingMs >= 0 &&
      card.residualAt != null;
    return haveR0 || haveResidual;
  },
  elapsedSeconds(card) {
    // Client-epoch anchor first: engine-epoch startedAt vs this.now shifts elapsed
    // by the clock skew on a remote dashboard.
    const start = card.startedAtClient ?? card.startedAt;
    if (start == null) return 0;
    return Math.max(0, Math.floor((this.now - start) / 1000));
  },
  // Whole-second countdown deadline on the SAME counter as elapsedSeconds (startedAt epoch).
  // Prefer residual re-anchor when known (CLI setBarResidualRemaining); fall back to frozen R0.
  // Deriving both faces from one counter keeps them ticking on the same paint.
  etaDeadlineSeconds(card) {
    if (!this.hasEta(card)) return null;
    const start = card.startedAtClient ?? card.startedAt;
    const base = start != null ? start : (card.residualAt != null ? card.residualAt : card.r0At);
    if (base == null) return null;
    // Residual re-anchor: deadline = residualAt + residualRemaining (open-loop decay between samples).
    if (
      typeof card.residualRemainingMs === 'number' &&
      card.residualRemainingMs >= 0 &&
      card.residualAt != null
    ) {
      return Math.floor((card.residualAt - base + card.residualRemainingMs) / 1000);
    }
    // Seed-only: deadline = r0At + r0Ms.
    if (typeof card.r0Ms === 'number' && card.r0Ms > 0 && card.r0At != null) {
      return Math.floor((card.r0At - base + card.r0Ms) / 1000);
    }
    return null;
  },
  etaSeconds(card) {
    const deadline = this.etaDeadlineSeconds(card);
    if (deadline != null) {
      return Math.max(0, deadline - this.elapsedSeconds(card));
    }
    const total = etaTotalMillis(card);
    return total == null ? 0 : Math.max(0, Math.floor(total / 1000));
  },
  /** Seconds past the residual/R0 deadline, or 0 while the countdown is still decaying. */
  etaOverrunSeconds(card) {
    const rem = this.etaFaceSeconds(card);
    if (rem == null || rem > 0) return 0;
    const deadline = this.etaDeadlineSeconds(card);
    if (deadline == null) return 0;
    return Math.max(0, this.elapsedSeconds(card) - deadline);
  },
  /**
   * Whole-second countdown with the CLI's 1s jitter buffer: the face
   * commits once per elapsed second, so a fresh residual sample landing mid-second cannot
   * flick the digit ±1 when the deadline straddles a floor boundary. Zero snaps immediately
   * (end on time), and a same-second re-anchor that raises the target overwrites a committed
   * zero rather than bouncing 0s → Ns on the next second .
   */
  etaFaceSeconds(card) {
    const deadline = this.etaDeadlineSeconds(card);
    if (deadline == null) return null;
    const sec = this.elapsedSeconds(card);
    const rem = Math.max(0, deadline - sec);
    if (rem <= 0) {
      card.etaFaceSec = sec;
      card.etaFaceRem = 0;
      return 0;
    }
    if (card.etaFaceSec !== sec || card.etaFaceRem == null || card.etaFaceRem <= 0) {
      card.etaFaceSec = sec;
      card.etaFaceRem = rem;
    }
    return card.etaFaceRem;
  },
  etaCountdown(card) {
    const rem = this.etaFaceSeconds(card);
    if (rem == null) return '';
    if (rem > 0) return '~' + fmtClockSeconds(rem);
    const over = this.etaOverrunSeconds(card);
    return over > 0 ? '+' + fmtClockSeconds(over) : '0s';
  },
  // Bare countdown string (no "ETA " label).
  eta(card) {
    return this.etaCountdown(card);
  },
  // mm:ss-style clock mirroring the CLI's CommandManager.fmtClock: "42s" / "1m 02s" / "1h 05m 09s".
  // Whole seconds only (floor) so dual-clock faces share one boundary — not Math.round.
  fmtClock(ms) {
    return fmtClockSeconds(Math.max(0, Math.floor(ms / 1000)));
  },
  outcome(card) {
    return outcomeOf(card);
  },
  // Badge label for a job card — optional jid (running) + #buildNumber + capitalized outcome.
  activityBadge(card) {
    const o = this.outcome(card);
    const jid = card.id != null && o === 'running' ? 'jid=' + card.id + ' ' : '';
    const num = card.buildNumber ? '#' + card.buildNumber + ' ' : '';
    return jid + num + o.charAt(0).toUpperCase() + o.slice(1);
  },
  summary(card) {
    return moduleSummary(card);
  },
  /**
   * Activity kind label. Wire kind stays {@code build}; finished runs use past tense so a
   * fully-cached monorepo (no / few module rows, ~100ms) reads "built" not "build".
   */
  kindLabel(card) {
    const k = (card && card.kind) || '';
    if (k === 'build' && this.outcome(card) !== 'running') return 'built';
    return k;
  },
  // The capitalized phase a diagnostic belongs to, joined from the module's step rows (which carry
  // the phase) by matching the diagnostic's step name. '' when the step has no phase or isn't found
  // the failure line then reads step › … without a phase prefix.
  diagPhase(mod, d) {
    const st = ((mod && mod.steps) || []).find((s) => s.name === d.step);
    const wire = st && st.phase ? st.phase : '';
    return wire ? wire.charAt(0).toUpperCase() + wire.slice(1) : '';
  },
  /** True when this diagnostic is a structured per-test failure (rich report, not one-liner). */
  isTestFailure(d) {
    return isTestFailureDiag(d);
  },
  /** True when this diagnostic is a javac / kotlinc / groovyc block. */
  isCompiler(d) {
    return isCompilerDiag(d);
  },
  /**
   * CLI-parity report model for a test-failure diagnostic, as a 0/1-element array for a single
   * {@code v-for} evaluation. {@code count} is how many test-failure diags this module carries
   * (header "N tests failed"); only the first failure in the module shows that header. Report
   * models are memoized per diagnostic — these are methods, not computeds, so they re-run on
   * every SSE-driven tick, and rebuilding label/assertj/snippet parses for every failure on
   * every tick made the card O(n²) in failures.
   */
  tfReports(mod, d) {
    if (!isTestFailureDiag(d)) return [];
    const diags = ((mod && mod.diagnostics) || []).filter((x) => isTestFailureDiag(x));
    const n = Math.max(1, diags.length);
    const showHeader = diags.length === 0 || diags[0] === d;
    let hit = TF_REPORT_CACHE.get(d);
    if (!hit || hit.n !== n || hit.showHeader !== showHeader) {
      hit = { n, showHeader, rep: testFailureReport(d, { count: n, showHeader }) };
      TF_REPORT_CACHE.set(d, hit);
    }
    return hit.rep ? [hit.rep] : [];
  },
  /**
   * CLI-parity compile-failure reports for a javac/kotlinc/groovyc diagnostic. First compiler
   * diag in the module carries the {@code Compile failure in …} header.
   */
  cfReports(mod, d) {
    if (!isCompilerDiag(d)) return [];
    const diags = ((mod && mod.diagnostics) || []).filter((x) => isCompilerDiag(x));
    const showHeader = diags.length === 0 || diags[0] === d;
    const module = (mod && mod.coord) || d.module || '';
    let hit = CF_REPORT_CACHE.get(d);
    if (!hit || hit.showHeader !== showHeader || hit.module !== module) {
      hit = { showHeader, module, reps: compilerFailureReports(d, { showHeader, module }) };
      CF_REPORT_CACHE.set(d, hit);
    }
    return hit.reps || [];
  },
  /** Syntax segments for {@code SimpleClass.method()} labels. */
  failLabelSegs(label) {
    return detailSegments(label);
  },
  // A build is "compact" (one step chain under the header, no module-name rows) when it has at
  // most one module — a single-project build, or a 1-module workspace. Multi-module builds render
  // a bullet+name row per module, each with its own chain.
  compact(card) {
    return card.modules.length <= 1;
  },
  // The single chain shown under the header for a compact card (the one module's steps, if any).
  singleChain(card) {
    return card.modules[0] ? card.modules[0].steps : [];
  },
  // The lone module of a compact card — carries the steps and any failure output shown inline.
  singleModule(card) {
    return card.modules[0] || null;
  },
  // Multi-module cards split their module rows across two peer accordions: the failed modules
  // (kept open) and everything else — succeeded, still-running, skipped — which rolls
  // up under a "success details" accordion that is open while running and collapsed once done. A
  // module carrying failure output counts as failed even if its state was never marked (covers
  // request-level errors that land on a synthetic row). Cancelled runs hide both accordions.
  // Order (CLI parity): active first (most recently updated), finished last.
  failedModules(card) {
    if (this.outcome(card) === 'cancelled') return [];
    return orderedModules(
      card.modules.filter((m) => m.state === 'failed' || m.diagnostics.length > 0),
    );
  },
  okModules(card) {
    if (this.outcome(card) === 'cancelled') return [];
    return orderedModules(
      card.modules.filter((m) => m.state !== 'failed' && m.diagnostics.length === 0),
    );
  },
  // A module row's label: the artifact name from its coord (e.g. "core"), else the dir's tail.
  moduleLabel(m) {
    if (m.coord) {
      const i = m.coord.lastIndexOf(':');
      return i >= 0 ? m.coord.slice(i + 1) : m.coord;
    }
    return this.shortDir(m.dir);
  },
  // ---- formatting helpers (templates keep zero logic beyond these) ----
  coordParts(card) {
    // "group:name" → colored segments; fall back to the dir's last path segment.
    // Guard null/undefined dir — projectMeta can land before a journal row has a path.
    const c = card || {};
    if (c.coord && String(c.coord).includes(':')) {
      const i = String(c.coord).indexOf(':');
      return { group: String(c.coord).slice(0, i), name: String(c.coord).slice(i + 1) };
    }
    if (c.dir == null || c.dir === '') {
      return { group: null, name: c.coord || 'project' };
    }
    const parts = String(c.dir).split('/').filter(Boolean);
    return { group: null, name: parts.length ? parts[parts.length - 1] : String(c.dir) };
  },
  elapsed(card) {
    if (card.startedAt == null) return '';
    // Full run-wide count-up from the same whole-second counter as the countdown. No plus —
    // the + lives on the ETA face once the estimate is past.
    return fmtClockSeconds(this.elapsedSeconds(card));
  },
  // The run's byte counters, one row per scope (remote = network, local = build cache). Both the
  // rows and the size formatting are pure functions in fold.js so they're covered headlessly.
  ioLines(card) {
    return ioLines(card);
  },
  // Screen-reader text for the I/O breakdown; the visual tooltip is CSS hover/focus.
  ioSummary(card) {
    return this.ioLines(card)
      .map((l) => `${l.label}: ${fmtBytes(l.up)} up, ${fmtBytes(l.down)} down`)
      .join('; ');
  },
};
