// SPDX-License-Identifier: Apache-2.0
// The decision dialog: what a decision shows, the answers as buttons, the route that opens it over
// the page, and the rules that closing is not answering and that another surface's answer closes it.
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

/** Every request the dialog sends, answered by `reply(method, url, body)`. */
const requests = [];
let reply = () => ({ status: 200, body: {} });
globalThis.fetch = async (url, opts = {}) => {
  const method = opts.method || 'GET';
  const body = opts.body ? JSON.parse(opts.body) : null;
  requests.push({ method, url, body });
  const r = reply(method, url, body);
  return {
    ok: r.status < 300,
    status: r.status,
    json: async () => r.body,
    clone() {
      return this;
    },
  };
};
const replaced = [];
globalThis.history = { replaceState: (_s, _t, url) => replaced.push(url) };

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const {
  DECLINE_BODY,
  DecisionDialog,
  SETTLED_CLOSE_MS,
  batchBody,
  choiceBody,
  decisionData,
  decisionHash,
  decisionMethods,
  decisionView,
  leftLabel,
  settledLine,
} = await spa('decisions.js');
const { routeFromHash } = await spa('route.js');

const confirm = (options) => ({
  id: 'disk-clean',
  title: 'Delete 3 caches?',
  steps: [{ id: 'decision', title: 'Decision', fields: [{ key: 'answer', label: 'Answer', kind: 'choice', required: true, options }] }],
});
const yesNoShow = confirm([
  { id: 'yes', label: 'Yes, delete them' },
  { id: 'no', label: 'No, leave it as it is' },
  { id: 'show', label: 'Show me the details (opens the dashboard)' },
]);

const decision = (over) => ({
  id: 'd1',
  kind: 'disk-clean',
  question: 'Delete 3 caches?',
  detail: ['~/.jk/cache 2.1 GiB'],
  command: 'jk decide d1',
  form: yesNoShow,
  label: 'claude-code 3f9a',
  project: '/ws/app',
  createdAt: 1000,
  deadline: 1000 + 30 * 60_000,
  state: 'open',
  route: '#decision/d1',
  ...over,
});

/** A detached dialog: its data, methods and computed view, no Vue; `emitted` records its events. */
function dialog(id = 'd1') {
  const v = Object.assign(Object.create(DecisionDialog.methods), DecisionDialog.data(), { id, emitted: [] });
  v.$emit = (name) => v.emitted.push(name);
  Object.defineProperty(v, 'view', { get: () => DecisionDialog.computed.view.call(v) });
  return v;
}

/** Runs `fn` with setTimeout recorded, not scheduled; returns the delays asked for and their callbacks. */
async function withTimers(fn) {
  const real = globalThis.setTimeout;
  const timers = [];
  globalThis.setTimeout = (cb, ms) => timers.push({ cb, ms }) && timers.length;
  try {
    await fn();
  } finally {
    globalThis.setTimeout = real;
  }
  return timers;
}

test('a confirmation is one button per choice, labelled with it, in the order the agent host shows them', () => {
  const view = decisionView(decision());
  assert.equal(view.mode, 'choices');
  assert.equal(view.layout, 'row', 'two choices sit side by side');
  assert.deepEqual(
    view.choices.map((c) => c.label),
    ['Yes, delete them', 'No, leave it as it is'],
    'the dashboard-only "show" choice is left out',
  );
  assert.deepEqual(view.fields, [], 'no radios, no fields');
  assert.equal(view.question, 'Delete 3 caches?');
});

test('each choice button records its own outcome: no declines, yes accepts', () => {
  const [yes, no] = decisionView(decision()).choices;
  assert.equal(yes.declines, false);
  assert.equal(no.declines, true);
  assert.deepEqual(choiceBody(yes), { action: 'accept', answers: { answer: 'yes' } });
  assert.deepEqual(choiceBody(no), { action: 'accept', answers: { answer: 'no' } }, 'the engine settles answer=no as declined');
});

