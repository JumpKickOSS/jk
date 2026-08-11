// SPDX-License-Identifier: Apache-2.0
// The dashboard: one root component, two views (Activity, Status), mounted by Vue 3 (global
// build from the CDN — see docs/webclient.md). The in-DOM template lives in index.html; Vue's
// runtime compiler turns it into render functions at load (the CSP 'unsafe-eval' grant).

import {
  bootstrapToken,
  token,
  clearToken,
  get,
  getText,
  post,
  del,
  events,
  loopback,
  noteEngineEpoch,
  hardRefreshForEpoch,
  echartsTooltipChrome,
} from './api.js';
import {
  foldEvent,
  outcomeOf,
  moduleSummary,
  phaseChainOf,
  seedFromHistory,
  weightNumerator,
  weightDenominator,
  ioLines,
  fmtBytes,
  liveStepDetail,
  detailSegments,
  orderedModules,
  etaTotalMillis,
  stepTimingLabel,
} from './fold.js';
import { installTips } from './tip.js';

bootstrapToken();

// The build **phase-chain**: a single horizontal strip of coarse plan phases (Resolve →
// Compile → Test → …), never wrapping. New phases advance rightward and push earlier ones off the
// left; when phases are hidden a ◂ / ▸ nav button pages the view (no scrollbar). Anchored to the
// newest phase on mount and whenever the chain grows. Each phase node is a click-to-expand toggle
// (single-open) that reveals the steps it collapses; the failed phase auto-opens. See
// docs/webclient.md. The `steps` prop is the module's raw step rows; the phase grouping is derived
// from them client-side (fold.phaseChainOf), so a new/plugin phase needs no code change here.
// A tiny inline-SVG icon set: <jk-icon name="refresh">. Each glyph is a 24x24 path stroked (or, for
// a couple, filled) with currentColor, so `color` / state classes drive it — no icon font, no network
// fetch, and inline SVG is allowed under the dashboard's CSP (default-src 'self' doesn't gate SVG DOM,
// unlike a data: URI). Sized by the .ico class (1em by default; see style.css). Registered globally
// before mount so every component (including phase-chain) can use it.
const ICON_PATHS = {
  'chevron-left': 'M15 18l-6-6 6-6',
  'chevron-right': 'M9 18l6-6-6-6',
  'arrow-up': 'M12 19V5M5 12l7-7 7 7',
  'arrow-down': 'M12 5v14M19 12l-7 7-7-7',
  check: 'M20 6L9 17l-5-5',
  x: 'M18 6L6 18M6 6l12 12',
  ban: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM5.6 5.6l12.8 12.8',
  alert: 'M10.3 3.9 2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v4M12 17h.01',
  refresh: 'M23 4v6h-6M1 20v-6h6M3.5 9a9 9 0 0 1 14.9-3.4L23 10M1 14l4.6 4.4A9 9 0 0 0 20.5 15',
  trash: 'M4 7h16M9 7V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v3M6 7l1 13a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-13M10 11v6M14 11v6',
  folder: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z',
  help: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM9.6 9.5a2.5 2.5 0 0 1 4.85.83c0 1.67-2.45 2.17-2.45 3.67M12 17h.01',
  settings: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.05.05a2 2 0 1 1-2.83 2.83l-.05-.05a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.05.05a2 2 0 1 1-2.83-2.83l.05-.05a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.05-.05a2 2 0 1 1 2.83-2.83l.05.05a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.05-.05a2 2 0 1 1 2.83 2.83l-.05.05a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z',
  bolt: 'M13 2 3 14 12 14 11 22 21 10 12 10Z',
  activity: 'M22 12h-4l-3 9L9 3l-3 9H2',
  database: 'M12 3c-4.4 0-8 1.3-8 3s3.6 3 8 3 8-1.3 8-3-3.6-3-8-3zM4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6',
  cpu: 'M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM9 9h6v6H9zM9 1v3M15 1v3M9 20v3M15 20v3M1 9h3M1 15h3M20 9h3M20 15h3',
  'folder-open': 'M6 14l1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.55 6a2 2 0 0 1-1.94 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2',
  plus: 'M12 5v14M5 12h14',
  // Two overlapping rectangles — clipboard / copy affordance (lucide-style).
  copy: 'M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2M8 2h8a1 1 0 0 1 1 1v2H7V3a1 1 0 0 1 1-1z',
};
// Icons that read better as a solid shape than an outline at small sizes.
const ICON_SOLID = {
  play: 'M8 5v14l11-7z',
  dot: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
};
const JkIcon = {
  props: { name: { type: String, required: true } },
  computed: {
    solid() {
      return this.name in ICON_SOLID;
    },
    d() {
      return this.solid ? ICON_SOLID[this.name] : ICON_PATHS[this.name] || '';
    },
  },
  template: `<svg class="ico" viewBox="0 0 24 24" :fill="solid ? 'currentColor' : 'none'"
       :stroke="solid ? 'none' : 'currentColor'" stroke-width="2" stroke-linecap="round"
       stroke-linejoin="round" aria-hidden="true"><path :d="d"></path></svg>`,
};

const PhaseChain = {
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
    <div class="phase-chain-outer">
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
                <jk-icon v-else-if="p.state === 'success'" name="check" class="step-glyph ok"></jk-icon>
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
      <div v-if="openPhase" class="phase-steps">
        <template v-for="(s, i) in openPhase.steps" :key="s.name">
          <span v-if="i > 0" class="step-edge" :class="openPhase.steps[i - 1].state"></span>
          <span class="step-node" :class="s.state" :data-tip="stepTitle(s)">
            <span v-if="s.state === 'running'" class="spin small"></span>
            <jk-icon v-else-if="s.state === 'success'" name="check" class="step-glyph ok"></jk-icon>
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

// The Projects tab's per-project history spark: one vertical bar per recent build (height = wall
// clock, colour = outcome), drawn with Apache ECharts (loaded globally from the CDN — see
// index.html). Kept deliberately chrome-less (no axes, no grid) so it reads as a sparkline inside the
// row. The x-axis is a fixed 30 slots so every bar is a consistent 1/30 width, packed left-to-right:
// a project with one build shows one bar occupying 1/30 of the track, not a single fat bar. Re-renders
// only when its `builds` prop changes (projectsList is a computed, so the 1s tick never thrashes it).
/** Read a CSS custom property off :root, falling back to a literal. ECharts paints to a <canvas>
 * and can't resolve var(), so the spark's bar/tooltip colours are looked up here at render time
 * (after the stylesheet has applied) — style.css :root stays the single source of the palette, and
 * the literal fallbacks (equal to those tokens) only cover the pre-stylesheet window. */
function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
/** Escape a value for interpolation into an HTML tooltip string — dependency names/versions/paths
 * come from a project's jk.toml/jk-lock.toml, which is attacker-adjacent (a shared or malicious
 * repo someone opens in the dashboard), so they must never reach innerHTML unescaped. */
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => HTML_ESCAPES[c]);
}

/** Outcome → spark-bar colour, resolved from the style.css palette via cssVar (see BuildBars).
 * Fallbacks must equal :root tokens (Jk Dark / JK-1081) — not Material leftovers. */
const buildColors = () => ({
  success: cssVar('--ok', '#00ff87'),
  failed: cssVar('--err', '#ff3366'),
  cancelled: cssVar('--dim', '#5c6d78'),
  running: cssVar('--run', '#3d9bff'),
});

/** Fixed number of build slots the spark reserves — a single build fills 1/30 of the width. */
const BUILD_SLOTS = 30;

const BuildBars = {
  props: { builds: { type: Array, required: true } },
  template: `<div class="build-bars" ref="el"></div>`,
  mounted() {
    if (window.echarts) this.chart = echarts.init(this.$refs.el, null, { renderer: 'canvas' });
    this.render();
    // The row's centre column is flex-sized; the chart must follow it (and any window resize).
    this.ro = new ResizeObserver(() => this.chart && this.chart.resize());
    this.ro.observe(this.$refs.el);
  },
  beforeUnmount() {
    if (this.ro) this.ro.disconnect();
    if (this.chart) this.chart.dispose();
  },
  watch: {
    builds() {
      this.render();
    },
  },
  methods: {
    render() {
      if (!this.chart) return;
      const builds = this.builds || [];
      const colors = buildColors();
      // A fixed BUILD_SLOTS-long category axis: builds fill the leftmost slots (oldest → newest),
      // the rest are empty (zero-height) placeholders so every bar keeps the same 1/30 width.
      const data = [];
      for (let i = 0; i < BUILD_SLOTS; i++) {
        const b = builds[i];
        data.push(
          b
            ? { value: Math.max(1, b.millis || 0), itemStyle: { color: colors[b.outcome] || cssVar('--dim', '#5c6d78'), borderRadius: [2, 2, 0, 0] } }
            : { value: 0, itemStyle: { color: 'transparent' } },
        );
      }
      this.chart.setOption(
        {
          animation: false,
          grid: { left: 1, right: 1, top: 6, bottom: 1, containLabel: false },
          xAxis: {
            type: 'category',
            show: false,
            data: Array.from({ length: BUILD_SLOTS }, (_, i) => i),
            boundaryGap: true,
          },
          yAxis: { type: 'value', show: false, min: 0 },
          tooltip: {
            trigger: 'axis',
            ...echartsTooltipChrome(cssVar),
            padding: [4, 8],
            axisPointer: { type: 'none' },
            formatter: (ps) => {
              const b = builds[ps[0].dataIndex];
              if (!b) return '';
              const n = b.buildNumber ? '#' + b.buildNumber + ' · ' : '';
              return n + b.outcome + ' · ' + fmtMillis(b.millis);
            },
          },
          series: [{ type: 'bar', data, barWidth: '80%', z: 2 }],
        },
        true,
      );
    },
  },
};

