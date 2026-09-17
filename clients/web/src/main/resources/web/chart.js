// SPDX-License-Identifier: Apache-2.0
// The two ECharts surfaces: the Projects tab's per-project build sparkline and the Project page's
// lazy module dependency graph. ECharts paints to a <canvas> and cannot resolve `var()`, so every
// colour is read out of :root here at render time — style.css stays the one palette.

import { echartsTooltipChrome, get } from './api.js';
import { fmtDuration } from './format.js';

const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** Escape a value for interpolation into an HTML tooltip string — dependency names/versions/paths
 * come from a project's jk.toml/jk-lock.toml, which is attacker-adjacent (a shared or malicious
 * repo someone opens in the dashboard), so they must never reach innerHTML unescaped. */
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => HTML_ESCAPES[c]);
}

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

/** Outcome → spark-bar colour, resolved from the style.css palette via cssVar (see BuildBars).
 * Fallbacks must equal :root tokens (Jk Dark) — not Material leftovers. */
const buildColors = () => ({
  success: cssVar('--ok', '#00ff87'),
  failed: cssVar('--err', '#ff3366'),
  cancelled: cssVar('--dim', '#5c6d78'),
  running: cssVar('--run', '#3d9bff'),
});

/** Fixed number of build slots the spark reserves — a single build fills 1/30 of the width. */
const BUILD_SLOTS = 30;

export const BuildBars = {
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
              return n + b.outcome + ' · ' + fmtDuration(b.millis);
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
 * Lazy dependency graph: mounted only when the Project-page Dependencies panel is open.
 * Fetches {@code GET /api/project/graph} with scope + transitive filters (same idea as
 * {@code jk tree --scopes} / {@code jk tree -t}), aborts on unmount, and only then calls
 * {@code echarts.init}.
 */
export const ModuleDepGraph = {
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
      'test-processor',
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
        <label class="check dep-transitive" data-tip="Include lockfile transitive dependencies (same as jk tree -t; off by default)">
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
      // Keep at least one scope selected (re-check main if the user clears the last box). When
      // the box being cleared IS main, the bound value goes true → true and the vnode diff never
      // patches the DOM property back, so the box is re-checked here as well.
      const next = { ...this.selectedScopes, [sc]: on };
      if (!Object.values(next).some(Boolean)) {
        next.main = true;
        if (sc === 'main' && ev && ev.target) ev.target.checked = true;
      }
      this.selectedScopes = next;
      this.scheduleLoad();
    },
    setTransitive(ev) {
      this.transitive = !!(ev && ev.target && ev.target.checked);
      this.scheduleLoad();
    },
    /** Debounced load: ticking several scope boxes in a row fires ONE request, not one per click
     * (each transitive graph walk is real server work). */
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
        this.failLoad(e);
      }
    },
    /**
     * A failed fetch describes THIS selection, so the previous selection's chart and node counts
     * must not stand in for it: the graph is dropped with the error. An in-flight reload keeps the
     * chart (see load) — only a failure clears it.
     */
    failLoad(e) {
      this.loading = false;
      this.graph = null;
      if (e && e.status === 401) {
        this.error = 'Authorization required to load the graph';
      } else if (e && e.error) {
        // The engine names what is broken (malformed jk.toml, missing workspace member).
        this.error = e.error;
      } else if (e && e.status) {
        this.error = 'Failed to load graph (HTTP ' + e.status + ')';
      } else {
        this.error = 'Failed to load graph';
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
