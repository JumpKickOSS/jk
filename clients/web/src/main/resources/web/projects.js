// SPDX-License-Identifier: Apache-2.0
// The Projects tab and the Project page: /api/history records grouped by durable project id, each
// row carrying its recent builds, its cache benefit and its live overlay. Spread into the root
// component's `computed`.

import { previousCoverage } from './coverage.js';
import { historyCard } from './fold.js';
import { outcomeOf } from './outcome.js';
import { focusedRun } from './sessions.js';

/**
 * A persisted record's outcome. There is exactly one outcome rule, {@link outcomeOf}, and it reads
 * a folded card — so a raw journal record is folded first rather than decoded a second time here.
 * A second ladder over the raw record is how the tab came to read a `steps` array the engine has
 * never written (it writes `tasks`), silently ignoring every FAIL task on the page.
 */
function recordOutcome(rec) {
  return outcomeOf(historyCard(rec));
}

/**
 * The cache's estimated wall-clock benefit for a run, from the engine-computed `benefit` snapshot —
 * a two-level critical-path estimate, not a count of skipped steps, which weighted a skipped 5ms
 * no-op the same as a skipped 90s compile. `present` is false when the record carries no benefit.
 */
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

export const projectComputed = {
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
  /**
   * The run the project page follows: the newest one (a live card first) unless the route pins a
   * build number. Folded to a card either way so the same outcome rule and module rows apply.
   */
  projectRun() {
    const id = this.selectedProjectId;
    const dir = this.selectedProjectDir || (this.projectMeta && this.projectMeta.dir);
    if (!id && !dir) return null;
    const same = (r) => (id && r.projectId ? r.projectId === id : dir && r.dir === dir);
    const records = (this.projectHistory || []).filter(same);
    const cards = (this.cards || []).filter(same);
    const focus = focusedRun(records, cards, this.pinnedRun || 0);
    if (!focus) return null;
    const card = focus.live ? focus.run : historyCard(focus.run);
    // The baseline of the Coverage block: the nearest earlier record of this project that measured.
    return { ...focus, card, outcome: outcomeOf(card), previousCoverage: previousCoverage(records, card) };
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
        session: r.session || null,
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
};