/**
 * Lazy dependency graph (JK-1542): mounted only when the Project-page Dependencies panel is open.
 * Fetches {@code GET /api/project/graph} with scope + transitive filters (same idea as
 * {@code jk tree --scopes}), aborts on unmount, and only then calls {@code echarts.init}.
 */
const ModuleDepGraph = {
  props: { dir: { type: String, required: true } },
  data: () => ({
    loading: true,
    error: null,
    graph: null,
    // Default: export/main/runtime — same as jk tree (DependencyTree.defaultScopeOrder).
    selectedScopes: { export: true, main: true, runtime: true },
    transitive: false,
    // Filled from the first successful response (server lists all Scope.canonical values).
    availableScopes: [
      'export',
      'main',
      'runtime',
      'provided',
      'processor',
      'platform',
      'test',
      'dev',
      'test-dev',
    ],
  }),
  template: `
    <div class="dep-graph-body">
      <div class="dep-graph-controls">
        <div class="dep-graph-scopes" role="group" aria-label="Dependency scopes">
          <label v-for="sc in availableScopes" :key="sc" class="check">
            <input type="checkbox" :checked="!!selectedScopes[sc]" @change="toggleScope(sc, $event)">
            <span class="check-box" aria-hidden="true"></span>
            <span>{{ sc }}</span>
          </label>
        </div>
        <label class="check dep-transitive" data-tip="Include lockfile transitive dependencies (off by default)">
          <input type="checkbox" :checked="transitive" @change="setTransitive($event)">
          <span class="check-box" aria-hidden="true"></span>
          <span>Transitive</span>
        </label>
      </div>
      <p v-if="loading" class="dep-graph-status dim small">Loading dependency graph…</p>
      <p v-else-if="error" class="dep-graph-status err small">{{ error }}</p>
      <p v-else-if="graph && !(graph.nodes || []).length" class="dep-graph-status dim small">
        No dependencies for the selected scopes.
      </p>
      <div v-show="graph && (graph.nodes || []).length" class="dep-graph-canvas" ref="el"></div>
      <p v-if="graph && graph.truncated" class="dep-graph-status warn small">
        Graph truncated at the node/edge cap — untick scopes or Transitive to see a complete graph.
      </p>
      <p v-if="graph && (graph.nodes || []).length" class="dep-graph-hint dim small mono">
        {{ graph.nodes.length }} node{{ graph.nodes.length === 1 ? '' : 's' }}
        · {{ (graph.edges || []).length }} edge{{ (graph.edges || []).length === 1 ? '' : 's' }}
        <template v-if="graph.truncated"> (truncated)</template>
        · pan / zoom · dependent → prereq
        <span class="swatch declared" data-tip="Workspace module or listed in a selected-scope jk.toml"></span>declared
        <span class="swatch transitive" data-tip="Transitive only — not listed in any selected-scope jk.toml"></span>transitive
      </p>
    </div>
  `,
  mounted() {
    this.load();
  },
  beforeUnmount() {
    this.teardown();
  },
  watch: {
    dir() {
      this.load();
    },
  },
  methods: {
    scopesQuery() {
      const order = this.availableScopes;
      const picked = order.filter((s) => this.selectedScopes[s]);
      return picked.length ? picked.join(',') : 'export,main,runtime';
    },
    toggleScope(sc, ev) {
      const on = !!(ev && ev.target && ev.target.checked);
      // Keep at least one scope selected (re-check main if the user clears the last box).
      const next = { ...this.selectedScopes, [sc]: on };
      if (!Object.values(next).some(Boolean)) next.main = true;
      this.selectedScopes = next;
      this.scheduleLoad();
    },
    setTransitive(ev) {
      this.transitive = !!(ev && ev.target && ev.target.checked);
      this.scheduleLoad();
    },
    /** Debounced load: ticking several scope boxes in a row fires ONE request, not one per click
     * (each transitive graph walk is real server work — JK-1625). */
    scheduleLoad() {
      if (this._loadTimer) clearTimeout(this._loadTimer);
      this._loadTimer = setTimeout(() => {
        this._loadTimer = null;
        this.load();
      }, 250);
    },
    teardown() {
      if (this._loadTimer) {
        clearTimeout(this._loadTimer);
        this._loadTimer = null;
      }
      if (this._abort) {
        this._abort.abort();
        this._abort = null;
      }
      if (this._ro) {
        this._ro.disconnect();
        this._ro = null;
      }
      if (this._chart) {
        this._chart.dispose();
        this._chart = null;
      }
    },
    async load() {
      // Abort in-flight only — keep the chart DOM until the next payload paints.
      if (this._abort) {
        this._abort.abort();
        this._abort = null;
      }
      this.loading = true;
      this.error = null;
      if (!this.dir) {
        this.loading = false;
        this.error = 'No project directory';
        return;
      }
      const ac = new AbortController();
      this._abort = ac;
      const q =
        '/api/project/graph?dir=' +
        encodeURIComponent(this.dir) +
        '&scopes=' +
        encodeURIComponent(this.scopesQuery()) +
        '&transitive=' +
        (this.transitive ? '1' : '0');
      try {
        const data = await get(q, { signal: ac.signal });
        if (ac.signal.aborted) return;
        this.graph = data;
        if (Array.isArray(data.availableScopes) && data.availableScopes.length) {
          this.availableScopes = data.availableScopes;
        }
        this.loading = false;
        await this.$nextTick();
        if (ac.signal.aborted) return;
        this.renderChart();
      } catch (e) {
        if (e && e.name === 'AbortError') return;
        if (ac.signal.aborted) return;
        this.loading = false;
        if (e && e.status === 401) {
          this.error = 'Authorization required to load the graph';
        } else if (e && e.error) {
          // The engine names what is broken (malformed jk.toml, missing workspace member — JK-1624).
          this.error = e.error;
        } else if (e && e.status) {
          this.error = 'Failed to load graph (HTTP ' + e.status + ')';
        } else {
          this.error = 'Failed to load graph';
        }
      }
    },
    renderChart() {
      const el = this.$refs.el;
      const g = this.graph;
      if (!el || !g || !(g.nodes || []).length) {
        if (this._chart) {
          this._chart.dispose();
          this._chart = null;
        }
        return;
      }
      if (!window.echarts) {
        this.error = 'ECharts failed to load';
        return;
      }
      if (this._chart) {
        this._chart.dispose();
        this._chart = null;
      }
      if (this._ro) {
        this._ro.disconnect();
        this._ro = null;
      }
      this._chart = echarts.init(el, null, { renderer: 'canvas' });
      this._ro = new ResizeObserver(() => this._chart && this._chart.resize());
      this._ro.observe(el);

      const dim = cssVar('--dim', '#5c6d78');
      const cn = cssVar('--cn', '#00f0ff');
      const indigo = cssVar('--indigo', '#3f51b5');
      const bright = cssVar('--bright', '#eceff1');
      const mono = cssVar('--mono', 'monospace');

      // Cyan: workspace module or declared in a selected-scope jk.toml. Indigo: transitive only.
      const shortName = (label) => {
        if (!label) return '';
        const i = label.indexOf(':');
        return i < 0 ? label : label.slice(i + 1);
      };
      const coordParts = (label) => {
        if (!label) return { group: '', name: '' };
        const i = label.indexOf(':');
        if (i < 0) return { group: '', name: label };
        return { group: label.slice(0, i), name: label.slice(i + 1) };
      };
      const nodes = (g.nodes || []).map((n) => {
        const kind = n.kind === 'transitive' ? 'transitive' : n.kind === 'module' ? 'module' : 'declared';
        const isTransitive = kind === 'transitive';
        const accent = isTransitive ? indigo : cn;
        const fullLabel = n.label || '';
        const parts = coordParts(fullLabel);
        return {
          id: n.id,
          name: shortName(fullLabel),
          fullLabel,
          group: parts.group,
          artifactName: parts.name,
          path: n.path,
          version: n.version,
          kind,
          symbolSize: Math.max(
            kind === 'module' || n.path ? 34 : 26,
            Math.min(52, 60 - (g.nodes.length > 40 ? 14 : g.nodes.length > 20 ? 8 : 0)),
          ),
          itemStyle: {
            color: isTransitive ? 'rgba(63, 81, 181, 0.28)' : 'rgba(0, 240, 255, 0.16)',
            borderColor: accent,
            borderWidth: 1.75,
          },
          label: {
            show: true,
            position: 'right',
            color: bright,
            fontSize: g.nodes.length > 50 ? 10 : 11,
            fontFamily: mono,
          },
        };
      });
      const links = (g.edges || []).map((e) => ({
        source: e.from,
        target: e.to,
        scope: e.scope,
        lineStyle: { color: dim, curveness: 0.12, width: 1.2 },
      }));
      const n = nodes.length;
      const repulsion = n > 80 ? 180 : n > 40 ? 320 : n > 15 ? 720 : 1100;
      const edgeLength = n > 80 ? 70 : n > 40 ? 110 : n > 15 ? 220 : 300;
      const tipChrome = echartsTooltipChrome(cssVar);

      this._chart.setOption(
        {
          animationDuration: n > 30 ? 200 : 400,
          tooltip: {
            show: true,
            ...tipChrome,
            formatter: (p) => {
              if (p.dataType === 'edge') {
                const s = p.data.source;
                const t = p.data.target;
                const sn = nodes.find((x) => x.id === s);
                const tn = nodes.find((x) => x.id === t);
                const sc = p.data.scope
                  ? ' <span style="opacity:.65">[' + escapeHtml(p.data.scope) + ']</span>'
                  : '';
                return (
                  escapeHtml(sn ? sn.name : s) + ' → ' + escapeHtml(tn ? tn.name : t) + sc
                );
              }
              const d = p.data || {};
              const lines = [];
              if (d.group) {
                lines.push(
                  '<span style="opacity:.7">Group:</span> ' + escapeHtml(d.group),
                );
              }
              lines.push(
                '<span style="opacity:.7">Name:</span> ' +
                  escapeHtml(d.artifactName || d.name || p.name || ''),
              );
              if (d.version) {
                lines.push(
                  '<span style="opacity:.7">Version:</span> ' + escapeHtml(d.version),
                );
              }
              const kindLabel =
                d.kind === 'transitive'
                  ? 'transitive'
                  : d.kind === 'module'
                    ? 'module'
                    : 'declared';
              lines.push('<span style="opacity:.7">Kind:</span> ' + escapeHtml(kindLabel));
              if (d.path) {
                lines.push(
                  '<span style="opacity:.7">Path:</span> ' + escapeHtml(d.path),
                );
              }
              return lines.join('<br/>');
            },
          },
          series: [
            {
              type: 'graph',
              layout: 'force',
              roam: true,
              draggable: true,
              data: nodes,
              links,
              edgeSymbol: ['none', 'arrow'],
              edgeSymbolSize: [0, 8],
              force: {
                repulsion,
                edgeLength,
                gravity: 0.03,
                friction: 0.65,
              },
              emphasis: {
                focus: 'adjacency',
                lineStyle: { width: 2, color: cn },
                itemStyle: { borderWidth: 2.5 },
              },
            },
          ],
        },
        true,
      );
    },
  },
};

