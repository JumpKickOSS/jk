// SPDX-License-Identifier: Apache-2.0
// The service worker: installability and notification clicks only. It never stands between the
// page and the engine (no fetch handler, no cache) and takes no push; a click brings a dashboard
// window forward with the decision, or opens one on the dialog.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { pathToFileURL } from 'node:url';

const DIR = process.env.JK_APP_DIR;
const source = fs.readFileSync(path.join(DIR, 'sw.js'), 'utf8');
const { WORKER_MESSAGE } = await import(pathToFileURL(path.join(DIR, 'notify.js')));

const SCOPE = 'http://127.0.0.1:4317/';

/** A window client: records focus and the messages posted to it. */
function windowClient() {
  return {
    focused: 0,
    messages: [],
    async focus() {
      this.focused++;
      return this;
    },
    postMessage(m) {
      this.messages.push(structuredClone(m)); // as postMessage does, and out of the worker's realm
    },
  };
}

/** sw.js run in a fresh worker global with `windows` open; its listeners by event type. */
function worker(windows = []) {
  const listeners = new Map();
  const opened = [];
  const self = {
    registration: { scope: SCOPE },
    clients: {
      queried: [],
      async matchAll(options) {
        this.queried.push(structuredClone(options));
        return windows;
      },
      async openWindow(url) {
        opened.push(url);
        return null;
      },
      async claim() {},
    },
    skipWaiting() {},
    addEventListener(type, fn) {
      listeners.set(type, [...(listeners.get(type) || []), fn]);
    },
  };
  vm.runInNewContext(source, { self, URL, console }, { filename: 'sw.js' });
  return { self, listeners, opened };
}

/** Dispatches a notification click and waits for the work it hands to waitUntil. */
async function click(w, data, action = '') {
  const notification = { data, closed: false, close() {
    this.closed = true;
  } };
  let work = Promise.resolve();
  for (const fn of w.listeners.get('notificationclick')) {
    fn({ notification, action, waitUntil: (p) => (work = p) });
  }
  await work;
  return notification;
}

test('the worker caches nothing: no fetch handler, no Cache Storage, no push', () => {
  const { listeners } = worker();
  assert.deepEqual([...listeners.keys()].sort(), ['activate', 'install', 'notificationclick']);
  const code = source.replace(/\/\/.*$/gm, '');
  assert.doesNotMatch(code, /\bcaches\b/, 'no Cache Storage');
  assert.doesNotMatch(code, /\bfetch\b/, 'no fetch handler and no requests of its own');
  assert.doesNotMatch(code, /\bpush(Manager)?\b/, 'no Web Push');
});

test('a changed worker takes over at once', async () => {
  const w = worker();
  let skipped = 0;
  let claimed;
  w.self.skipWaiting = () => skipped++;
  w.self.clients.claim = async () => (claimed = true);
  w.listeners.get('install')[0]({});
  let work;
  w.listeners.get('activate')[0]({ waitUntil: (p) => (work = p) });
  await work;
  assert.equal(skipped, 1);
  assert.equal(claimed, true);
});

test('a click brings the most recent dashboard window forward and tells it to open the decision', async () => {
  const recent = windowClient();
  const older = windowClient();
  const w = worker([recent, older]);
  const n = await click(w, { id: 'd1', hash: '#decision/d1' });
  assert.equal(n.closed, true);
  assert.deepEqual(w.self.clients.queried, [{ type: 'window', includeUncontrolled: true }]);
  assert.equal(recent.focused, 1);
  assert.deepEqual(recent.messages, [{ type: WORKER_MESSAGE.open, id: 'd1' }]);
  assert.deepEqual(older.messages, []);
  assert.deepEqual(w.opened, []);
});

test("a button tells the window to answer with that choice, so the page's own token posts it", async () => {
  const win = windowClient();
  const w = worker([win]);
  await click(w, { id: 'd1' }, 'yes');
  assert.equal(win.focused, 1);
  assert.deepEqual(win.messages, [{ type: WORKER_MESSAGE.answer, id: 'd1', choice: 'yes' }]);
});

test('with no window open a click opens one on the dialog, and a button answers nothing', async () => {
  const w = worker([]);
  await click(w, { id: 'a b' });
  await click(w, { id: 'd1' }, 'yes');
  assert.deepEqual(w.opened, [SCOPE + '#decision/a%20b', SCOPE + '#decision/d1']);
});

test('a window that cannot take focus still gets the decision', async () => {
  const win = windowClient();
  win.focus = async () => {
    throw new Error('not allowed to focus');
  };
  await click(worker([win]), { id: 'd1' });
  assert.deepEqual(win.messages, [{ type: WORKER_MESSAGE.open, id: 'd1' }]);
});

test('a notification that is not a decision does nothing', async () => {
  const win = windowClient();
  const w = worker([win]);
  await click(w, null);
  assert.deepEqual(win.messages, []);
  assert.deepEqual(w.opened, []);
});
