// SPDX-License-Identifier: Apache-2.0
// Telling the person once: the tab reports its visibility and permission over its stream, a
// notification the engine routes here shows only from a hidden tab that may notify, a visible tab
// rings the bell instead, a settled decision's notification closes by its tag, and the bell asks for
// permission on a click only.
import { test, beforeEach } from 'node:test';
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
globalThis.location = { hash: '', hostname: '127.0.0.1', pathname: '/', search: '' };
globalThis.document = { visibilityState: 'hidden', hidden: true, addEventListener() {}, removeEventListener() {} };

/** Every request the page sends, and what a GET of a path answers (`{}` when unset). */
const posts = [];
const answers = new Map();
globalThis.fetch = async (url, opts = {}) => {
  posts.push({ url, method: opts.method || 'GET', body: opts.body ? JSON.parse(opts.body) : null });
  const json = (opts.method || 'GET') === 'GET' && answers.has(url) ? answers.get(url) : {};
  return { ok: true, status: 200, json: async () => json, clone() { return this; } };
};

/** A browser's Notification: records what was shown and closed; `permission` is settable. */
class FakeNotification {
  static permission = 'granted';
  static shown = [];
  static asked = 0;
  static answer = 'granted';
  static async requestPermission() {
    FakeNotification.asked++;
    FakeNotification.permission = FakeNotification.answer;
    return FakeNotification.answer;
  }
  constructor(title, options) {
    this.title = title;
    this.options = options;
    this.closed = false;
    FakeNotification.shown.push(this);
  }
  close() {
    this.closed = true;
    if (this.onclose) this.onclose();
  }
}
globalThis.Notification = FakeNotification;

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const {
  NOTIFY_TITLE,
  WORKER_MESSAGE,
  askLine,
  maxActions,
  notificationFor,
  notificationsFor,
  notifyData,
  notifyMethods,
  pageChannel,
  pageNotifications,
  tabState,
} = await spa('notify.js');
const { SSE } = await spa('wire.js');

const decision = (over = {}) => ({ id: 'd1', question: "Clean jk's cache?", label: 'claude-code 3f9a', state: 'open', ...over });

/** The root component's share, with the bits of the root it touches. */
function root() {
  const vm = { ...notifyData(), decisions: [], opened: [], $nextTick: (fn) => fn() };
  for (const [k, fn] of Object.entries(notifyMethods)) vm[k] = fn.bind(vm);
  vm.openDecision = (id) => vm.opened.push(id);
  return vm;
}

function show(visible, permission = 'granted') {
  document.visibilityState = visible ? 'visible' : 'hidden';
  document.hidden = !visible;
  FakeNotification.permission = permission;
}

beforeEach(() => {
  posts.length = 0;
  answers.clear();
  FakeNotification.shown = [];
  FakeNotification.asked = 0;
  show(false);
});

test('the tab reports whether it is visible and what the browser lets it do', () => {
  assert.deepEqual(tabState({ visibilityState: 'visible' }, { permission: 'granted' }), { visible: true, permission: 'granted' });
  assert.deepEqual(tabState({ visibilityState: 'hidden' }, { permission: 'default' }), { visible: false, permission: 'default' });
  assert.deepEqual(tabState({ visibilityState: 'hidden' }, null), { visible: false, permission: 'unsupported' });
});

test('a notification shows only from a hidden tab that may notify; a visible one keeps it in the page', () => {
  assert.equal(pageChannel({ visible: true, permission: 'granted' }), 'in-app');
  assert.equal(pageChannel({ visible: false, permission: 'granted' }), 'browser');
  assert.equal(pageChannel({ visible: false, permission: 'default' }), 'none');
  assert.equal(pageChannel({ visible: false, permission: 'denied' }), 'none');
  assert.equal(pageChannel({ visible: false, permission: 'unsupported' }), 'none');
});

