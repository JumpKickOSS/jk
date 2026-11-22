// SPDX-License-Identifier: Apache-2.0
// The project page's Flaky tests tab: the checkout's quarantined test classes and the others that
// passed on a re-run lately, each with its recent runs, from GET /api/project/flaky (docs/user/test.md).

import { get } from './api.js';

/** What one character of a class's history means, and the cell's look. */
const MARKS = {
  '.': { kind: 'pass', tip: 'passed' },
  R: { kind: 'rerun', tip: 'passed on a re-run' },
  F: { kind: 'fail', tip: 'failed' },
  '|': { kind: 'released', tip: 'released from quarantine: the runs before it no longer count' },
};

/** How many runs a history holds: a release is not one. */
export function runCount(history) {
  return String(history || '').replaceAll('|', '').length;
}

/** The notice kind the engine adds when a run quarantines a class (QuarantineNotice). */
export const QUARANTINE_NOTICE = 'quarantine';

/**
 * One cell per run, oldest first, and a thin one where the class was released; a class with fewer
 * runs than the window is padded on the left.
 */
export function historyCells(history, runs) {
  const marks = String(history || '');
  const cells = [];
  for (let i = runCount(marks); i < runs; i++) cells.push({ kind: 'none', tip: 'no run' });
  for (const m of marks) cells.push(MARKS[m] || { kind: 'none', tip: 'unknown' });
  return cells;
}

/** The line that says when JumpKick quarantines a class on its own. */
export function policyLine(policy) {
  if (!policy || !policy.flakes) return 'JumpKick adds no class on its own: [test] quarantine = false';
  return 'quarantined at ' + policy.flakes + ' re-run passes in ' + policy.runs + ' runs';
}

/** An issue link the page may open: http(s) only, else none. */
export function issueHref(issue) {
  return /^https?:\/\//i.test(String(issue || '')) ? issue : null;
}

/** The tab's model of a GET /api/project/flaky body; null before it loads. */
export function flakyModel(body) {
  if (!body) return null;
  const runs = (body.policy && body.policy.runs) || 20;
  const rows = (body.classes || []).map((c) => {
    const dot = c.name.lastIndexOf('.');
    const q = c.quarantined || null;
    return {
      key: c.module + '\t' + c.name,
      name: c.name,
      simple: c.name.slice(dot + 1),
      module: c.module || 'every module',
      cells: historyCells(c.history, runs),
      flakes: c.flakes || 0,
      runs: runCount(c.history),
      quarantined: !!q,
      reason: (q && q.reason) || '',
      since: (q && q.since) || '',
      issue: (q && q.issue) || '',
      issueHref: q ? issueHref(q.issue) : null,
    };
  });
  return {
    file: body.file || '.jk/test/quarantine.toml',
    policy: policyLine(body.policy),
    error: body.error || '',
    quarantined: rows.filter((r) => r.quarantined).length,
    rows,
  };
}

export const flakyData = () => ({
  flaky: null, // the Flaky tests tab: { dir, body } of the last GET /api/project/flaky
});

export const flakyMethods = {
  /** Read the checkout's flaky and quarantined classes. */
  async loadProjectFlaky(dir = this.flakyFor) {
    if (this.authModal || !dir) return;
    try {
      const body = await get('/api/project/flaky?dir=' + encodeURIComponent(dir));
      if (dir === this.flakyFor) this.flaky = { dir, body };
    } catch (e) {
      this.handleHttpError(e);
    }
  },
  /** A `notice` frame: a class was quarantined, so the open tab reads its list again. */
  flakyNoticed(frame) {
    const n = frame && frame.change === 'added' && frame.notice;
    if (n && n.kind === QUARANTINE_NOTICE && this.flakyFor) this.loadProjectFlaky();
  },
};

export const flakyComputed = {
  /** The checkout the open Flaky tests tab shows, or null when that tab is not open. */
  flakyFor() {
    return this.view === 'project' && this.projectTab === 'flaky' ? this.projectDir() : null;
  },
  /** The tab's model for the checkout it shows; null while it loads. */
  projectFlaky() {
    const f = this.flaky;
    return f && f.dir === this.flakyFor ? flakyModel(f.body) : null;
  },
};
