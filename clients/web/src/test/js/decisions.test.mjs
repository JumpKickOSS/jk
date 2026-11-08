// SPDX-License-Identifier: Apache-2.0
// The decision page's view model and the Needs-you banner rows: what a decision shows, its
// answers, and the route that opens it.
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
const { answerBody, answerOptions, decisionHash, decisionView, foldDecision, leftLabel, openDecisions } =
  await spa('decisions.js');
const { routeFromHash } = await spa('route.js');

const form = {
  id: 'disk-clean',
  title: 'Clean the cache?',
  steps: [
    {
      id: 'decision',
      title: 'Decision',
      fields: [
        {
          key: 'answer',
          label: 'Answer',
          kind: 'choice',
          options: [
            { id: 'yes', label: 'Yes, clean it' },
            { id: 'no', label: 'No, leave it as it is' },
            { id: 'show', label: 'Show me the details (opens the dashboard)' },
          ],
        },
      ],
    },
  ],
};

const decision = (over) => ({
  id: 'd1',
  kind: 'disk-clean',
  question: 'Clean the cache?',
  detail: ['~/.jk/cache 2.1 GiB'],
  command: 'jk decide d1',
  form,
  label: 'claude-code 3f9a',
  project: '/ws/app',
  createdAt: 1000,
  deadline: 1000 + 30 * 60_000,
  state: 'open',
  route: '#decision/d1',
  ...over,
});

test('the banner lists open decisions newest first, linking each to its page', () => {
  const rows = openDecisions(
    [decision({ id: 'old', createdAt: 1, deadline: 500 }), decision({ id: 'done', state: 'accepted' }), decision({ id: 'new', createdAt: 2000 })],
    1000 + 5 * 60_000,
  );
  assert.deepEqual(
    rows.map((r) => r.id),
    ['new', 'old'],
  );
  assert.equal(rows[0].href, '#decision/new');
  assert.equal(rows[0].who, 'claude-code 3f9a');
  assert.equal(rows[1].left, '');
  assert.equal(openDecisions(null).length, 0);
});

test('the time left reads in minutes, then seconds, then nothing', () => {
  assert.equal(leftLabel(10 * 60_000, 0), '10 min left');
  assert.equal(leftLabel(30_000, 0), '30 s left');
  assert.equal(leftLabel(0, 5), '');
});

test('an SSE decision replaces the one with its id or joins the list', () => {
  const list = [decision({ id: 'a' }), decision({ id: 'b' })];
  const settled = foldDecision(list, decision({ id: 'b', state: 'declined' }));
  assert.equal(settled.find((d) => d.id === 'b').state, 'declined');
  assert.equal(list[1].state, 'open', 'the input is not mutated');
  assert.deepEqual(
    foldDecision(list, decision({ id: 'c' })).map((d) => d.id),
    ['c', 'a', 'b'],
  );
});

test('the dashboard answers any open decision with its form choices, show left out', () => {
  const view = decisionView(decision());
  assert.equal(view.open, true);
  assert.equal(view.command, 'jk decide d1');
  assert.equal('canAccept' in view, false, 'nothing gates an answer here');
  assert.deepEqual(
    view.options.map((o) => o.id),
    ['yes', 'no'],
  );
  assert.deepEqual(answerBody('accept', 'yes'), { action: 'accept', answers: { answer: 'yes' } });
  assert.deepEqual(answerBody('decline', 'yes'), { action: 'decline' });
  assert.deepEqual(answerOptions({}), []);
});

test('a settled decision says how and where it was settled, and takes no answer', () => {
  const view = decisionView(decision({ state: 'accepted', answeredBy: 'cli' }));
  assert.equal(view.open, false);
  assert.equal(view.answeredBy, 'jk decide');
});

test('the route opens one decision', () => {
  const r = routeFromHash('#decision/abc123?t=token');
  assert.equal(r.view, 'decision');
  assert.equal(r.decisionId, 'abc123');
  assert.equal(decisionHash('abc123'), '#decision/abc123');
  assert.equal(routeFromHash('#decision/').view, 'console');
});
