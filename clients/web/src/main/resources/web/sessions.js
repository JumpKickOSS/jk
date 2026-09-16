// SPDX-License-Identifier: Apache-2.0
// The supervisor's view of who asked: a run's origin label, the Activity feed grouped by session,
// and the project page's followed-or-pinned run. Pure functions over folded cards and journal
// records (no DOM), plus the root-component mixins that spread them into computed/methods.

import { outcomeOf } from './outcome.js';

/** Human label for a run's trigger — the same vocabulary the results header prints. */
export const TRIGGER_LABEL = Object.freeze({ cli: 'CLI', mcp: 'MCP', web: 'Web', bsp: 'BSP', ci: 'CI' });

export function triggerLabel(trigger) {
  if (!trigger) return '—';
  return TRIGGER_LABEL[trigger] || trigger;
}

/**
 * `MCP · claude-code 3f9a`: trigger, then the session that asked when the record names one. The
 * same facts as `trigger: mcp · session: claude-code 3f9a` in jk-results.md.
 */
export function originLabel(run) {
  if (!run || !run.trigger) return '—';
  const t = triggerLabel(run.trigger);
  return run.session ? t + ' · ' + run.session : t;
}

/** The grouping key: one trigger with no session (every CLI run) is one group; each session is its own. */
export function sessionKey(run) {
  if (!run || !run.trigger) return '?';
  return run.session ? run.trigger + ':' + run.session : run.trigger;
}

/**
 * Cards grouped by session, newest group first (by its newest run). Each group carries its
 * timeline — the runs in the order they happened, oldest first — with the outcome and wall per run,
 * and the tallies the group header prints.
 */
export function groupBySession(cards) {
  const groups = new Map();
  for (const card of cards || []) {
    if (!card) continue;
    const key = sessionKey(card);
    let g = groups.get(key);
    if (!g) {
      g = {
        key,
        trigger: card.trigger || null,
        session: card.session || null,
        label: originLabel(card),
        runs: [],
        passed: 0,
        failed: 0,
        cancelled: 0,
        running: 0,
        wallMillis: 0,
        newestAt: 0,
      };
      groups.set(key, g);
    }
    const outcome = outcomeOf(card);
    g.runs.push({
      id: card.id,
      historyId: card.historyId || null,
      buildNumber: card.buildNumber || null,
      kind: card.kind || 'build',
      coord: card.coord || null,
      dir: card.dir || '',
      projectId: card.projectId || null,
      outcome,
      millis: outcome === 'running' ? null : card.millis ?? null,
      startedAt: card.startedAt ?? null,
      finishedAt: card.finishedAt ?? null,
    });
    if (outcome === 'success') g.passed++;
    else if (outcome === 'failed') g.failed++;
    else if (outcome === 'cancelled') g.cancelled++;
    else if (outcome === 'running') g.running++;
    if (typeof card.millis === 'number' && outcome !== 'running') g.wallMillis += card.millis;
    const at = card.finishedAt ?? card.startedAt ?? 0;
    if (at > g.newestAt) g.newestAt = at;
  }
  const list = [...groups.values()];
  for (const g of list) {
    g.runs.sort((a, b) => (a.startedAt ?? a.finishedAt ?? 0) - (b.startedAt ?? b.finishedAt ?? 0));
  }
  list.sort((a, b) => (b.running > 0) - (a.running > 0) || b.newestAt - a.newestAt);
  return list;
}

/**
 * The run the project page shows: the pinned build number when the route names one and the
 * project has it, else the newest run — a live card when one is in flight, else the newest record.
 * `following` is true when nothing is pinned.
 */
export function focusedRun(records, cards, pinnedBuildNumber) {
  const recs = records || [];
  if (pinnedBuildNumber) {
    const live = (cards || []).find((c) => c.buildNumber === pinnedBuildNumber);
    const rec = recs.find((r) => r.buildNumber === pinnedBuildNumber);
    const run = live || rec || null;
    if (run) return { run, live: !!live, following: false, buildNumber: pinnedBuildNumber };
  }
  const running = (cards || []).find((c) => outcomeOf(c) === 'running');
  if (running) return { run: running, live: true, following: true, buildNumber: running.buildNumber || null };
  const newest = recs[0] || null;
  return newest ? { run: newest, live: false, following: true, buildNumber: newest.buildNumber || null } : null;
}

/** Root-component `computed`: the grouped feed. */
export const sessionComputed = {
  sessionGroups() {
    return groupBySession(this.cards);
  },
};

/** Root-component `methods`: labels and the pin / group toggles the templates call. */
export const sessionMethods = {
  triggerLabel(trigger) {
    return triggerLabel(trigger);
  },
  originLabel(run) {
    return originLabel(run);
  },
  toggleSessionGrouping() {
    this.groupBySession = !this.groupBySession;
  },
  /** Pin one run on the project page (a history row click); the route carries it so a reload keeps it. */
  pinRun(buildNumber) {
    if (!this.selectedProjectId || !buildNumber) return;
    location.hash = this.projectRunHash(this.selectedProjectId, buildNumber);
  },
  /** Back to following the newest run. */
  unpinRun() {
    if (!this.selectedProjectId) return;
    location.hash = this.projectRunHash(this.selectedProjectId, 0);
  },
};
