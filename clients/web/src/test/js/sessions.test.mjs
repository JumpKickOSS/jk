// SPDX-License-Identifier: Apache-2.0
// The supervisor's view: origin labels, the feed grouped by session, the followed-or-pinned run on
// the project page, the pin route, and a token that rides a project route's fragment query.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const storage = () => {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
  };
};
globalThis.sessionStorage = storage();
globalThis.localStorage = storage();
globalThis.location = { hash: '', pathname: '/', search: '' };

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const { focusedRun, groupBySession, originLabel, sessionKey, triggerLabel } = await spa('sessions.js');
const { historyCard, seedFromHistory } = await spa('fold.js');
const { buildProjectHash, routeFromHash } = await spa('route.js');
const { adoptFragmentToken } = await spa('api.js');

const rec = (over) => ({
  id: 'r' + over.buildNumber,
  buildNumber: 1,
  kind: 'build',
  dir: '/ws',
  coord: 'g:a',
  projectId: 'p',
  success: true,
  running: false,
  startedAt: 1_000 * over.buildNumber,
  finishedAt: 1_000 * over.buildNumber + 500,
  millis: 500,
  trigger: 'cli',
  ...over,
});

test('origin label is trigger, then the session that asked', () => {
  assert.equal(triggerLabel('mcp'), 'MCP');
  assert.equal(triggerLabel(undefined), '—');
  assert.equal(originLabel({ trigger: 'mcp', session: 'claude-code 3f9a' }), 'MCP · claude-code 3f9a');
  assert.equal(originLabel({ trigger: 'cli' }), 'CLI');
  assert.equal(originLabel({}), '—');
  assert.equal(sessionKey({ trigger: 'mcp', session: 'claude-code 3f9a' }), 'mcp:claude-code 3f9a');
  assert.equal(sessionKey({ trigger: 'cli' }), 'cli');
});

test('history records fold their origin onto the card', () => {
  const card = historyCard(rec({ buildNumber: 4, trigger: 'mcp', session: 'claude-code 3f9a' }));
  assert.equal(card.trigger, 'mcp');
  assert.equal(card.session, 'claude-code 3f9a');
  // A live card that arrived before its journal row picks the origin up on reconcile.
  const cards = [{ ...historyCard(rec({ buildNumber: 5 })), id: 55, historyId: null, trigger: null, session: null }];
  seedFromHistory(cards, [rec({ buildNumber: 5, trigger: 'bsp', session: 'IntelliJ-BSP 7b2c' })]);
  assert.equal(cards[0].trigger, 'bsp');
  assert.equal(cards[0].session, 'IntelliJ-BSP 7b2c');
});

test('two MCP sessions and the CLI are three groups, each a timeline in run order', () => {
  const cards = [
    rec({ buildNumber: 6, trigger: 'mcp', session: 'claude-code 3f9a', success: false }),
    rec({ buildNumber: 5, trigger: 'mcp', session: 'codex 9c01' }),
    rec({ buildNumber: 4, trigger: 'mcp', session: 'claude-code 3f9a' }),
    rec({ buildNumber: 3 }),
  ].map(historyCard);
  const groups = groupBySession(cards);
  assert.deepEqual(
    groups.map((g) => g.key),
    ['mcp:claude-code 3f9a', 'mcp:codex 9c01', 'cli'],
  );
  const claude = groups[0];
  assert.equal(claude.label, 'MCP · claude-code 3f9a');
  assert.deepEqual(
    claude.runs.map((r) => [r.buildNumber, r.outcome, r.millis]),
    [
      [4, 'success', 500],
      [6, 'failed', 500],
    ],
  );
  assert.equal(claude.passed, 1);
  assert.equal(claude.failed, 1);
  assert.equal(claude.wallMillis, 1000);
});

test('the project page follows the newest run unless one is pinned', () => {
  const records = [rec({ buildNumber: 9 }), rec({ buildNumber: 8, success: false })];
  const follow = focusedRun(records, [], 0);
  assert.equal(follow.following, true);
  assert.equal(follow.buildNumber, 9);
  const live = historyCard(rec({ buildNumber: 10, running: true, finishedAt: null, millis: null }));
  const liveFocus = focusedRun(records, [live], 0);
  assert.equal(liveFocus.live, true);
  assert.equal(liveFocus.buildNumber, 10);
  const pinned = focusedRun(records, [live], 8);
  assert.equal(pinned.following, false);
  assert.equal(pinned.run.buildNumber, 8);
  // A pin the project no longer has falls back to following.
  assert.equal(focusedRun(records, [live], 77).following, true);
});

test('the pin rides the route: #project/<id>/run/<n>', () => {
  assert.equal(buildProjectHash({ projectId: 'ab12', run: 8 }), '#project/ab12/run/8');
  assert.equal(buildProjectHash({ projectId: 'ab12', run: 0 }), '#project/ab12');
  const r = routeFromHash('#project/ab12/run/8');
  assert.equal(r.view, 'project');
  assert.equal(r.projectId, 'ab12');
  assert.equal(r.run, 8);
  assert.equal(routeFromHash('#project/ab12').run, 0);
});

test('a token in a project route fragment is adopted and scrubbed, keeping the route', () => {
  assert.equal(adoptFragmentToken('#project/ab12?t=abcDEF_-123'), '#project/ab12');
  assert.equal(sessionStorage.getItem('jk-http-token'), 'abcDEF_-123');
  assert.equal(adoptFragmentToken('#t=zzz'), '');
  assert.equal(localStorage.getItem('jk-http-token'), 'zzz');
  assert.equal(adoptFragmentToken('#project/ab12/files/a.java?line=3&t=q1&col=2'), '#project/ab12/files/a.java?line=3&col=2');
  assert.equal(adoptFragmentToken('#project/ab12?line=3'), null);
  assert.equal(adoptFragmentToken(''), null);
});

test('a timeline row carries its one-chip delta beside the wall', () => {
  const delta = {
    previousBuildNumber: 6,
    previousSuccess: false,
    previousMillis: 900,
    files: { count: 1, shown: ['src/main/java/Foo.java'] },
    appeared: { count: 0, shown: [] },
    gone: { count: 1, shown: ['error · compile-java · Foo.java:3 · x'] },
  };
  const cards = [historyCard(rec({ buildNumber: 6, success: false })), historyCard(rec({ buildNumber: 7, delta }))];
  const [group] = groupBySession(cards);
  assert.equal(group.runs[0].deltaChip, null);
  assert.equal(group.runs[1].deltaChip.text, '−1 diagnostic');
  assert.equal(group.runs[1].deltaChip.cls, 'good');
  assert.equal(group.runs[1].deltaChip.tip.startsWith('since #6 · failed · '), true);
});