/**
 * A finished record's outcome for the Projects tab.
 * FAIL steps / error diagnostics beat a cancel bit (same rule as fold.outcomeOf).
 */
function recordOutcome(r) {
  if (recordHasFailStep(r) || recordHasErrorDiag(r)) return 'failed';
  if (r.cancelled) return 'cancelled';
  return r.success ? 'success' : 'failed';
}

function recordHasFailStep(r) {
  const bad = (st) => {
    const u = String(st || '').toUpperCase();
    return u === 'FAIL' || u === 'FAILED';
  };
  for (const s of r.steps || []) if (bad(s.status)) return true;
  for (const m of r.modules || []) {
    for (const s of m.steps || []) if (bad(s.status)) return true;
  }
  return false;
}

function recordHasErrorDiag(r) {
  return (r.diagnostics || []).some((d) => d && d.severity === 'error');
}

/**
 * Parse the location hash into a route. Flat top-level views plus one detail route:
 * `#project/<projectId>` — durable identity (not an absolute path). Anything unrecognised falls
 * back to the activity feed.
 */
function routeFromHash() {
  const h = location.hash || '';
  if (h.startsWith('#project/')) {
    return { view: 'project', projectId: decodeURIComponent(h.slice('#project/'.length)), dir: null };
  }
  if (h === '#projects') return { view: 'projects', projectId: null, dir: null };
  if (h === '#status') return { view: 'status', projectId: null, dir: null };
  return { view: 'activity', projectId: null, dir: null };
}

/** Cached-vs-total step counts for one record → the build's "N of M steps served from cache". */
// The cache's estimated wall-clock benefit for a run, from the engine-computed `benefit` snapshot
// (a two-level critical-path estimate — see CacheBenefit). Replaces the old "steps skipped" count,
// which weighted a skipped 5ms no-op the same as a skipped 90s compile. `present` is false for
// records with no benefit (older records, or a build that didn't succeed cleanly).
function benefitStats(rec) {
  const b = rec.benefit;
  if (!b) return { saved: 0, uncached: 0, coveredSkips: 0, totalSkips: 0, present: false };
  return {
    saved: b.savedMillis || 0,
    uncached: b.estimatedUncachedMillis || 0,
    coveredSkips: b.coveredSkips || 0,
    totalSkips: b.totalSkips || 0,
    present: true,
  };
}

/** Compact duration for the chart tooltip (no Vue instance in reach): "820 ms" / "3.4 s" / "1m 05s". */
function fmtMillis(millis) {
  if (millis == null) return '';
  if (millis < 1000) return millis + ' ms';
  if (millis < 60_000) return (millis / 1000).toFixed(1) + ' s';
  // Floor whole seconds — rounding the remainder reached "1m 60s" (JK-1530).
  const totalSec = Math.floor(millis / 1000);
  return Math.floor(totalSec / 60) + 'm ' + String(totalSec % 60).padStart(2, '0') + 's';
}

