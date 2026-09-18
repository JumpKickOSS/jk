// SPDX-License-Identifier: Apache-2.0

import { token } from './api.js';

// The Coverage block on a run: the record's `coverage[]` rows — one per module, the whole-report
// LINE and BRANCH counters of its jacoco.xml — as the results file prints them under `## Coverage`:
// a percentage with its counts, an `all` row for a workspace, and signed points against the
// previous coverage run of the same project. The link opens the HTML report the engine serves
// for this run (`/report/<token>/<id>/`), the token in the path so the report's own pages resolve.

/** Covered as a percentage of covered + missed, one decimal: `85.2%`; `100.0%` of nothing. */
export function pct(covered, missed) {
  const total = covered + missed;
  return (total === 0 ? 100 : (covered * 100) / total).toFixed(1) + '%';
}

const percent = (covered, missed) => (covered + missed === 0 ? 100 : (covered * 100) / (covered + missed));

/** A percentage-point change with its sign: `+1.3`, `−0.5`, `±0.0`. */
export function signed(points) {
  const rounded = Math.round(points * 10) / 10;
  if (rounded === 0) return '±0.0';
  return (rounded > 0 ? '+' : '−') + Math.abs(rounded).toFixed(1);
}

/** `85.2% (1204/1413)`. */
const cell = (covered, missed) => pct(covered, missed) + ' (' + covered + '/' + (covered + missed) + ')';

/** The rows summed, as one row. */
function totals(rows) {
  const t = { linesCovered: 0, linesMissed: 0, branchesCovered: 0, branchesMissed: 0 };
  for (const r of rows) {
    t.linesCovered += r.linesCovered || 0;
    t.linesMissed += r.linesMissed || 0;
    t.branchesCovered += r.branchesCovered || 0;
    t.branchesMissed += r.branchesMissed || 0;
  }
  return t;
}

/** One table row: the label, both measures with counts, and the deltas against `was` (null = no baseline, 'new' = absent). */
function row(label, c, delta, was) {
  const linesDelta = !delta ? null : was ? signed(percent(c.linesCovered, c.linesMissed) - percent(was.linesCovered, was.linesMissed)) : 'new';
  const branchesDelta = !delta
    ? null
    : was
      ? signed(percent(c.branchesCovered, c.branchesMissed) - percent(was.branchesCovered, was.branchesMissed))
      : 'new';
  return {
    label,
    lines: cell(c.linesCovered, c.linesMissed),
    linesDelta,
    branches: cell(c.branchesCovered, c.branchesMissed),
    branchesDelta,
    cls: (d) => (d && d.startsWith('+') ? 'good' : d && d.startsWith('−') ? 'bad' : ''),
  };
}

/**
 * The block's model for a run's `coverage` rows against `previous` (the earlier record whose
 * coverage is the baseline, or null): `headline`, `since`, `rows`, `all` (a workspace only).
 * Deltas match modules by `dir`. Null when the run measured nothing.
 */
export function coverageSummary(coverage, previous) {
  if (!Array.isArray(coverage) || coverage.length === 0) return null;
  const before = previous && Array.isArray(previous.coverage) ? previous.coverage : [];
  const delta = before.length > 0;
  const rows = coverage.map((c) => row(c.label, c, delta, before.find((b) => b.dir === c.dir) || null));
  const t = totals(coverage);
  const all = coverage.length > 1 ? row('all', t, delta, delta ? totals(before) : null) : null;
  const headline =
    pct(t.linesCovered, t.linesMissed) +
    ' lines · ' +
    pct(t.branchesCovered, t.branchesMissed) +
    ' branches' +
    (coverage.length > 1 ? ' · ' + coverage.length + ' modules' : '');
  return { headline, since: delta ? 'Δ vs run #' + (previous.buildNumber || '?') : null, rows, all };
}

/**
 * The baseline for `run` (a record or a card with `dir` and `buildNumber`): the nearest older
 * record of the same project whose coverage is not empty — runs in between that measured nothing
 * are skipped, as the results file skips them. Null when there is none.
 */
export function previousCoverage(records, run) {
  if (!run || !run.buildNumber) return null;
  let best = null;
  for (const r of records || []) {
    if (!r || r.running || r.dir !== run.dir || !r.buildNumber || r.buildNumber >= run.buildNumber) continue;
    if (!Array.isArray(r.coverage) || r.coverage.length === 0) continue;
    if (!best || r.buildNumber > best.buildNumber) best = r;
  }
  return best;
}

/** The one-chip form for a history row or a timeline: the line percentage, the whole headline as the tip. */
export function coverageChip(coverage) {
  const s = coverageSummary(coverage, null);
  if (!s) return null;
  return { text: s.headline.split(' · ')[0], tip: s.headline };
}

/** The engine route for run `historyId`'s HTML report, the bearer `tok` in the path. */
export function reportHref(historyId, tok) {
  return '/report/' + encodeURIComponent(tok || '') + '/' + encodeURIComponent(historyId || '') + '/';
}

export const RunCoverage = {
  props: {
    coverage: { type: Array, required: true },
    previous: { type: Object, default: null },
    historyId: { type: String, default: null },
  },
  data: () => ({ open: false }),
  template: `
    <div v-if="summary" class="run-coverage" :class="{ open }">
      <div class="run-coverage-strip">
        <button type="button" class="run-coverage-head" :aria-expanded="String(open)" @click="open = !open"
                data-tip="covered lines and branches, per module; click for the table">
          <span class="lbl">coverage</span>
          <span class="run-coverage-headline">{{ summary.headline }}</span>
          <span v-if="summary.since" class="run-coverage-since">{{ summary.since }}</span>
        </button>
        <a v-if="href" class="run-coverage-link" :href="href" target="_blank" rel="noopener"
           data-tip="the JaCoCo HTML report of this run">HTML report</a>
      </div>
      <table v-if="open" class="stats run-coverage-table">
        <thead><tr><th>Module</th><th>Lines</th><th v-if="summary.since">Δ</th><th>Branches</th><th v-if="summary.since">Δ</th></tr></thead>
        <tbody>
          <tr v-for="r in summary.rows" :key="r.label" class="run-coverage-row">
            <td>{{ r.label }}</td><td class="mono">{{ r.lines }}</td>
            <td v-if="summary.since" class="mono" :class="r.cls(r.linesDelta)">{{ r.linesDelta }}</td>
            <td class="mono">{{ r.branches }}</td>
            <td v-if="summary.since" class="mono" :class="r.cls(r.branchesDelta)">{{ r.branchesDelta }}</td>
          </tr>
          <tr v-if="summary.all" class="run-coverage-row all">
            <td><b>all</b></td><td class="mono">{{ summary.all.lines }}</td>
            <td v-if="summary.since" class="mono" :class="summary.all.cls(summary.all.linesDelta)">{{ summary.all.linesDelta }}</td>
            <td class="mono">{{ summary.all.branches }}</td>
            <td v-if="summary.since" class="mono" :class="summary.all.cls(summary.all.branchesDelta)">{{ summary.all.branchesDelta }}</td>
          </tr>
        </tbody>
      </table>
    </div>`,
  computed: {
    summary() {
      return coverageSummary(this.coverage, this.previous);
    },
    href() {
      return this.historyId && token() ? reportHref(this.historyId, token()) : null;
    },
  },
};