test('a list of choices is a column of buttons; a form with real inputs keeps fields, a submit and a decline', () => {
  const list = decisionView(decision({ form: confirm(['a', 'b', { id: 'c', label: 'Cee' }]) }));
  assert.equal(list.layout, 'column');
  assert.deepEqual(
    list.choices.map((c) => [c.id, c.label]),
    [['a', 'a'], ['b', 'b'], ['c', 'Cee']],
  );
  const form = {
    id: 'f',
    title: 'Name it',
    steps: [{ id: 's', title: 'S', fields: [{ key: 'name', label: 'Name', kind: 'text', default: 'x' }, { key: 'force', label: 'Force', kind: 'bool' }] }],
  };
  const view = decisionView(decision({ form }));
  assert.equal(view.mode, 'form');
  assert.deepEqual(
    view.fields.map((f) => f.key),
    ['name', 'force'],
  );
  assert.deepEqual(DECLINE_BODY, { action: 'decline' });
});

const setup = (over) =>
  decision({
    id: 's1',
    kind: 'setup',
    label: undefined,
    question: 'Set up this machine for faster builds',
    detail: ['Windows shows one prompt for the changes that need an administrator.'],
    form: {
      id: 'setup',
      title: 'Set up this machine for faster builds',
      steps: [
        {
          id: 'decision',
          title: 'Decision',
          fields: [
            {
              key: 'changes',
              label: 'Changes',
              kind: 'multi',
              required: true,
              default: ['exclude-a', 'long-paths'],
              options: [
                { id: 'exclude-a', label: 'Exclude C:\\Users\\ada\\.jk from Defender', help: 'Measured here: 1.9 s per 1,000 build files with the scan.' },
                { id: 'long-paths', label: 'Turn on long paths in Windows', help: 'Windows stops paths at 260 characters.' },
              ],
            },
            {
              key: 'answer',
              label: 'Answer',
              kind: 'choice',
              required: true,
              options: [
                { id: 'yes', label: 'Apply the ticked changes (one Windows prompt)' },
                { id: 'no', label: 'No, leave it as it is' },
              ],
            },
          ],
        },
      ],
    },
    ...over,
  });

test('a batch is a checklist of changes, each with what it does and why, then the answer buttons', () => {
  const view = decisionView(setup());
  assert.equal(view.mode, 'batch');
  assert.equal(view.who, 'JumpKick');
  assert.deepEqual(
    view.fields[0].options.map((o) => [o.id, o.label, o.help]),
    [
      ['exclude-a', 'Exclude C:\\Users\\ada\\.jk from Defender', 'Measured here: 1.9 s per 1,000 build files with the scan.'],
      ['long-paths', 'Turn on long paths in Windows', 'Windows stops paths at 260 characters.'],
    ],
  );
  assert.deepEqual(
    view.choices.map((c) => [c.id, c.declines]),
    [
      ['yes', false],
      ['no', true],
    ],
  );
});

test('every change starts ticked; yes sends the ticked ones in form order, no declines', async () => {
  requests.length = 0;
  reply = (method) => ({ status: 200, body: method === 'POST' ? setup({ state: 'accepted', answeredBy: 'dashboard' }) : setup() });
  const v = dialog('s1');
  await v.load();
  assert.equal(v.ticked('changes', 'exclude-a'), true);
  assert.equal(v.ticked('changes', 'long-paths'), true);
  v.toggleMulti('changes', 'exclude-a');
  v.toggleMulti('changes', 'exclude-a');
  assert.deepEqual(batchBody(v.view.choices[0], v.values, v.view), {
    action: 'accept',
    answers: { answer: 'yes', changes: ['exclude-a', 'long-paths'] },
  });
  v.toggleMulti('changes', 'long-paths');
  await v.answer(v.view.choices[0]);
  assert.deepEqual(requests.at(-1).body, { action: 'accept', answers: { answer: 'yes', changes: ['exclude-a'] } });
  assert.deepEqual(batchBody(v.view.choices[1], v.values, v.view), DECLINE_BODY);
});

test('a settled decision says how and by whom', () => {
  assert.equal(settledLine(decision({ state: 'declined', answeredBy: 'cli' })), 'Settled by jk decide: declined.');
  assert.equal(settledLine(decision({ state: 'accepted', answeredBy: 'host-form' })), "Settled by the agent's host: accepted.");
  assert.equal(settledLine(decision({ state: 'expired' })), 'Expired: nobody answered in time.');
  assert.equal(settledLine(decision()), '');
  assert.equal(decisionView(decision({ state: 'accepted' })).open, false);
});

test('the time left reads in minutes, then seconds, then nothing', () => {
  assert.equal(leftLabel(10 * 60_000, 0), '10 min left');
  assert.equal(leftLabel(30_000, 0), '30 s left');
  assert.equal(leftLabel(0, 5), '');
});