Vue.createApp({
  data: () => ({
    view: routeFromHash().view, // 'activity' | 'projects' | 'project' | 'status'
    selectedProjectId: routeFromHash().projectId, // durable id (#project/<id>)
    selectedProjectDir: null, // checkout path resolved from project meta
    projectMeta: null, // live /api/project payload (coord + description + dir) for the open project
    // JK-1542: Dependencies panel on the Project page — closed by default; graph fetch + echarts
    // only when opened (ModuleDepGraph mounts lazily).
    projectGraphOpen: false,
    connection: 'connecting', // 'connecting' | 'live' | 'offline' | 'unauthorized'
    status: null, // the /api/status payload
    metrics: null, // the /api/metrics payload (running build aggregates), shown on the Status view
    cache: null, // /api/cache + live `cache` SSE: cache tier + artifact store breakdown
    engineLog: '', // the /api/log tail, shown on the Status view
    configPath: null, // absolute path of the machine config.toml
    configRows: [], // EffectiveUserConfig rows: {key, default, value, overridden}
    cards: [], // folded activity, newest first
    projectHistory: [], // raw /api/history records (up to 200), grouped into the Projects tab
    buildDir: '',
    buildError: null,
    browser: null, // the /api/fs payload while the workspace picker is open, else null
    browserMode: 'workspace', // 'workspace' | 'parent' (new-project parent dir)
    help: false, // the header Help/About modal
    // Blocking gate when a required token is missing/invalid — no partial dashboard (docs/webclient.md).
    authModal: false,
    authCopied: false, // brief "Copied" feedback on the Access Denied console copy button
    _authCopiedTimer: null,
    newProjectOpen: false,
    newProjectBusy: false,
    newProjectError: null,
    newProject: {
      name: '',
      group: '',
      lang: 'java',
      layout: 'simple',
      template: '',
      parentDir: '',
      executable: true,
    },
    templates: [],
    now: Date.now(), // 1s tick driving elapsed counters and "ago" stamps
    // JK-1500: single-flight keys → in-flight Promise; offline status poll backoff (ms).
    _inflight: Object.create(null),
    _offlineStatusBackoffMs: 5_000,
    _offlineStatusTimer: null,
  }),

  async mounted() {
    // Gate before hydrating: a missing/invalid token must not leave a half-working UI.
    const authed = await this.checkAuth();
    if (authed) {
      this.connectEvents();
      this.refresh();
      this.loadHistory(); // backfill past builds so a reload/restart doesn't start from an empty feed
      this.loadProjectHistory(); // so the Projects tab is populated the moment it's opened
      if (this.view === 'project' && this.selectedProjectId) this.loadProjectMeta(this.selectedProjectId);
    }
    // Back/forward and any hash change re-derive the route (openProject sets the hash, which lands here).
    window.addEventListener('hashchange', () => this.applyRoute());
    // JK-1500: pause offline REST polling while the tab is hidden; keep SSE open (orphan engine).
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) this.clearOfflineStatusFallback();
      else if (this.connection !== 'live' && this.connection !== 'unauthorized') {
        this.scheduleOfflineStatusFallback();
      }
    });
    // Local clock only — no network (relative "ago" labels).
    setInterval(() => (this.now = Date.now()), 1_000);
  },

  computed: {
    // Template short names compatible with the New-project Language field. Entries without a
    // languages list are treated as universal (legacy / fallback payloads).
    templatesForLang() {
      const lang = (this.newProject?.lang || 'java').toLowerCase();
      return (this.templates || []).filter((t) => {
        const langs = t.languages;
        if (!Array.isArray(langs) || langs.length === 0) return true;
        return langs.some((l) => String(l).toLowerCase() === lang);
      });
    },

    // Group the journal into per-project rows for the Projects tab. A computed (not a method) so it
    // recomputes only when projectHistory or the live cards change — never on the 1s clock tick, so
    // the ECharts canvases don't re-render every second. Key = durable projectId (not path/coord).
    projectsList() {
      const RECENT = 30;
      const groups = new Map(); // key → { records[] } (records arrive newest-first from /api/history)
      for (const rec of this.projectHistory || []) {
        if (!rec || !rec.dir) continue;
        const key = rec.projectId || (rec.coord && rec.coord.includes(':') ? rec.coord : rec.dir);
        let g = groups.get(key);
        if (!g) {
          g = { key, projectId: rec.projectId || key, records: [] };
          groups.set(key, g);
        }
        g.records.push(rec);
      }

      // A project with a build in flight right now shows the pulsing 'running' orb, overriding the
      // last finished outcome. Live running builds live in the SSE cards feed, keyed the same way.
      const runningKeys = new Set();
      for (const c of this.cards || []) {
        if (outcomeOf(c) !== 'running') continue;
        const key = c.projectId || (c.coord && c.coord.includes(':') ? c.coord : c.dir);
        if (key) runningKeys.add(key);
      }

      const list = [];
      for (const g of groups.values()) {
        const recs = g.records; // newest-first
        const latest = recs[0];
        const window = recs.slice(0, RECENT); // newest-first slice for stats
        const recent = window
          .slice()
          .reverse() // oldest → newest, left → right for the chart
          .map((r) => ({
            outcome: recordOutcome(r),
            millis: r.millis || 0,
            buildNumber: r.buildNumber || 0,
            finishedAt: r.finishedAt || 0,
          }));

        // Reliability over the shown window: passes / (passes + fails), cancelled excluded — so the
        // number and the coloured bars always describe the same set of builds.
        let passed = 0;
        let ran = 0;
        let totalMillis = 0;
        let timed = 0;
        for (const r of window) {
          const o = recordOutcome(r);
          if (o !== 'cancelled') {
            ran++;
            if (o === 'success') passed++;
          }
          if (r.millis) {
            totalMillis += r.millis;
            timed++;
          }
        }
        const relPct = ran ? Math.round((100 * passed) / ran) : null;
        const parts = this.coordParts(latest); // reuse the card coord-splitter (coord or dir tail)
        const running = runningKeys.has(g.key);

        list.push({
          key: g.key,
          projectId: g.projectId || latest.projectId || g.key,
          group: parts.group,
          name: parts.name,
          dir: latest.dir,
          state: running ? 'running' : recordOutcome(latest),
          buildNumber: latest.buildNumber || 0,
          // The durable per-project counter (newest build's number) is the true total; fall back to
          // the count of retained records for pre-numbering (schema-1) history.
          total: latest.buildNumber || recs.length,
          avgMillis: timed ? Math.round(totalMillis / timed) : 0,
          lastFinishedAt: latest.finishedAt || 0,
          recent,
          reliability: relPct == null ? '—' : relPct + '%',
          reliabilityClass: relPct == null ? '' : relPct >= 90 ? 'ok' : relPct >= 70 ? 'warn' : 'err',
        });
      }
      // Running projects float to the top, then most-recently-active first.
      list.sort((a, b) => (b.state === 'running') - (a.state === 'running') || b.lastFinishedAt - a.lastFinishedAt);
      return list;
    },

    // The open project's detail page: identity + aggregate metrics + a build-history table, all
    // derived from the journal filtered to this project (by projectId).
    // Recomputes only when the history, selection, or meta change — not on the 1s clock tick.
    projectDetail() {
      const id = this.selectedProjectId;
      const dir = this.selectedProjectDir || (this.projectMeta && this.projectMeta.dir);
      if (!id && !dir) return null;
      const RECENT = 30;
      const records = (this.projectHistory || []).filter((r) => {
        if (id && r.projectId) return r.projectId === id;
        return dir && r.dir === dir;
      });
      // Prefer live /api/project coord (jk.toml), else the newest journal record.
      const metaCoord = this.projectMeta && this.projectMeta.coord ? this.projectMeta.coord : null;
      const parts = this.coordParts({ coord: metaCoord || (records[0] && records[0].coord), dir });
      const base = {
        dir,
        group: parts.group,
        name: parts.name,
        description: (this.projectMeta && this.projectMeta.description) || null,
      };
      if (records.length === 0) return { ...base, empty: true, rows: [] };

      const latest = records[0];
      const window = records.slice(0, RECENT);
      let passed = 0;
      let ran = 0;
      let savedSum = 0;
      let uncachedSum = 0;
      let covSkips = 0;
      let totSkips = 0;
      const durs = [];
      for (const r of window) {
        const o = recordOutcome(r);
        if (o !== 'cancelled') {
          ran++;
          if (o === 'success') passed++;
        }
        // Cache benefit is a build-time property; aggregate only successful `build` runs so the tile
        // tells the "cache saved on builds" story cleanly (test runs get a partial estimate).
        if (r.kind === 'build') {
          const bs = benefitStats(r);
          if (bs.present) {
            savedSum += bs.saved;
            uncachedSum += bs.uncached;
            covSkips += bs.coveredSkips;
            totSkips += bs.totalSkips;
          }
        }
        if (r.millis) durs.push(r.millis);
      }
      const relPct = ran ? Math.round((100 * passed) / ran) : null;
      const cacheSavedPct = uncachedSum ? Math.round((100 * savedSum) / uncachedSum) : null;
      const cacheLowConfidence = totSkips > 0 && covSkips / totSkips < 0.5;
      durs.sort((a, b) => a - b);
      const avg = durs.length ? Math.round(durs.reduce((s, x) => s + x, 0) / durs.length) : null;

      const rows = records.slice(0, 50).map((r) => {
        const bs = benefitStats(r);
        return {
          id: r.id,
          buildNumber: r.buildNumber || null,
          outcome: recordOutcome(r),
          trigger: r.trigger || null,
          commit: r.commit || null,
          tests: r.tests || null,
          saved: bs.present && bs.uncached > 0 ? this.duration(bs.saved) : null,
          savedPct: bs.present && bs.uncached > 0 ? Math.round((100 * bs.saved) / bs.uncached) : null,
          millis: r.millis,
          finishedAt: r.finishedAt || 0,
        };
      });

      return {
        ...base,
        empty: false,
        total: latest.buildNumber || records.length,
        reliability: relPct == null ? '—' : relPct + '%',
        reliabilityClass: relPct == null ? '' : relPct >= 90 ? 'ok' : relPct >= 70 ? 'warn' : 'err',
        cacheSaved: savedSum > 0 ? this.duration(savedSum) : '—',
        cacheSavedPct: cacheSavedPct == null ? '' : (cacheLowConfidence ? '~' : '') + cacheSavedPct + '%',
        cacheLowConfidence,
        minMillis: durs.length ? durs[0] : null,
        maxMillis: durs.length ? durs[durs.length - 1] : null,
        avgMillis: avg,
        lastFinishedAt: latest.finishedAt || 0,
        rows,
      };
    },
  },

  methods: {
    /**
     * Open the blocking auth dialog and freeze live updates. When {@code clear} is true, drop a
     * stored token that the engine just rejected.
     */
    markUnauthorized({ clear = false } = {}) {
      if (clear) clearToken();
      this.connection = 'unauthorized';
      this.authModal = true;
      this.clearOfflineStatusFallback();
      if (this._eventSource) {
        try {
          this._eventSource.close();
        } catch {
          // already closed
        }
        this._eventSource = null;
      }
      // Drop partial chrome so the gated shell does not look "live" under the dialog.
      this.status = null;
      this.cache = null;
      this.metrics = null;
      this.engineLog = '';
      this.configRows = [];
      this.configPath = null;
      this.cards = [];
      this.projectHistory = [];
      this.browser = null;
      this.help = false;
      this.newProjectOpen = false;
    },

    /** True when {@code e} is a 401 — marks unauthorized and returns true so callers can stop. */
    handleHttpError(e) {
      if (e && e.status === 401) {
        // Fail closed: any 401 (missing or rejected token) opens the blocking auth dialog.
        this.markUnauthorized({ clear: !!token() });
        return true;
      }
      return false;
    },

    /**
     * Session gate on load. Fails closed when there is no token, when {@code GET /api/status} is
     * 401, or when a stored token is rejected by a gated probe. Loopback is not a free pass —
     * without a bearer the SPA must not paint Activity (recover via {@code jk web} / status URL).
     */
    async checkAuth() {
      if (!token()) {
        this.markUnauthorized({ clear: false });
        return false;
      }
      try {
        const status = await get('/api/status', { bootstrap: true });
        // Latch the engine epoch from the bootstrap payload BEFORE any gated call (JK-1774).
        // Without this the next probe carries no X-Jk-Engine-Epoch, the server 409s, and every
        // fresh tab pays a full reload. A mismatch here means a stale epoch from a previous
        // engine generation survived in sessionStorage — reload once now, before painting.
        if (noteEngineEpoch(status) === 'mismatch') {
          hardRefreshForEpoch();
          return false;
        }
      } catch (e) {
        if (e.status === 401) {
          this.markUnauthorized({ clear: true });
          return false;
        }
        // engine down / network — not an auth failure (token is present; reconnect later)
        return true;
      }
      // Prove a stored token still works (stale localStorage after rotate-token is the common case).
      try {
        await getText('/api/log?lines=1');
      } catch (e) {
        if (e.status === 401) {
          this.markUnauthorized({ clear: true });
          return false;
        }
      }
      return true;
    },

    /** (Re)open the SSE stream — closed while unauthorized so a half-authed tab cannot look live. */
    connectEvents() {
      if (this._eventSource) {
        try {
          this._eventSource.close();
        } catch {
          // already closed
        }
        this._eventSource = null;
      }
      this._eventSource = events(
        (event) => {
          if (this.authModal || this.connection === 'unauthorized') return;
          // Live chrome vitals (JK-1495+): change-gated on the server; apply without folding cards.
          if (event.type === 'status') {
            this.applyStatusEvent(event.data);
            return;
          }
          if (event.type === 'cache') {
            this.applyCacheEvent(event.data);
            return;
          }
          foldEvent(this.cards, { ...event, at: Date.now() });
          // Keep footer Builds Running in lockstep with activity (JK-1725). Prefer the post-
          // transition count on the event when present; otherwise derive from running cards.
          if (event.type === 'request-start' || event.type === 'request-finish') {
            this.applyActiveBuildPlans(event.data);
          }
          // The build number + journal record are written just after request-finish (writeJournal),
          // so re-pull history a beat later: it reconciles the live card (tagging its #number) and
          // refreshes the Projects tab. Debounced so a burst of finishes triggers one reload.
          if (event.type === 'request-finish') {
            clearTimeout(this._reconcileTimer);
            this._reconcileTimer = setTimeout(() => {
              this.loadHistory();
              this.loadProjectHistory();
              // Metrics are view-scoped (JK-1503); refresh them only where they paint.
              if (this.view === 'status' || this.view === 'projects' || this.view === 'project') {
                this.refreshMetrics();
              }
            }, 500);
          }
        },
        (state) => {
          // Unauthorized is sticky until a token is accepted — open loopback reads / SSE must not
          // clear the gate and leave a half-working UI.
          if (this.authModal || this.connection === 'unauthorized') return;
          const wasOffline = this.connection === 'offline';
          this.connection = state;
          if (state === 'live') {
            this._offlineStatusBackoffMs = 5_000;
            this.clearOfflineStatusFallback();
            if (wasOffline) {
              this.refresh(); // resync after an engine restart
              this.loadHistory(); // re-seed persisted runs (dedupe keeps this idempotent)
              this.loadProjectHistory();
            } else if (this.cards.length === 0) {
              // First open / hard-refresh: mount also loads history; re-try once the stream is live
              // in case the earlier GET raced a cold engine.
              this.loadHistory();
              this.loadProjectHistory();
            }
          } else if (state === 'offline') {
            // EventSource's own retry cadence (~3s on refused connections) is shorter than the
            // poll delay — re-arming on every onerror would perpetually reset the pending timer
            // and the fallback poll would never actually run (JK-1518).
            if (this._offlineStatusTimer == null) this.scheduleOfflineStatusFallback();
          }
        },
      );
    },

    setView(view) {
      if (this.authModal) return;
      this.view = view;
      history.replaceState(null, '', '#' + view);
      if (view === 'status') this.refresh(); // full cache + metrics + log
      if (view === 'projects') {
        this.loadProjectHistory();
        this.refreshMetrics(); // JK-1503: view-scoped, not a global timer
      }
    },

    // ---- the Projects tab (grouped /api/history + live running overlay) ----

    // The Projects tab groups the same journal payload the feed seeds from — one GET serves
    // both (each /api/history hit re-enriches up to 200 rows engine-side; JK-1750).
    async loadProjectHistory() {
      return this.loadHistory();
    },

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

    // "just now" / "5m ago" / "3h ago" / "2d ago" from an epoch-millis stamp (drives the 1s clock).
    agoMillis(ms) {
      if (!ms) return '';
      const s = Math.max(0, Math.floor((this.now - ms) / 1000));
      if (s < 60) return 'just now';
      if (s < 3600) return Math.floor(s / 60) + 'm ago';
      if (s < 86_400) return Math.floor(s / 3600) + 'h ago';
      return Math.floor(s / 86_400) + 'd ago';
    },

    // Full-breakdown elapsed for the "Last built" line: "1d 2h 3m 4s ago" — the largest non-zero unit
    // down to seconds (leading zero units dropped). Drives off the 1s clock like agoMillis.
    agoLong(ms) {
      if (!ms) return 'never';
      let s = Math.max(0, Math.floor((this.now - ms) / 1000));
      const d = Math.floor(s / 86_400); s -= d * 86_400;
      const h = Math.floor(s / 3600); s -= h * 3600;
      const m = Math.floor(s / 60); s -= m * 60;
      const parts = [];
      if (d) parts.push(d + 'd');
      if (h || parts.length) parts.push(h + 'h');
      if (m || parts.length) parts.push(m + 'm');
      parts.push(s + 's');
      return parts.join(' ') + ' ago';
    },

    // ---- the project detail page (#project/<projectId>) ----

    // Open a project's page by durable id — hash creates a history entry so Back returns to the list.
    openProject(projectId, dir) {
      if (!projectId && dir) {
        // Resolve id from a history row when only path is known (rare).
        const hit = (this.projectHistory || []).find((r) => r.dir === dir && r.projectId);
        projectId = hit ? hit.projectId : null;
      }
      if (!projectId) return;
      if (dir) this.selectedProjectDir = dir;
      location.hash = '#project/' + encodeURIComponent(projectId);
    },

    /** Activity card badge / coord → project detail (by projectId, with dir fallback). */
    openProjectFromCard(card) {
      if (!card) return;
      this.openProject(card.projectId, card.dir);
    },

    // Re-derive view + selected project from the hash, loading whatever that route needs.
    applyRoute() {
      if (this.authModal) return;
      const r = routeFromHash();
      const idChanged = r.projectId !== this.selectedProjectId;
      this.view = r.view;
      this.selectedProjectId = r.projectId;
      // Collapse the expensive graph panel when leaving project view or switching projects.
      if (r.view !== 'project' || idChanged) this.projectGraphOpen = false;
      if (r.view === 'project' && r.projectId) this.loadProjectMeta(r.projectId);
      if (r.view === 'projects') {
        this.loadProjectHistory();
        this.refreshMetrics();
      }
      if (r.view === 'status') this.refresh();
    },

    /** Toggle the Project-page Dependencies accordion (lazy graph load on open). */
    toggleProjectGraph() {
      this.projectGraphOpen = !this.projectGraphOpen;
    },

    // Live coord + description for the open project (by durable id).
    async loadProjectMeta(projectId) {
      if (this.authModal) return;
      this.projectMeta = null;
      try {
        this.projectMeta = await get('/api/project?project=' + encodeURIComponent(projectId));
        if (this.projectMeta && this.projectMeta.dir) {
          this.selectedProjectDir = this.projectMeta.dir;
        }
      } catch (e) {
        this.handleHttpError(e);
      }
      if (!this.projectHistory.length) this.loadProjectHistory(); // detail rows come from history
    },

    // The "Build" button: kick off a fresh build of this project and jump to the live Activity feed.
    buildProject(dir) {
      this.triggerBuild(dir);
      this.setView('activity');
    },

    // Human label for a build's trigger. Older (pre-capture) records have none → em dash.
    triggerLabel(trigger) {
      return { web: 'Web build', cli: 'CLI build' }[trigger] || '—';
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
      // Monotonic floor across the weighted→clock takeover (JK-1815): the clock fill starts
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
      // engine/CLI ProgressBarMode.select (JK-1816); with neither signal, weighted below.
      const useClock = (mode === 'clock' && (haveR0 || haveResidual)) || (mode === 'auto' && haveR0);
      if (useClock) {
        const base = haveR0 ? card.r0At : card.startedAt;
        const since = Math.max(0, this.now - (base != null ? base : this.now));
        // Adaptive: elapsed / (elapsed + residual). Residual firms up as work completes;
        // open-loop countdown still uses frozen r0Ms only.
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
    // Countdown freezes at "0s" on overrun; count-up is always full elapsed. No seed → count-up only.
    hasEta(card) {
      // Open-loop countdown from frozen R0 (not residual rewrites).
      return (
        this.outcome(card) === 'running' &&
        typeof card.r0Ms === 'number' &&
        card.r0Ms > 0 &&
        card.r0At != null
      );
    },
    elapsedSeconds(card) {
      if (card.startedAt == null) return 0;
      return Math.max(0, Math.floor((this.now - card.startedAt) / 1000));
    },
    etaSeconds(card) {
      // Open-loop total: elapsed-at-seed + R0 ≈ r0Ms when seed is near start; use r0Ms as remaining seed.
      if (typeof card.r0Ms !== 'number' || card.r0Ms <= 0 || card.r0At == null) {
        const total = etaTotalMillis(card);
        return total == null ? 0 : Math.max(0, Math.floor(total / 1000));
      }
      return Math.max(0, Math.floor(card.r0Ms / 1000));
    },
    etaOverdue(card) {
      // Countdown has frozen at 0s (open-loop R0 exhausted).
      if (!this.hasEta(card)) return false;
      const since = Math.max(0, this.now - card.r0At);
      return since >= card.r0Ms;
    },
    /** Count-up mid-gray only after 2s past R0 — matches CLI COUNT_UP_PROMOTE_GRACE_MS. */
    etaCountUpPromoted(card) {
      if (!this.hasEta(card)) return false;
      const since = Math.max(0, this.now - card.r0At);
      return since >= card.r0Ms + 2000;
    },
    etaCountdown(card) {
      if (!this.hasEta(card)) return '';
      const since = Math.max(0, this.now - card.r0At);
      const remMs = card.r0Ms - since;
      const rem = Math.max(0, Math.floor(remMs / 1000));
      return rem <= 0 ? '0s' : '~' + this.fmtClockSeconds(rem);
    },
    // Back-compat alias used by older snapshots/tests: bare countdown string (no "ETA " label).
    eta(card) {
      return this.etaCountdown(card);
    },

    // mm:ss-style clock mirroring the CLI's CommandManager.fmtClock: "42s" / "1m 02s" / "1h 05m 09s".
    // Whole seconds only (floor) so dual-clock faces share one boundary — not Math.round.
    fmtClock(ms) {
      return this.fmtClockSeconds(Math.max(0, Math.floor(ms / 1000)));
    },
    fmtClockSeconds(totalSec) {
      const s = Math.max(0, totalSec | 0);
      if (s < 60) return s + 's';
      const pad = (n) => String(n).padStart(2, '0');
      const m = Math.floor(s / 60);
      if (m < 60) return m + 'm ' + pad(s % 60) + 's';
      return Math.floor(m / 60) + 'h ' + pad(m % 60) + 'm ' + pad(s % 60) + 's';
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

    // Cancel a running job by jid (card.id === requestId/jid from request-start).
    async cancelCard(card) {
      if (card.id == null) return;
      try {
        await post('/api/cancel', { jid: card.id });
      } catch (e) {
        if (this.handleHttpError(e)) return;
        this.buildError = e.error || 'Cancel failed';
      }
    },

    summary(card) {
      return moduleSummary(card);
    },

    // The capitalized phase a diagnostic belongs to, joined from the module's step rows (which carry
    // the phase) by matching the diagnostic's step name. '' when the step has no phase or isn't found
    // — the failure line then reads step › … without a phase prefix.
    diagPhase(mod, d) {
      const st = ((mod && mod.steps) || []).find((s) => s.name === d.step);
      const wire = st && st.phase ? st.phase : '';
      return wire ? wire.charAt(0).toUpperCase() + wire.slice(1) : '';
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
    // (kept open) and everything else — succeeded, still-running, skipped, cancelled — which rolls
    // up under a "success details" accordion that is open while running and collapsed once done. A
    // module carrying failure output counts as failed even if its state was never marked (covers
    // request-level errors that land on a synthetic row).
    // Order (CLI parity): active first (most recently updated), finished last.
    failedModules(card) {
      return orderedModules(
        card.modules.filter((m) => m.state === 'failed' || m.diagnostics.length > 0),
      );
    },
    okModules(card) {
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

    // ---- JK-1500 live-refresh coordinator ------------------------------------

    /**
     * At most one in-flight REST call per key. Concurrent callers share the same Promise so
     * double refresh / reconnect cannot stack GETs for the same path.
     */
    fetchOnce(key, fn) {
      const inflight = this._inflight;
      if (inflight[key]) return inflight[key];
      const p = Promise.resolve()
        .then(fn)
        .finally(() => {
          if (inflight[key] === p) delete inflight[key];
        });
      inflight[key] = p;
      return p;
    },

    clearOfflineStatusFallback() {
      if (this._offlineStatusTimer != null) {
        clearTimeout(this._offlineStatusTimer);
        this._offlineStatusTimer = null;
      }
    },

    /**
     * Offline-only status poll with stepped backoff (5s → … → 30s). No-op while SSE is live or
     * the document is hidden (keep EventSource open; do not REST-hammer a background tab).
     */
    scheduleOfflineStatusFallback() {
      this.clearOfflineStatusFallback();
      if (document.hidden || this.connection === 'live' || this.connection === 'unauthorized') return;
      const delay = this._offlineStatusBackoffMs || 5_000;
      this._offlineStatusTimer = setTimeout(async () => {
        this._offlineStatusTimer = null;
        if (document.hidden || this.connection === 'live' || this.connection === 'unauthorized') return;
        // A non-200 answer (503 during an engine respawn, exhausted SSE budget, 421) kills
        // EventSource for good — it only auto-reconnects after network errors. The offline poll
        // doubles as the reconnect probe so the stream comes back once the engine is healthy
        // (JK-1518).
        if (this._eventSource && this._eventSource.readyState === EventSource.CLOSED) {
          this.connectEvents();
        }
        await this.refreshStatus();
        this._offlineStatusBackoffMs = Math.min(30_000, Math.round((this._offlineStatusBackoffMs || 5_000) * 1.5));
        this.scheduleOfflineStatusFallback();
      }, delay);
    },

    // Merge a live `status` SSE frame into this.status. Frames carry core vitals only (not httpUrl
    // / config knobs from GET /api/status) — keep REST fields when present.
    applyStatusEvent(data) {
      if (this.authModal || this.connection === 'unauthorized') return;
      if (!data || typeof data !== 'object') return;
      if (noteEngineEpoch(data) === 'mismatch') {
        hardRefreshForEpoch();
        return;
      }
      this.status = this.status ? { ...this.status, ...data } : { ...data };
    },

    /**
     * Immediate plan-count update from request-start/finish (JK-1725). When the event carries
     * {@code activeBuildPlans}, use it; otherwise count running activity cards so the footer
     * never lags the Live feed.
     */
    applyActiveBuildPlans(data) {
      const n =
        data && typeof data.activeBuildPlans === 'number'
          ? data.activeBuildPlans
          : this.runningCardCount();
      if (!this.status) this.status = {};
      this.status = { ...this.status, activeBuildPlans: n };
    },

    /** Number of Live activity cards still in flight. */
    runningCardCount() {
      let n = 0;
      for (const c of this.cards || []) {
        if (outcomeOf(c) === 'running') n++;
      }
      return n;
    },

    // Thin live `cache` frames (JK-1502) merge into the last full REST snapshot; full frames replace.
    applyCacheEvent(data) {
      if (this.authModal || this.connection === 'unauthorized') return;
      if (!data || typeof data !== 'object') return;
      if (data.thin) {
        const prev = this.cache || {};
        this.cache = { ...prev, ...data };
      } else {
        this.cache = data;
      }
    },

    // REST hydrate / offline fallback for header sysbox + footer heap / builds-running.
    async refreshStatus() {
      if (this.authModal) return;
      return this.fetchOnce('status', async () => {
        try {
          const s = await get('/api/status', { bootstrap: true });
          if (noteEngineEpoch(s) === 'mismatch') {
            hardRefreshForEpoch();
            return;
          }
          this.status = s;
        } catch (e) {
          this.handleHttpError(e);
        }
      });
    },

    /** Full cache breakdown for Status panels (REST). Footer uses thin SSE while live. */
    async refreshCache() {
      if (this.authModal) return;
      return this.fetchOnce('cache', async () => {
        try {
          this.cache = await get('/api/cache');
        } catch (e) {
          this.handleHttpError(e);
        }
      });
    },

    /**
     * Running build aggregates — view-scoped (JK-1503), not a global chrome poll. Call when
     * opening Status/Projects or after a finished build while those views are visible.
     */
    async refreshMetrics() {
      if (this.authModal) return;
      return this.fetchOnce('metrics', async () => {
        try {
          this.metrics = await get('/api/metrics');
        } catch (e) {
          this.handleHttpError(e);
        }
      });
    },

    async refreshLog() {
      if (this.authModal) return;
      return this.fetchOnce('log', async () => {
        try {
          this.engineLog = await getText('/api/log?lines=100');
        } catch (e) {
          if (this.handleHttpError(e)) return;
        }
      });
    },

    /** Effective machine config.toml (key / default / override) for the Status Configuration panel. */
    async refreshConfig() {
      if (this.authModal) return;
      return this.fetchOnce('config', async () => {
        try {
          const payload = await get('/api/config');
          this.configPath = payload.path || null;
          this.configRows = Array.isArray(payload.rows) ? payload.rows : [];
        } catch (e) {
          this.handleHttpError(e);
        }
      });
    },

    /**
     * Hydrate REST surfaces for the current view. Status always pulls full cache + metrics + log;
     * Projects pulls metrics; Activity only needs status/cache hydrate when offline or first paint.
     */
    async refresh(opts) {
      if (this.authModal) return;
      const sseLive = this.connection === 'live';
      const wantStatus = !opts || opts.status !== false;
      if (wantStatus) await this.refreshStatus();

      if (this.view === 'status') {
        await this.refreshLog();
        await this.refreshConfig();
        await this.refreshMetrics();
        await this.refreshCache(); // full breakdown for dual Status panels
        return;
      }
      if (this.view === 'projects' || this.view === 'project') {
        await this.refreshMetrics();
      }
      // Cache tier + artifact store footer: SSE while live; REST hydrate when offline or empty.
      if (!sseLive || !this.cache) {
        await this.refreshCache();
      }
    },

    // ---- Status view storage panels (/api/cache + live `cache` SSE) ----

    /** Cache-tier bytes (CLI: jk cache usage) — index + cache CAS + stamps. */
    actionCacheBytes() {
      const c = this.cache;
      if (!c) return null;
      if (c.cacheBytes != null) return c.cacheBytes;
      if (c.actionCacheBytes != null) return c.actionCacheBytes;
      return (c.actionsBytes || 0) + (c.cacheCasBytes || 0) + (c.formatStampsBytes || 0);
    },

    actionMaxBytes() {
      const c = this.cache;
      if (!c) return null;
      if (c.cacheMaxBytes != null) return c.cacheMaxBytes;
      return c.actionMaxBytes != null ? c.actionMaxBytes : null;
    },

    /** Artifact store: store CAS + repos/workers + run logs (CLI: jk storage). */
    artifactStorageBytes() {
      const c = this.cache;
      if (!c) return null;
      if (c.artifactStorageBytes != null) return c.artifactStorageBytes;
      // Run logs are state (not storage) — match jk storage usage.
      return (c.casBytes || 0) + (c.workerJarsBytes || 0);
    },

    actionCacheUtilizationPercent() {
      const used = this.actionCacheBytes();
      const max = this.actionMaxBytes();
      if (used == null || !max || max <= 0) return 0;
      return Math.min(100, Math.round((100 * used) / max));
    },

    artifactStorageUtilizationPercent() {
      const c = this.cache;
      const used = this.artifactStorageBytes();
      if (!c || used == null || !c.maxBytes || c.maxBytes <= 0) return 0;
      return Math.min(100, Math.round((100 * used) / c.maxBytes));
    },

    /** @deprecated combined meter — prefer action / artifact helpers */
    cacheUtilizationPercent() {
      return this.artifactStorageUtilizationPercent();
    },

    prunedAgo() {
      const at = this.cache?.lastPrunedMillis;
      if (!at) return 'never';
      const days = Math.floor((this.now - at) / 86_400_000);
      if (days <= 0) return 'today';
      return days === 1 ? '1 day ago' : days + ' days ago';
    },

    count(n) {
      return n == null ? '—' : n.toLocaleString();
    },
    /** Format-stamp count cap (512k default / 1M on CI); from API or local fallback. */
    formatStampsMax() {
      const m = this.cache?.formatStampsMax;
      if (m != null && m > 0) return m;
      return 512000;
    },
    formatStampsMaxLabel() {
      return this.formatStampsMax().toLocaleString();
    },
    /** Percent of stamp-file cap in use (one decimal), not byte utilization. */
    formatStampsUsedPct() {
      const n = this.cache?.formatStampsCount;
      const max = this.formatStampsMax();
      if (n == null || max <= 0) return '—';
      return ((100 * n) / max).toFixed(1);
    },

    // ---- the Status view's build-stats section (running aggregates from /api/metrics) ----

    // The machine-wide invocation rows (one per kind: build, test), stable order.
    metricsGlobal() {
      return (this.metrics || [])
        .filter((r) => r.scope === 'global')
        .sort((a, b) => a.kind.localeCompare(b.kind));
    },

    // The machine-wide per-step rows, biggest total first, capped for the panel.
    metricsSteps() {
      return (this.metrics || [])
        .filter((r) => r.scope === 'step')
        .sort((a, b) => b.okTotalMillis - a.okTotalMillis)
        .slice(0, 10);
    },

    // Sums across kinds for the KPI tiles: total runs, ok, failed+cancelled, total wall-clock.
    metricsTotals() {
      const g = this.metricsGlobal();
      const sum = (f) => g.reduce((acc, r) => acc + f(r), 0);
      const ok = sum((r) => r.okCount);
      const bad = sum((r) => r.failCount) + sum((r) => r.cancelledCount);
      return { runs: ok + bad, ok, bad, totalMillis: sum((r) => r.okTotalMillis + r.failTotalMillis) };
    },

    successRate() {
      const t = this.metricsTotals();
      return t.runs === 0 ? '—' : Math.round((100 * t.ok) / t.runs) + '%';
    },

    // Backfill the feed from the persisted journal (/api/history), reconciled with live cards.
    // Always reassign `this.cards` so a bulk seed after a hard-refresh repaints (Vue tracks the
    // array identity as well as mutations).
    async loadHistory() {
      if (this.authModal) return;
      return this.fetchOnce('history', async () => {
        try {
          const records = await get('/api/history');
          this.projectHistory = records; // shared with the Projects tab (loadProjectHistory)
          const next = this.cards.slice();
          seedFromHistory(next, records);
          this.cards = next;
        } catch (e) {
          this.handleHttpError(e);
        }
      });
    },

    // Delete a finished run from history (engine + disk), then drop its card locally.
    async deleteCard(card) {
      if (!card.historyId) return;
      try {
        await del('/api/history?id=' + encodeURIComponent(card.historyId));
        const i = this.cards.indexOf(card);
        if (i >= 0) this.cards.splice(i, 1);
      } catch (e) {
        if (this.handleHttpError(e)) return;
        this.buildError = 'Could not delete this run';
      }
    },

    // ---- the workspace picker (Browse…) ----
    async openBrowser() {
      if (this.authModal) return;
      // Start from the typed path when it looks absolute; the server defaults to $HOME otherwise.
      this.browserMode = 'workspace';
      const seed = this.buildDir.trim().startsWith('/') ? this.buildDir.trim() : null;
      await this.browseTo(seed);
    },

    async openParentBrowser() {
      if (this.authModal) return;
      this.browserMode = 'parent';
      const seed =
        this.newProject.parentDir && this.newProject.parentDir.trim().startsWith('/')
          ? this.newProject.parentDir.trim()
          : null;
      await this.browseTo(seed);
    },

    async browseTo(dir) {
      this.buildError = null;
      try {
        this.browser = await get('/api/fs' + (dir ? '?dir=' + encodeURIComponent(dir) : ''));
      } catch (e) {
        if (this.handleHttpError(e)) {
          this.browser = null;
          return;
        }
        if (this.browser) {
          // an unreadable subdir: stay where we are
        } else {
          const msg = 'Could not list that directory';
          this.buildError = msg;
          if (this.browserMode === 'parent') this.newProjectError = msg;
        }
      }
    },

    chooseBrowsed() {
      if (this.browserMode === 'parent') {
        this.newProject.parentDir = this.browser.dir;
      } else {
        this.buildDir = this.browser.dir;
      }
      this.browser = null;
      this.browserMode = 'workspace';
    },

    closeBrowser() {
      this.browser = null;
      this.browserMode = 'workspace';
    },

    // ---- New project (JK-1193 → POST /api/projects) ----
    async openNewProject() {
      if (this.authModal) return;
      this.newProjectOpen = true;
      this.newProjectError = null;
      this.newProjectBusy = false;
      // Defaults (group from git email like `jk new`, parent from history / well-known roots)
      // and the short-name catalog — load in parallel so the modal fills quickly.
      let defaults = null;
      let templates = null;
      try {
        [defaults, templates] = await Promise.all([
          get('/api/projects/defaults').catch((e) => {
            if (e.status === 401) throw e;
            return null;
          }),
          get('/api/templates').catch((e) => {
            if (e.status === 401) throw e;
            return null;
          }),
        ]);
      } catch (e) {
        if (this.handleHttpError(e)) {
          this.newProjectOpen = false;
          return;
        }
      }
      if (defaults) {
        if (defaults.group && !this.newProject.group) this.newProject.group = defaults.group;
        if (defaults.parentDir && !this.newProject.parentDir) this.newProject.parentDir = defaults.parentDir;
      } else if (!this.newProject.parentDir) {
        // Last-resort parent: $HOME from a bare fs listing (same as before defaults existed).
        try {
          const fs = await get('/api/fs');
          this.newProject.parentDir = fs.dir || '';
        } catch (e) {
          if (this.handleHttpError(e)) {
            this.newProjectOpen = false;
            return;
          }
        }
      }
      if (!this.newProject.group) this.newProject.group = 'com.example';
      // Offline fallback: mirror of the full Giter8ShortNames catalog (order + metadata) so a
      // tokenless/errored /api/templates still offers every first-party short name (JK-1458).
      this.templates = Array.isArray(templates) && templates.length
        ? templates
        : [
            { id: 'java-cli', description: 'Simple Java 25 executable (Mill SIMPLE layout)', languages: ['java'], layout: 'simple' },
            { id: 'kotlin-cli', description: 'Simple Kotlin executable (Mill SIMPLE layout)', languages: ['kotlin'], layout: 'simple' },
            { id: 'java-cli-native', description: 'Interactive Java CLI with JLine (jk native ready)', languages: ['java'], layout: 'simple' },
            { id: 'spring-boot-webmvc', description: 'Spring Boot 4.1 WebMVC + JPA/H2 + Actuator', languages: ['java'], layout: 'traditional' },
            { id: 'spring-boot-webmvc-kotlin', description: 'Kotlin Spring Boot 4.1 WebMVC + JPA/H2 + Actuator', languages: ['kotlin'], layout: 'traditional' },
            { id: 'spring-boot-mcp', description: 'Spring Boot MCP server (Spring AI, @Tool over SSE)', languages: ['java'], layout: 'traditional' },
            { id: 'quarkus', description: 'Quarkus 3.x REST application ([quarkus] plugin)', languages: ['java'], layout: 'simple' },
            { id: 'ktor-3', description: 'Ktor 3 service with Koin DI and Exposed/H2', languages: ['kotlin'], layout: 'simple' },
            { id: 'micronaut', description: 'Micronaut HTTP service (compile-time DI, Netty)', languages: ['java'], layout: 'simple' },
            { id: 'grails-8', description: 'Grails 8 REST app (GORM, H2, Groovy 5)', languages: ['groovy'], layout: 'custom' },
          ];
      this.onNewProjectLangChange(); // drop a leftover template that no longer matches Language
      // Focus Name so the user can type the app name immediately; @focus selects any existing value.
      this.$nextTick(() => {
        const el = this.$refs.nameInput;
        if (el && typeof el.focus === 'function') el.focus();
      });
    },

    // Language drives the template short-name list; clear a selection that is no longer offered.
    onNewProjectLangChange() {
      const id = this.newProject.template;
      if (!id) return;
      const ok = this.templatesForLang.some((t) => t.id === id);
      if (!ok) this.newProject.template = '';
    },

    selectedTemplateLayout() {
      const id = this.newProject.template;
      if (!id) return '';
      const t = (this.templates || []).find((x) => x.id === id);
      return (t && t.layout) || 'simple';
    },

    closeNewProject() {
      this.newProjectOpen = false;
      this.newProjectError = null;
      this.newProjectBusy = false;
    },

    async submitNewProject() {
      this.newProjectError = null;
      this.newProjectBusy = true;
      const hasTemplate = !!(this.newProject.template && this.newProject.template.trim());
      const body = {
        name: this.newProject.name.trim(),
        group: this.newProject.group.trim() || 'com.example',
        lang: this.newProject.lang,
        // Layout + executable only affect the blank scaffolder; omit noise when a template applies.
        layout: hasTemplate ? 'simple' : this.newProject.layout,
        parentDir: this.newProject.parentDir.trim(),
        executable: hasTemplate ? false : !!this.newProject.executable,
      };
      if (hasTemplate) {
        body.template = this.newProject.template.trim();
      }
      try {
        const res = await post('/api/projects', body);
        const path = res.path || res.dir;
        this.closeNewProject();
        this.newProject.name = '';
        this.newProject.template = '';
        // Keep group + parentDir so the next create is one field away from a sibling project.
        if (path) {
          // Route with the durable projectId from the create response (JK-1775) — never the
          // filesystem path: #project/<abs-path> lands a broken page in history (isValidId
          // rejects '/'). Without an id, skip the hash push instead of pushing a dead route.
          if (res.projectId) this.openProject(res.projectId, path);
          await this.triggerBuild(path);
          this.setView('activity');
        }
      } catch (e) {
        if (this.handleHttpError(e)) return;
        this.newProjectError = e.error || 'Could not create project';
      } finally {
        this.newProjectBusy = false;
      }
    },

    joinPath(dir, name) {
      return dir.endsWith('/') ? dir + name : dir + '/' + name;
    },

    async triggerBuild(dir) {
      if (this.authModal) return;
      this.buildError = null;
      const target = (dir ?? this.buildDir).trim();
      if (!target) return;
      try {
        await post('/api/build', { dir: target });
        if (dir == null) this.buildDir = '';
      } catch (e) {
        if (this.handleHttpError(e)) return;
        this.buildError = e.error || 'Build request failed';
      }
    },

    // ---- formatting helpers (templates keep zero logic beyond these) ----
    coordParts(card) {
      // "group:name" → colored segments; fall back to the dir's last two path segments.
      if (card.coord && card.coord.includes(':')) {
        const i = card.coord.indexOf(':');
        return { group: card.coord.slice(0, i), name: card.coord.slice(i + 1) };
      }
      const parts = card.dir.split('/').filter(Boolean);
      return { group: null, name: parts.length ? parts[parts.length - 1] : card.dir };
    },
    mib(bytes) {
      // Null guard: a thin cache SSE frame can land before the full REST snapshot on a hard load
      // to #status — section fields are absent and rendered "NaN MiB" without it (JK-1530).
      return bytes == null || bytes < 0 ? '—' : Math.round(bytes / 1048576) + ' MiB';
    },
    // System RAM reads naturally in GiB (total / available physical memory the engine's OS reports).
    gib(bytes) {
      return bytes == null || bytes < 0 ? '—' : (bytes / 1073741824).toFixed(1) + ' GiB';
    },
    // Header / about: whole-host CPU utilisation from /api/status systemCpuLoad ∈ [0,1].
    // The bean returns -1 until the first sample; show an em-dash rather than "0%".
    loadPercent() {
      const n = this.cpuPercent();
      return n == null ? '—' : n + '%';
    },
    /** Whole-host CPU % for the header sysbox meter, or null when not yet sampled. */
    cpuPercent() {
      const load = this.status?.systemCpuLoad;
      if (load == null || load < 0) return null;
      return Math.min(100, Math.round(load * 100));
    },
    /**
     * Host RAM used % for the header sysbox: (total − available) / total.
     * availableMemoryBytes is available headroom (see StatusSnapshot).
     */
    ramPercent() {
      const s = this.status;
      if (!s || s.totalMemoryBytes == null || s.totalMemoryBytes <= 0) return null;
      const avail = s.availableMemoryBytes ?? s.freeMemoryBytes;
      if (avail == null || avail < 0) return null;
      const used = Math.max(0, s.totalMemoryBytes - avail);
      return Math.min(100, Math.round((100 * used) / s.totalMemoryBytes));
    },
    /** Used host RAM in bytes (total − available), or null. */
    ramUsedBytes() {
      const s = this.status;
      if (!s || s.totalMemoryBytes == null || s.totalMemoryBytes <= 0) return null;
      const avail = s.availableMemoryBytes ?? s.freeMemoryBytes;
      if (avail == null || avail < 0) return null;
      return Math.max(0, s.totalMemoryBytes - avail);
    },
    /** 1-minute load average formatted to one decimal, or null when unobservable. */
    loadAverageText() {
      const avg = this.status?.systemLoadAverage;
      if (avg == null || avg < 0) return null;
      return avg.toFixed(1);
    },
    /** Sysbox percent label ({@code 6%}, {@code 51%}); em-dash when unsampled. */
    sysMeterPct(pct) {
      return pct == null ? '—' : pct + '%';
    },
    /**
     * CPU sysbox tip on the % / load text: total cores + 1m load as "cores used".
     * Example: {@code 24 cores. 1.4 cores used recently}
     */
    cpuSysTip() {
      const cores = this.status?.cores;
      const load = this.loadAverageText();
      if (cores == null && load == null) return '';
      const c = cores != null ? String(cores) : '—';
      const l = load != null ? load : '—';
      return c + ' cores. ' + l + ' cores used recently';
    },
    /**
     * RAM sysbox tip on the % / used text: total + used.
     * Example: {@code 30.4 GiB total RAM. 15.6 GiB used.}
     */
    ramSysTip() {
      const total = this.status?.totalMemoryBytes;
      const used = this.ramUsedBytes();
      if (total == null && used == null) return '';
      return this.gib(total) + ' total RAM. ' + this.gib(used) + ' used.';
    },
    /** CSS level on a sysrow: cyan default, warn >90%, crit >97%. */
    sysMeterLevel(pct) {
      if (pct == null) return '';
      if (pct > 97) return 'crit';
      if (pct > 90) return 'warn';
      return '';
    },
    // Header version pill: "v0.10.0" — the build-metadata suffix (-SNAPSHOT) is dropped for the chip.
    versionPill() {
      return this.status ? 'v' + String(this.status.version).replace(/-SNAPSHOT$/, '') : '';
    },
    /**
     * Footer "Builds Running": lockstep with Live activity while live (JK-1725).
     * Offline falls back to last status snapshot.
     */
    buildsRunning() {
      if (this.connection === 'live') return this.runningCardCount();
      return this.status?.activeBuildPlans ?? 0;
    },
    heapUsedPercent() {
      return this.percentOfMax(this.status?.heapUsedBytes);
    },
    heapCommittedPercent() {
      return this.percentOfMax(this.status?.heapCommittedBytes);
    },
    percentOfMax(bytes) {
      const s = this.status;
      if (!s || s.heapMaxBytes <= 0 || bytes == null || bytes < 0) return 0;
      return Math.min(100, Math.round((100 * bytes) / s.heapMaxBytes));
    },
    uptime() {
      const s = this.status;
      if (!s) return '—';
      const total = Math.max(0, Math.floor((this.now - s.startedAt) / 1000));
      const h = Math.floor(total / 3600);
      const m = Math.floor((total % 3600) / 60);
      return h + 'h ' + m + 'm ' + (total % 60) + 's';
    },
    elapsed(card) {
      if (card.startedAt == null) return '';
      // Full run-wide count-up from the same whole-second counter as the countdown.
      return '+' + this.fmtClockSeconds(this.elapsedSeconds(card));
    },
    ago(card) {
      if (card.finishedAt == null) return '';
      const s = Math.max(0, Math.floor((this.now - card.finishedAt) / 1000));
      if (s < 60) return 'just now';
      if (s < 3600) return Math.floor(s / 60) + 'm ago';
      return Math.floor(s / 3600) + 'h ago';
    },
    duration(millis) {
      if (millis == null) return '';
      if (millis < 1000) return millis + ' ms';
      if (millis < 60_000) return (millis / 1000).toFixed(1) + ' s';
      // Floor whole seconds — rounding the remainder reached "1m 60s" at e.g. 119,600ms (JK-1530).
      const totalSec = Math.floor(millis / 1000);
      return Math.floor(totalSec / 60) + 'm ' + (totalSec % 60) + 's';
    },
    // The run's byte counters, one row per scope (remote = network, local = build cache). Both the
    // rows and the size formatting are pure functions in fold.js so they're covered headlessly.
    ioLines(card) {
      return ioLines(card);
    },
    // Screen-reader text for the I/O breakdown; the visual tooltip is CSS hover/focus (JK-1459).
    ioSummary(card) {
      return this.ioLines(card)
        .map((l) => `${l.label}: ${fmtBytes(l.up)} up, ${fmtBytes(l.down)} down`)
        .join('; ');
    },
    bytes(n) {
      return fmtBytes(n);
    },
    shortDir(dir) {
      const parts = dir.split('/').filter(Boolean);
      return parts.length > 2 ? '…/' + parts.slice(-2).join('/') : dir;
    },
    lastSegment(dir) {
      const parts = dir.split('/').filter(Boolean);
      return parts.length ? parts[parts.length - 1] : dir;
    },
    connectionLabel() {
      return {
        connecting: 'Connecting…',
        live: 'Live',
        offline: 'Engine stopped — run any jk command to restart it',
        unauthorized: 'Access denied — run `jk web` and follow the instructions',
      }[this.connection];
    },
    /** Copy `jk web` for the Access Denied console — clipboard only, never the prompt glyph. */
    async copyJkWeb() {
      const text = 'jk web';
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(text);
        } else {
          const ta = document.createElement('textarea');
          ta.value = text;
          ta.setAttribute('readonly', '');
          ta.style.position = 'fixed';
          ta.style.left = '-9999px';
          document.body.appendChild(ta);
          ta.select();
          document.execCommand('copy');
          document.body.removeChild(ta);
        }
        this.authCopied = true;
        if (this._authCopiedTimer) clearTimeout(this._authCopiedTimer);
        this._authCopiedTimer = setTimeout(() => {
          this.authCopied = false;
          this._authCopiedTimer = null;
        }, 1500);
      } catch (_) {
        // Clipboard blocked — user can still select the command text.
      }
    },
  },
})
  .component('jk-icon', JkIcon)
  .component('phase-chain', PhaseChain)
  .component('build-bars', BuildBars)
  .component('module-dep-graph', ModuleDepGraph)
  .mount('#app');

// Themed tooltips for data-tip / title (native title= is unstyleable OS chrome — JK-1726).
installTips(document);
