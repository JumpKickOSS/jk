// SPDX-License-Identifier: Apache-2.0

import { detailSegments, liveStepDetail } from './label.js';
import { phaseChainOf } from './outcome.js';
import { stepTimingLabel } from './fold.js';

// The build **phase-chain**: a single horizontal strip of coarse plan phases (Generate →
// Compile → Test → …), never wrapping. Resolve is omitted unless it failed. New phases advance rightward and push earlier ones off the
// left; when phases are hidden a ◂ / ▸ nav button pages the view (no scrollbar). Anchored to the
// newest phase on mount and whenever the chain grows. Each phase node is a click-to-expand toggle
// (single-open) that reveals the steps it collapses; the failed phase auto-opens. See
// docs/webclient.md. The `steps` prop is the module's raw step rows; the phase grouping is derived
// from them client-side (fold.phaseChainOf), so a new/plugin phase needs no code change here.
export const PhaseChain = {
  // `module` is the coord used to strip redundant "g:a :: " prefixes from test labels (CLI parity).
  props: {
    steps: { type: Array, required: true },
    module: { type: String, default: '' },
  },
  // `follow` = keep pinned to the newest phase (re-armed when the user pages back to the end).
  // `manualKey` = the user's single-open accordion choice: `undefined` until they click (failed
  // phase auto-opens), then a phase key, or `null` when they've closed all.
  data: () => ({ atStart: true, atEnd: true, follow: true, manualKey: undefined }),
  template: `
    <div class="phase-chain-outer" :class="{ 'has-open': !!openPhase }">
      <div class="phase-live-row">
        <div class="step-chain-wrap">
          <button v-show="!atStart" type="button" class="chain-nav left" @click="page(-1)"
                  aria-label="show earlier phases" data-tip="earlier phases"><jk-icon name="chevron-left"></jk-icon></button>
          <span v-show="!atStart" class="chain-fade left" aria-hidden="true"></span>
          <div class="step-chain" ref="track">
            <template v-for="(p, i) in phases" :key="p.key">
              <span v-if="i > 0" class="step-edge" :class="phases[i - 1].state"></span>
              <button type="button" class="step-node phase-node" :class="[p.state, { open: openKey === p.key }]"
                      :data-tip="phaseTitle(p)" :aria-expanded="String(openKey === p.key)" @click="toggle(p.key)">
                <span v-if="p.state === 'running'" class="spin small"></span>
                <jk-icon v-else-if="p.state === 'success' || p.state === 'skipped'" name="check" class="step-glyph ok"></jk-icon>
                <jk-icon v-else-if="p.state === 'failed'" name="x" class="step-glyph err"></jk-icon>
                {{ p.label }}
              </button>
            </template>
          </div>
          <span v-show="!atEnd" class="chain-fade right" aria-hidden="true"></span>
          <button v-show="!atEnd" type="button" class="chain-nav right" @click="page(1)"
                  aria-label="show later phases" data-tip="later phases"><jk-icon name="chevron-right"></jk-icon></button>
        </div>
        <!-- Live tick/label after the (blue) running phase — CLI "· detail" segment. -->
        <span v-if="liveDetail" class="phase-detail" :data-tip="liveDetail">
          <span class="phase-detail-sep" aria-hidden="true">·</span>
          <span class="phase-detail-text">
            <span v-for="(seg, i) in liveDetailSegs" :key="i" :class="seg.cls">{{ seg.text }}</span>
          </span>
        </span>
      </div>
      <div v-if="openPhase" class="phase-steps" :class="openPhase.state">
        <template v-for="(s, i) in openPhase.steps" :key="s.name">
          <span v-if="i > 0" class="step-edge" :class="openPhase.steps[i - 1].state"></span>
          <span class="step-node" :class="s.state" :data-tip="stepTitle(s)">
            <span v-if="s.state === 'running'" class="spin small"></span>
            <jk-icon v-else-if="s.state === 'success' || s.state === 'skipped'" name="check" class="step-glyph ok"></jk-icon>
            <jk-icon v-else-if="s.state === 'failed'" name="x" class="step-glyph err"></jk-icon>
            {{ stepLabel(s) }}
          </span>
        </template>
      </div>
    </div>`,
  computed: {
    phases() {
      return phaseChainOf({ steps: this.steps });
    },
    // Effective open phase: the user's manual choice once they've clicked, else the failed phase
    // (auto-open on failure) — so a failure's step is visible without any interaction.
    openKey() {
      if (this.manualKey !== undefined) return this.manualKey;
      const failed = this.phases.find((p) => p.state === 'failed');
      return failed ? failed.key : null;
    },
    openPhase() {
      return this.phases.find((p) => p.key === this.openKey) || null;
    },
    // Rightmost running phase's current tick text (test class.method, "shrinking jar", …).
    liveDetail() {
      return liveStepDetail(this.module, this.steps);
    },
    liveDetailSegs() {
      return detailSegments(this.liveDetail);
    },
  },
  mounted() {
    this.observer = new ResizeObserver(() => this.reflow());
    this.observer.observe(this.$refs.track);
    this.$nextTick(() => this.anchorEnd());
  },
  updated() {
    // Fires after each phase update (state/width change); nextTick lets layout settle first.
    this.$nextTick(() => this.reflow());
  },
  beforeUnmount() {
    if (this.observer) this.observer.disconnect();
  },
  methods: {
    // Single-open accordion: clicking the open phase closes it, clicking another switches to it.
    // Either way the user has taken control (manualKey set), so auto-open-on-failure stands down.
    toggle(key) {
      this.manualKey = this.openKey === key ? null : key;
    },
    // A sub-chain step's label: drop the redundant leading "phase-" ("compile-java" under Compile →
    // "java"). A step whose name is exactly its phase, or that carries none, shows verbatim.
    stepLabel(s) {
      const prefix = (s.phase || '') + '-';
      return s.phase && s.name.startsWith(prefix) ? s.name.slice(prefix.length) : s.name;
    },
    // Tooltip: raw step name + duration when known (e.g. "compile-tests (212ms)"); live message wins
    // while the step is still running and has tick text.
    stepTitle(s) {
      if (s.state === 'running' && s.message) return s.message;
      return stepTimingLabel(s);
    },
    // Tooltip: the phase plus each collapsed step with its duration, e.g.
    // "resolve: ensure-jdk (360ms), resolve-deps (1.2s)".
    phaseTitle(p) {
      const names = p.steps.map((s) => stepTimingLabel(s)).join(', ');
      return p.phase ? p.phase + ': ' + names : names;
    },
    reflow() {
      if (this.follow) this.anchorEnd();
      else this.measure();
    },
    measure() {
      const t = this.$refs.track;
      if (!t) return;
      const max = t.scrollWidth - t.clientWidth;
      this.atStart = t.scrollLeft <= 1;
      this.atEnd = t.scrollLeft >= max - 1;
    },
    anchorEnd() {
      const t = this.$refs.track;
      if (!t) return;
      // Instant jump (not the smooth CSS path): a synchronous read in measure() must see the final
      // scrollLeft, and only user paging should animate.
      const max = Math.max(0, t.scrollWidth - t.clientWidth);
      if (Math.abs(t.scrollLeft - max) > 1) t.scrollLeft = max;
      this.measure();
    },
    page(dir) {
      const t = this.$refs.track;
      if (!t) return;
      this.follow = false; // manual navigation — stop auto-pinning to the end
      t.scrollTo({ left: t.scrollLeft + dir * Math.max(90, Math.round(t.clientWidth * 0.6)), behavior: 'smooth' });
      setTimeout(() => {
        this.measure();
        if (this.atEnd) this.follow = true; // paged back to the newest end → resume following
      }, 260);
    },
  },
};
