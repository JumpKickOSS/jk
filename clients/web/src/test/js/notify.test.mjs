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

/** Every POST the page sends. */
const posts = [];
globalThis.fetch = async (url, opts = {}) => {
  posts.push({ url, method: opts.method || 'GET', body: opts.body ? JSON.parse(opts.body) : null });
  return { ok: true, status: 200, json: async () => ({}), clone() { return this; } };
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
const { NOTIFY_TITLE, askLine, notificationFor, notifyData, notifyMethods, pageChannel, pageNotifications, tabState } =
  await spa('notify.js');
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