test('#decision/<id> opens the dialog over the page under it, and closing puts that page back', () => {
  const r = routeFromHash('#decision/abc123');
  assert.equal(r.decisionId, 'abc123');
  assert.equal(r.view, 'console', 'a link that opens the dashboard lands on the console under the dialog');
  assert.equal(decisionHash('abc 1'), '#decision/abc%201');
  assert.equal(routeFromHash('#decision/').decisionId, undefined);

  const root = Object.assign(Object.create(decisionMethods), decisionData({}), { view: 'projects' });
  assert.equal(root.routeDecision(routeFromHash('#projects')), false);
  root.bellOpen = true;
  const change = { oldURL: 'http://127.0.0.1:8910/#projects', newURL: 'http://127.0.0.1:8910/#decision/abc123' };
  assert.equal(root.routeDecision(routeFromHash('#decision/abc123'), change), true, 'the page is left as it is');
  assert.equal(root.decisionId, 'abc123');
  assert.equal(root.bellOpen, false, 'a bell entry folds the menu as it opens');
  root.closeDecision();
  assert.equal(root.decisionId, null);
  assert.equal(replaced.at(-1), '#projects');

  // A notification's link opened in a tab already showing the dashboard: the token leaves the URL.
  globalThis.location.hash = '#decision/abc123?t=tok_1';
  root.routeDecision(routeFromHash(globalThis.location.hash), { oldURL: 'http://127.0.0.1:8910/#console' });
  assert.equal(replaced.at(-1), '/#decision/abc123');
  assert.equal(globalThis.sessionStorage.getItem('jk-http-token'), 'tok_1');
  globalThis.location.hash = '';
});

test('closing the dialog is not an answer: nothing is sent and the decision stays open', async () => {
  requests.length = 0;
  reply = () => ({ status: 200, body: decision() });
  const v = dialog();
  await v.load();
  assert.equal(v.view.open, true);
  v.dismiss();
  assert.deepEqual(v.emitted, ['close']);
  assert.deepEqual(
    requests.map((r) => r.method),
    ['GET'],
    'Esc, the close button and the backdrop send no answer',
  );
  // The bell still counts it: nothing folded it away.
  const root = Object.assign(Object.create(decisionMethods), decisionData({ decisionId: 'd1' }), { decisions: [decision()] });
  root.closeDecision();
  assert.equal(root.decisions.length, 1);
});

test('a choice button posts its answer and closes the dialog', async () => {
  requests.length = 0;
  reply = (method) => ({ status: 200, body: method === 'POST' ? decision({ state: 'declined', answeredBy: 'dashboard' }) : decision() });
  const v = dialog();
  await v.load();
  await v.answer(v.view.choices[1]);
  assert.deepEqual(requests.at(-1), { method: 'POST', url: '/api/decisions/d1', body: { action: 'accept', answers: { answer: 'no' } } });
  assert.deepEqual(v.emitted, ['close']);
});

test('an answer from another surface shows "settled by" and closes the dialog', async () => {
  reply = () => ({ status: 200, body: decision() });
  const v = dialog();
  await v.load();
  const timers = await withTimers(() => {
    DecisionDialog.watch.event.call(v, decision({ id: 'other', state: 'accepted' }));
    assert.equal(v.view.open, true, "another decision's frame changes nothing");
    DecisionDialog.watch.event.call(v, decision({ state: 'declined', answeredBy: 'cli' }));
  });
  assert.equal(v.view.settled, 'Settled by jk decide: declined.');
  assert.equal(timers.length, 1);
  assert.equal(timers[0].ms, SETTLED_CLOSE_MS);
  timers[0].cb();
  assert.deepEqual(v.emitted, ['close']);
});

test('a 409 means another surface answered first: the dialog says how and closes', async () => {
  const settled = decision({ state: 'accepted', answeredBy: 'host-form' });
  reply = (method) => (method === 'POST' ? { status: 409, body: { error: 'already accepted', decision: settled } } : { status: 200, body: settled });
  const v = dialog();
  v.decision = decision();
  const timers = await withTimers(() => v.answer(v.view.choices[0]));
  assert.equal(v.view.settled, "Settled by the agent's host: accepted.");
  assert.equal(timers.length, 1);
  timers[0].cb();
  assert.deepEqual(v.emitted, ['close']);
});
