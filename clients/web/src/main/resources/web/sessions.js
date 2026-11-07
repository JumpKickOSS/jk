// SPDX-License-Identifier: Apache-2.0
// Who asked, and which run the project page shows: a run's origin label and the followed-or-pinned
// run. Pure functions over folded cards and journal records (no DOM), plus the root-component
// methods the templates call. The sessions themselves are the console's (console.js).

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

/** Root-component `methods`: labels and the pin route the templates call. */
export const sessionMethods = {
  triggerLabel(trigger) {
    return triggerLabel(trigger);
  },
  originLabel(run) {
    return originLabel(run);
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
