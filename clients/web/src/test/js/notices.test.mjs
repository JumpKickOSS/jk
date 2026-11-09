// SPDX-License-Identifier: Apache-2.0
// The bell's number counts what you haven't seen: notices newer than the machine's one seen marker,
// folded from the engine's `notice` frame so every tab agrees; opening the bell or a dialog moves the
// marker; a decision seen but unanswered keeps the bell amber without a number.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const storage = () => {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k) };
};
globalThis.sessionStorage = storage();
globalThis.localStorage = storage();
globalThis.location = { hash: '', hostname: '127.0.0.1', pathname: '/', search: '' };
globalThis.document = { visibilityState: 'visible', hidden: false, addEventListener() {}, removeEventListener() {} };

const posts = [];
let answer = () => ({ status: 200, body: {} });
globalThis.fetch = async (url, opts = {}) => {
  const method = opts.method || 'GET';
  posts.push({ url, method, body: opts.body ? JSON.parse(opts.body) : null });
  const { status, body } = answer(url, method);
  return { ok: status < 400, status, json: async () => body, clone() { return this; } };
};

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const { JUST_NOW, bellLook, fetchNotices, foldNotice, justNow, newestAt, noticeData, noticeMethods, unseenOf } = await spa('notices.js');
const { SSE } = await spa('wire.js');

const notice = (at, over = {}) => ({ id: 'n' + at, at, kind: 'decision', text: 'An agent needs you: q' + at, href: '#decision/d' + at, decision: 'd' + at, ...over });

function root(over = {}) {
  const vm = { ...noticeData(), previews: [], decisionId: null, ...over };
  for (const [k, fn] of Object.entries(noticeMethods)) vm[k] = fn.bind(vm);
  vm.deliverInApp = (card) => vm.previews.push(card);
  vm.isActiveTab = () => !!vm.active;
  return vm;
}

beforeEach(() => {
  posts.length = 0;
  answer = () => ({ status: 200, body: {} });
});

test('the count is what is newer than the marker', () => {
  assert.equal(unseenOf([notice(1), notice(2), notice(3)], 1), 2);
  assert.equal(unseenOf([], 0), 0);
  assert.equal(newestAt([notice(4), notice(9), notice(2)]), 9);
  assert.equal(newestAt([]), 0);
});

test('a frame adds a notice once and takes the engine marker and count', () => {
  let s = noticeData();
  s = foldNotice(s, { change: 'added', notice: notice(1), seenUpTo: 0, unseen: 1 });
  s = foldNotice(s, { change: 'added', notice: notice(1), seenUpTo: 0, unseen: 1 });
  assert.equal(s.notices.length, 1, 'a repeat does not duplicate');
  s = foldNotice(s, { change: 'added', notice: notice(2), seenUpTo: 0, unseen: 2 });
  assert.deepEqual(s.notices.map((n) => n.id), ['n2', 'n1']);
  assert.equal(s.unseen, 2);
  s = foldNotice(s, { change: 'seen', seenUpTo: 2, unseen: 0 });
  assert.equal(s.unseen, 0, 'another tab opened the bell: the count clears here too');
  assert.equal(s.seenUpTo, 2);
  assert.equal(foldNotice(s, { change: 'seen', seenUpTo: 1, unseen: 1 }).seenUpTo, 2, 'never back');
  assert.equal(foldNotice(s, null), s);
});

test('the bell is amber while a decision waits, numbered only for what is unseen', () => {
  assert.deepEqual(bellLook(0, 0), { count: '', amber: false, label: 'Notifications' });
  assert.deepEqual(bellLook(2, 0), { count: '2', amber: false, label: 'Notifications: 2 new' });
  assert.deepEqual(bellLook(0, 1), { count: '', amber: true, label: 'Notifications: 1 decision waits on you' }, 'seen but not answered');
  assert.equal(bellLook(3, 2).label, 'Notifications: 3 new, 2 decisions wait on you');
});

test('"Just now" lists recent notices, newest first, leaving out decisions still waiting', () => {
  const notices = [notice(1), notice(2, { kind: 'setup', decision: undefined, text: 'Machine setup applied' }), notice(3)];
  const open = [{ id: 'd3', state: 'open' }];
  assert.deepEqual(justNow(notices, open).map((n) => n.id), ['n2', 'n1'], 'd3 is under "Waiting on you"');
  const many = Array.from({ length: JUST_NOW + 4 }, (_, i) => notice(i + 1));
  assert.equal(justNow(many, []).length, JUST_NOW);
});

test('loading reads the engine, and an engine without notices is an empty bell', async () => {
  answer = () => ({ status: 200, body: { seenUpTo: 1, unseen: 1, notices: [notice(2), notice(1)] } });
  assert.deepEqual(await fetchNotices((p) => ({ seenUpTo: 1, unseen: 1, notices: [notice(2)], p })), { notices: [notice(2)], seenUpTo: 1, unseen: 1 });
  const gone = Object.assign(new Error('nope'), { status: 404 });
  assert.deepEqual(await fetchNotices(async () => { throw gone; }), { notices: [], seenUpTo: 0, unseen: 0 });
});

test('a tab opened on a decision dialog marks it seen once the notices load', async () => {
  answer = (url, method) =>
    method === 'GET' ? { status: 200, body: { seenUpTo: 0, unseen: 1, notices: [notice(5)] } } : { status: 200, body: { seenUpTo: 5, unseen: 0 } };
  const vm = root({ decisionId: 'd5' });
  await vm.loadNotices();
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(posts.at(-1), { url: '/api/notices/seen', method: 'POST', body: { upTo: 5 } });
  assert.equal(vm.unseen, 0);
});

test('marking seen clears the count at once and tells the engine the newest notice', async () => {
  answer = () => ({ status: 200, body: { seenUpTo: 7, unseen: 0 } });
  const vm = root({ notices: [notice(7), notice(6)], seenUpTo: 5, unseen: 2 });
  const pending = vm.markSeen();
  assert.equal(vm.unseen, 0, 'cleared before the reply');
  await pending;
  assert.deepEqual(posts, [{ url: '/api/notices/seen', method: 'POST', body: { upTo: 7 } }]);

  posts.length = 0;
  await vm.markSeen();
  assert.equal(posts.length, 0, 'nothing newer: nothing sent');
});

test('the notice frame is handled here, and a non-decision notice previews only in a tab someone uses', () => {
  const vm = root();
  assert.equal(vm.noticeEvent({ type: SSE.decision, data: {} }), false);
  const setup = notice(3, { kind: 'setup', decision: undefined, text: 'Machine setup applied', href: '#status' });
  assert.equal(vm.noticeEvent({ type: SSE.notice, data: { change: 'added', notice: setup, seenUpTo: 0, unseen: 1 } }), true);
  assert.equal(vm.unseen, 1);
  assert.equal(vm.previews.length, 0, 'nobody is using this tab');

  const used = root({ active: true });
  used.noticeEvent({ type: SSE.notice, data: { change: 'added', notice: setup, seenUpTo: 0, unseen: 1 } });
  assert.deepEqual(used.previews, [{ id: 'n3', text: 'Machine setup applied', href: '#status', action: 'View' }]);
  used.noticeEvent({ type: SSE.notice, data: { change: 'added', notice: notice(4), seenUpTo: 0, unseen: 2 } });
  assert.equal(used.previews.length, 1, "a decision previews from its own frame, not twice");
});
