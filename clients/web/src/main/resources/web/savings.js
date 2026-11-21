// SPDX-License-Identifier: Apache-2.0
// The savings panel on the Projects tab (every project) and the Project page (that one): what the
// cache, the code graph and the guards saved over the last days. GET /api/stats adds it up in the
// engine; its rows are the objects `jk stats -O jsonl` prints, overall first, then per project.

import { get } from './api.js';
import { fmtDuration } from './format.js';

/** The period the panel covers, as `jk stats` defaults to. */
export const SAVINGS_DAYS = 7;

const DAY_MS = 86_400_000;

/** The /api/stats request for the last `days` days before `now` (epoch ms). */
export function savingsQuery(now, days = SAVINGS_DAYS) {
  return '/api/stats?since=' + Math.max(0, now - days * DAY_MS);
}

/** The row for one project, by its id, else by its checkout; null when it has none in the period. */
export function savingsRow(rows, projectId, dir) {
  if (!rows) return null;
  const projects = rows.filter((r) => r.scope === 'project');
  if (projectId) {
    const byId = projects.find((r) => r.projectId === projectId);
    if (byId) return byId;
  }
  return (dir && projects.find((r) => !r.projectId && r.dir === dir)) || null;
}

/**
 * The time saved, marked as the estimate it is, and how much of it rests on measured times: the
 * cache hits the engine had a measured time for, as a share of all it counted.
 */
export function savedTime(row) {
  const hits = row.estimatedSkips || 0;
  const measured = hits ? Math.round((100 * (row.coveredSkips || 0)) / hits) : null;
  return {
    value: '~' + fmtDuration(row.savedMillis || 0),
    note: measured == null ? 'estimate' : 'estimate · ' + measured + '% measured',
  };
}

/** One tile per figure, in the order `jk stats` prints them. */
export function savingsTiles(row) {
  if (!row) return [];
  const saved = savedTime(row);
  const stops = row.guardStops || 0;
  return [
    { key: 'saved', label: 'time avoided', value: saved.value, note: saved.note,
      tip: 'Build and test time the cache avoided: an estimate from each run’s cold-cache critical path' },
    { key: 'steps', label: 'steps from cache', value: row.stepsSkipped || 0, note: (row.suitesSkipped || 0) + ' test suites',
      tip: 'Steps whose result came from the cache instead of running' },
    { key: 'classes', label: 'classes not re-run', value: row.testClassesNotRerun || 0,
      tip: 'Test classes a re-run left out because they passed under the same inputs' },
    { key: 'graph', label: 'graph answers', value: row.graphCalls || 0,
      tip: 'Code-graph questions an agent asked instead of reading files' },
    { key: 'renames', label: 'renames · edits', value: (row.renames || 0) + ' · ' + (row.edits || 0),
      tip: 'Renames applied and dependency edits made through jk' },
    { key: 'guards', label: 'guard stops', value: row.guardViolations || 0,
      note: stops ? 'in ' + stops + (stops === 1 ? ' run' : ' runs') : '',
      tip: 'Guard-rule violations a run stopped before they reached a commit' },
  ];
}

/**
 * An episode's receipt (its `saved` row from /api/sessions/<id>): the panel's tiles for the
 * figures it counted, in the same order; empty when it saved nothing yet.
 */
export function receiptTiles(row) {
  if (!row) return [];
  const counted = {
    saved: row.savedMillis,
    steps: row.stepsSkipped,
    classes: row.testClassesNotRerun,
    graph: row.graphCalls,
    renames: (row.renames || 0) + (row.edits || 0),
    guards: row.guardViolations,
  };
  return savingsTiles(row).filter((t) => (counted[t.key] || 0) > 0);
}

export const savingsData = () => ({
  savings: null, // the /api/stats rows for the last SAVINGS_DAYS days, overall first
  savingsDays: SAVINGS_DAYS,
});

export const savingsMethods = {
  async loadSavings() {
    if (this.authModal) return;
    try {
      const body = await get(savingsQuery(Date.now()));
      this.savings = (body && body.rows) || [];
    } catch (e) {
      this.handleHttpError(e);
    }
  },
  savingsTiles,
  receiptTiles,
};

export const savingsComputed = {
  /** Every project's savings, or null before the first load. */
  savingsAll() {
    return this.savings && this.savings.length ? this.savings[0] : null;
  },
  /** The open project's savings, or null when it has none in the period. */
  savingsProject() {
    return savingsRow(this.savings, this.selectedProjectId, this.projectDir());
  },
};
