// SPDX-License-Identifier: Apache-2.0
// The savings panel on the Projects tab (every project) and the Project page (that one): what the
// cache, the code graph and the guards saved over the last days. GET /api/stats adds it up in the
// engine; its rows are the objects `jk stats -O jsonl` prints, overall first, then per project.
// The status bar keeps a running total of the tokens and dollars saved over all the history kept,
// read again a moment after each finished run and each agent action.

import { get } from './api.js';
import { count, fmtDuration } from './format.js';

/** The period the panel covers, as `jk stats` defaults to. */
export const SAVINGS_DAYS = 7;

const DAY_MS = 86_400_000;

/** The status bar's request: every run and action the engine keeps. */
export const TOTAL_QUERY = '/api/stats?since=0';

/** How long the status bar waits after a run or an action before it reads the total again. */
export const TOTAL_DEBOUNCE_MS = 2_000;

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

/** A token count in a few characters: 950, 12k, 1.2M. */
export function fmtTokens(n) {
  const v = n || 0;
  if (Math.abs(v) >= 1e6) return (v / 1e6).toFixed(1) + 'M';
  if (Math.abs(v) >= 1e3) return Math.round(v / 1e3) + 'k';
  return String(v);
}

/** Millionths of a dollar as dollars: three decimals under one dollar, cents above. */
export function fmtUsd(micros) {
  const usd = (micros || 0) / 1e6;
  return Math.abs(usd) < 1 ? '$' + usd.toFixed(3)
    : '$' + usd.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * The tokens and dollars a row saved the agents, marked as the estimate they are, with the date of
 * the API list prices behind the dollars and the calls left out because the bench has not measured
 * their agent or kind; null for no row.
 */
export function tokensSaved(row) {
  if (!row) return null;
  const date = row.pricesRetrieved ? ' of ' + row.pricesRetrieved : '';
  const unpriced = row.tokensUnpriced ? '; ' + fmtTokens(row.tokensUnpriced) + ' tokens on models with no price' : '';
  const n = row.callsNotMeasured || 0;
  const notMeasured = n ? '; ' + n + (n === 1 ? ' call' : ' calls') + ' not measured by the bench, not counted' : '';
  return {
    tokens: '~' + fmtTokens(row.tokensSaved),
    usd: '~' + fmtUsd(row.usdSavedMicros),
    tip: 'Estimate from bench runs, at API list prices' + date + unpriced + notMeasured,
  };
}

/** One tile per figure, in the order `jk stats` prints them. */
export function savingsTiles(row) {
  if (!row) return [];
  const saved = savedTime(row);
  const tokens = tokensSaved(row);
  const stops = row.guardStops || 0;
  return [
    { key: 'saved', label: 'time avoided', value: saved.value, note: saved.note,
      tip: 'Build and test time the cache avoided: an estimate from each run’s cold-cache critical path' },
    { key: 'tokens', label: 'tokens saved', value: tokens.tokens, note: tokens.usd + ' · estimate', tip: tokens.tip },
    { key: 'steps', label: 'steps from cache', value: row.stepsSkipped || 0, note: (row.suitesSkipped || 0) + ' test suites',
      tip: 'Steps whose result came from the cache instead of running' },
    { key: 'classes', label: 'classes not re-run', value: row.testClassesNotRerun || 0,
      tip: 'Test classes a re-run left out because they passed under the same inputs' },
    { key: 'graph', label: 'code answers', value: row.graphCalls || 0,
      tip: 'Code questions an agent asked instead of reading files' },
    { key: 'refactorings', label: 'refactorings · edits',
      value: (row.refactorings || 0) + ' · ' + (row.edits || 0),
      tip: 'Refactorings the code graph applied and dependency edits made through jk' },
    { key: 'guards', label: 'guard stops', value: row.guardViolations || 0,
      note: stops ? 'in ' + stops + (stops === 1 ? ' run' : ' runs') : '',
      tip: 'Guard rules broken and stopped before a commit, each rule in each file counted once' },
  ];
}

/**
 * An episode's receipt (its `saved` row from /api/sessions/<id>): the panel's tiles for the
 * figures it counted, in the same order, then the tokens its agent counted when the engine read
 * them; empty when it saved nothing yet.
 */
export function receiptTiles(row) {
  if (!row) return [];
  const counted = {
    saved: row.savedMillis,
    tokens: row.tokensSaved,
    steps: row.stepsSkipped,
    classes: row.testClassesNotRerun,
    graph: row.graphCalls,
    refactorings: (row.refactorings || 0) + (row.edits || 0),
    guards: row.guardViolations,
  };
  const tiles = savingsTiles(row).filter((t) => (counted[t.key] || 0) > 0);
  const tokens = row.tokens;
  if (tokens && tokens.total > 0) {
    tiles.push({ key: 'tokens', label: 'agent tokens', value: count(tokens.total),
      note: tokens.agent + '’s own count' + (tokens.wholeSession ? ', whole session' : ''),
      tip: 'Tokens the agent used in '
        + (tokens.wholeSession ? 'the whole session this episode ended in or after' : 'this episode')
        + ', read from its own session files: '
        + count(tokens.cacheRead || 0) + ' read from its cache, ' + count(tokens.output || 0) + ' output' });
  }
  return tiles;
}

export const savingsData = () => ({
  savings: null, // the /api/stats rows for the last SAVINGS_DAYS days, overall first
  savingsDays: SAVINGS_DAYS,
  savingsTotal: null, // the /api/stats rows over all the history kept: the status bar's running total
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
  async loadSavingsTotal() {
    if (this.authModal) return;
    try {
      const body = await get(TOTAL_QUERY);
      this.savingsTotal = (body && body.rows) || [];
    } catch (e) {
      this.handleHttpError(e);
    }
  },
  /** Read the running total again once runs and actions have paused for a moment. */
  scheduleSavingsTotal() {
    clearTimeout(this._savingsTotalTimer);
    this._savingsTotalTimer = setTimeout(() => this.loadSavingsTotal(), TOTAL_DEBOUNCE_MS);
  },
  savingsTiles,
  receiptTiles,
  tokensSaved,
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
  /** The running total over every project, or null before the first load. */
  savingsTotalAll() {
    return this.savingsTotal && this.savingsTotal.length ? this.savingsTotal[0] : null;
  },
  /** The open project's running total, or null off a project page or when it has none. */
  savingsTotalProject() {
    if (this.view !== 'project') return null;
    return savingsRow(this.savingsTotal, this.selectedProjectId, this.projectDir());
  },
};
