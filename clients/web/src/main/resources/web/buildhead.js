// SPDX-License-Identifier: Apache-2.0
// The live build's top bar and worker rows, laid out as the terminal paints them: a blue chip
// (spinner + command name, right-pointing edge), the gradient bar butting up to it through a
// reverse chevron, the percentage at the bar's far end, then the ETA and elapsed clock. The rows
// below are a fixed set (strip.workerLanes) so the pane never changes height mid-build.

const SETTLED_ICON = Object.freeze({ success: 'check', failed: 'x', cancelled: 'ban' });

/** The top bar. `state` is the run's outcome class: running | success | failed | cancelled | … */
export const BuildHead = {
  props: {
    label: { type: String, default: '' },
    pct: { type: Number, default: 0 },
    state: { type: String, default: 'running' },
    eta: { type: String, default: '' }, // '~1:20' / '0s' / '+0:05'; '' with no estimate
    clock: { type: String, default: '' }, // elapsed while running, the final wall once finished
  },
  // The settled chip carries the outcome's icon (the terminal's check / cross / cancelled) where the spinner was.
  computed: {
    icon() {
      return SETTLED_ICON[this.state] || '';
    },
  },
  template: `
    <div class="bhead" :class="'bhead-' + state" role="progressbar" :aria-valuenow="pct" aria-valuemin="0" aria-valuemax="100">
      <span class="bchip"><span v-if="state === 'running'" class="spin bspin"></span><jk-icon v-else-if="icon" :name="icon" class="bglyph"></jk-icon><span class="bchip-label">{{ label }}</span></span>
      <span class="btrack"><span class="bfill" :style="{ width: (state === 'running' ? pct : 100) + '%' }"></span><span class="bpct">{{ state === 'running' ? pct : 100 }}%</span></span>
      <span class="bclock"><template v-if="eta"><span class="bsep">·</span> <span class="eta-label">ETA</span> <span class="eta-cd">{{ eta }}</span> </template><span class="bsep">·</span> <span class="bel">{{ clock }}</span></span>
    </div>`,
};

/** The fixed worker rows: `lanes` from strip.workerLanes. Only the detail (last segment) truncates. */
export const WorkerLanes = {
  props: { lanes: { type: Array, required: true } },
  template: `
    <div class="lanes" aria-label="Workers">
      <div v-for="(l, i) in lanes" :key="i" class="lane" :class="{ 'lane-idle': l.idle }" :data-tip="l.idle ? undefined : l.tip">
        <template v-if="l.idle"><span class="lane-dot">◎</span><span class="lane-idle-text">idle</span></template>
        <template v-else>
          <span class="lane-dot">◎</span><span class="lane-mod">{{ l.module }}</span><span class="lane-sep">›</span><span class="lane-op">{{ l.op }}</span>
          <template v-if="l.segs.length"><span class="lane-sep">›</span><span class="lane-detail"><span v-for="(s, k) in l.segs" :key="k" :class="s.cls">{{ s.text }}</span></span></template>
        </template>
      </div>
    </div>`,
};