test('the notification reads as the native one and is tagged with the decision id', () => {
  const n = notificationFor(decision());
  assert.equal(n.title, NOTIFY_TITLE);
  assert.equal(NOTIFY_TITLE, 'JumpKick needs you');
  assert.equal(n.options.tag, 'd1');
  assert.match(n.options.body, /^Clean jk's cache\?\nAsked by claude-code 3f9a\. Answer it in the dashboard\.$/);
  assert.deepEqual(n.options.data, { id: 'd1', hash: '#decision/d1' });
});

test('the presence ping names the stream and is answered with the tab state on it', async () => {
  const vm = root();
  assert.equal(vm.notifyEvent({ type: SSE.presence, data: { stream: 's-1', every: 15000 } }), true);
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(posts, [{ url: '/api/events/s-1', method: 'POST', body: { visible: false, permission: 'granted' } }]);

  show(true, 'denied');
  await vm.reportPresence();
  assert.deepEqual(posts[1].body, { visible: true, permission: 'denied' });
  assert.equal(vm.notifyPermission, 'denied');
});

test('no stream yet, no report', async () => {
  await root().reportPresence();
  assert.equal(posts.length, 0);
});

test('a hidden tab with permission shows one notification per decision, and a repeat replaces it', () => {
  const vm = root();
  assert.equal(vm.notifyEvent({ type: SSE.notify, data: decision() }), true);
  vm.notifyEvent({ type: SSE.notify, data: decision() });
  assert.equal(FakeNotification.shown.length, 2, 'the browser replaces by tag');
  assert.ok(FakeNotification.shown.every((n) => n.options.tag === 'd1'));
  assert.equal(vm.bellPulse, false);
});

test('a tab that turned visible since its last report rings the bell instead of notifying', () => {
  const vm = root();
  show(true);
  vm.notifyEvent({ type: SSE.notify, data: decision() });
  assert.equal(FakeNotification.shown.length, 0);
  assert.equal(vm.bellPulse, true);
});

test('a tab that lost its permission shows nothing', () => {
  const vm = root();
  show(false, 'denied');
  vm.notifyEvent({ type: SSE.notify, data: decision() });
  assert.equal(FakeNotification.shown.length, 0);
});

test('a decision settled anywhere closes its notification by tag, and the frame goes on to the console', () => {
  const vm = root();
  vm.notifyEvent({ type: SSE.notify, data: decision() });
  const n = FakeNotification.shown[0];
  assert.equal(vm.notifyEvent({ type: SSE.decision, data: { change: 'resolved', decision: decision({ state: 'accepted' }) } }), false);
  assert.equal(n.closed, true);
});

test('a new decision rings the bell of a visible tab, never opens the dialog, and leaves a hidden one quiet', () => {
  const vm = root();
  show(true);
  vm.notifyEvent({ type: SSE.decision, data: { change: 'opened', decision: decision() } });
  assert.equal(vm.bellPulse, true);
  assert.deepEqual(vm.opened, [], 'no dialog over the work');

  const hidden = root();
  show(false);
  hidden.notifyEvent({ type: SSE.decision, data: { change: 'opened', decision: decision() } });
  assert.equal(hidden.bellPulse, false);

  const known = root();
  known.decisions = [decision()];
  show(true);
  known.notifyEvent({ type: SSE.decision, data: { change: 'opened', decision: decision() } });
  assert.equal(known.bellPulse, false, 'a decision already in the bell does not ring again');
});

test('a click focuses this tab, closes the notification and opens the decision', () => {
  let focused = 0;
  const opened = [];
  const notes = pageNotifications(FakeNotification, (id) => opened.push(id), { focus: () => focused++ });
  const n = notes.show(NOTIFY_TITLE, notificationFor(decision()).options);
  n.onclick();
  assert.equal(focused, 1);
  assert.equal(n.closed, true);
  assert.deepEqual(opened, ['d1']);
  notes.close('d1');
  assert.equal(FakeNotification.shown.length, 1, 'closing a gone notification is a no-op');
});

test('the bell asks on a click only, says why in one line, and steps aside once answered', async () => {
  assert.match(askLine('default'), /browser notification/);
  assert.equal(askLine('granted'), '');
  assert.equal(askLine('denied'), '');
  assert.equal(askLine('unsupported'), '');

  FakeNotification.permission = 'default';
  const vm = root();
  vm._stream = 's-2';
  assert.equal(FakeNotification.asked, 0, 'nothing asks on load');
  FakeNotification.answer = 'granted';
  await vm.askNotifications();
  assert.equal(FakeNotification.asked, 1);
  assert.equal(vm.notifyPermission, 'granted');
  assert.equal(askLine(vm.notifyPermission), '');
  assert.deepEqual(posts.at(-1), { url: '/api/events/s-2', method: 'POST', body: { visible: false, permission: 'granted' } });
});

/** A confirmation, answered by one choice: the shape the engine's forms give it. */
const confirmation = (over = {}) =>
  decision({
    form: { steps: [{ fields: [{ key: 'answer', kind: 'choice', options: [{ id: 'yes', label: 'Clean' }, { id: 'no', label: 'Keep' }, 'show'] }] }] },
    ...over,
  });

/** A service worker container: `controller` set while a worker controls the page. */
function container(controlled) {
  const registration = {
    shown: [],
    closed: [],
    async showNotification(title, options) {
      registration.shown.push({ title, options });
    },
    async getNotifications({ tag }) {
      return registration.shown
        .filter((n) => n.options.tag === tag)
        .map((n) => ({ close: () => registration.closed.push(n.options.tag) }));
    },
  };
  return { controller: controlled ? {} : null, ready: Promise.resolve(registration), registration };
}

test('a decision answered by one choice carries its choices as buttons, where the browser has room', () => {
  assert.deepEqual(notificationFor(confirmation(), 2).options.actions, [
    { action: 'yes', title: 'Clean' },
    { action: 'no', title: 'Keep' },
  ]);
  assert.equal(notificationFor(confirmation(), 0).options.actions, undefined, 'no buttons where the browser shows none');
  assert.equal(notificationFor(confirmation(), 1).options.actions, undefined, 'all the choices or none');
  assert.equal(notificationFor(decision(), 2).options.actions, undefined, 'a form with real inputs is answered in the dialog');
  assert.equal(maxActions({ maxActions: 2 }), 2);
  assert.equal(maxActions(FakeNotification), 0);
  assert.equal(maxActions(null), 0);
});

test('the service worker shows notifications while it controls the page, with their buttons', async () => {
  const sw = container(true);
  const notes = notificationsFor(FakeNotification, sw, () => {});
  assert.equal(notes.via, 'worker');
  const n = notificationFor(confirmation(), 2);
  await notes.show(n.title, n.options);
  assert.equal(FakeNotification.shown.length, 0);
  assert.equal(sw.registration.shown.length, 1);
  assert.equal(sw.registration.shown[0].options.actions.length, 2);
  notes.close('d1');
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(sw.registration.closed, ['d1'], 'closed by tag through the registration');
});

test('the page shows them, without buttons, until a worker controls it', async () => {
  const sw = container(false);
  const notes = notificationsFor(FakeNotification, sw, () => {});
  assert.equal(notes.via, 'page');
  const n = notificationFor(confirmation(), 2);
  notes.show(n.title, n.options);
  assert.equal(sw.registration.shown.length, 0);
  assert.equal(FakeNotification.shown.length, 1);
  assert.equal(FakeNotification.shown[0].options.actions, undefined, 'a page notification with actions throws');

  sw.controller = {};
  assert.equal(notes.via, 'worker', 'chosen per notification, so a worker that takes over is used at once');
  notes.close('d1');
  assert.equal(FakeNotification.shown[0].closed, true, 'one the page showed still closes');
  assert.equal(notificationsFor(FakeNotification, null, () => {}).via, 'page', 'no service workers here');
});

test("the worker's click opens the decision, and a button answers it as the dialog's button would", async () => {
  const vm = root();
  vm.workerMessage({ type: WORKER_MESSAGE.open, id: 'd1' });
  assert.deepEqual(vm.opened, ['d1']);

  answers.set('/api/decisions/d1', confirmation());
  await vm.answerDecision('d1', 'no');
  assert.deepEqual(posts.at(-1), { url: '/api/decisions/d1', method: 'POST', body: { action: 'accept', answers: { answer: 'no' } } });
  assert.deepEqual(vm.opened, ['d1'], 'answered without the dialog');
});

test('a button for a settled decision or a choice it lacks opens the dialog and answers nothing', async () => {
  const vm = root();
  answers.set('/api/decisions/d1', confirmation({ state: 'accepted' }));
  await vm.answerDecision('d1', 'yes');
  answers.set('/api/decisions/d1', confirmation());
  await vm.answerDecision('d1', 'maybe');
  assert.deepEqual(vm.opened, ['d1', 'd1']);
  assert.equal(posts.filter((p) => p.method === 'POST').length, 0);
});

test('a message that is not the worker\'s two is ignored', () => {
  const vm = root();
  vm.workerMessage(null);
  vm.workerMessage({ type: WORKER_MESSAGE.open });
  vm.workerMessage({ type: 'other', id: 'd1' });
  vm.workerMessage({ type: WORKER_MESSAGE.answer, id: 'd1' });
  assert.deepEqual(vm.opened, []);
  assert.equal(posts.length, 0);
});
