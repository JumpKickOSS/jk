// SPDX-License-Identifier: Apache-2.0
// The decision page's view model and the Needs-you banner rows: what a decision shows, which
// answers this surface may give, and the route that opens it.
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
const { adoptHostKey, answerBody, answerOptions, decisionHash, decisionView, foldDecision, leftLabel, openDecisions } =
  await spa('decisions.js');
const { routeFromHash } = await spa('route.js');

const form = {
  id: 'update',
  title: 'Apply 1 dependency change?',
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
            { id: 'yes', label: 'Yes, update and relock' },
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
  kind: 'update',
  question: 'Apply 1 dependency change that needs a person in /ws/app?',
  detail: ['jackson 2.18.2 → 3.0.0'],
  command: 'jk decide d1',
  trust: true,
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
  assert.equal(rows[0].trust, true);
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

test('a trust-changing decision can be declined here but accepted only in a terminal', () => {
  const view = decisionView(decision());
  assert.equal(view.canAccept, false);
  assert.equal(view.canDecline, true);
  assert.equal(view.command, 'jk decide d1');
  assert.match(view.note, /terminal/);
});

test("the page the host opened, holding the link's key, may accept a trust-changing decision", () => {
  const view = decisionView(decision(), 'k3y');
  assert.equal(view.canAccept, true);
  assert.equal(view.note, '');
  assert.deepEqual(answerBody('accept', 'yes', 'k3y'), { action: 'accept', answers: { answer: 'yes' }, key: 'k3y' });
});

test('any other decision is answered from the dashboard with its form choices, show left out', () => {
  const view = decisionView(decision({ trust: false, kind: 'disk-clean' }));
  assert.equal(view.canAccept, true);
  assert.deepEqual(
    view.options.map((o) => o.id),
    ['yes', 'no'],
  );
  assert.deepEqual(answerBody('decline', 'yes'), { action: 'decline' });
  assert.deepEqual(answerOptions({}), []);
});

test('a settled decision says how and where it was settled, and takes no answer', () => {
  const view = decisionView(decision({ state: 'accepted', answeredBy: 'cli' }));
  assert.equal(view.open, false);
  assert.equal(view.canAccept, false);
  assert.equal(view.canDecline, false);
  assert.equal(view.answeredBy, 'jk decide in a terminal');
});

test('the route opens one decision and the host key is kept for the tab', () => {
  const r = routeFromHash('#decision/abc123?k=secret');
  assert.equal(r.view, 'decision');
  assert.equal(r.decisionId, 'abc123');
  assert.equal(decisionHash('abc123'), '#decision/abc123');
  const tab = storage();
  assert.equal(adoptHostKey('abc123', '#decision/abc123?k=secret', tab), 'secret');
  assert.equal(adoptHostKey('abc123', '#decision/abc123', tab), 'secret', 'a reload after the scrub keeps it');
  assert.equal(adoptHostKey('other', '#decision/other', tab), '');
  assert.equal(routeFromHash('#decision/').view, 'activity');
});
