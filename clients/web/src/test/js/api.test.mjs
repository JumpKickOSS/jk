// SPDX-License-Identifier: Apache-2.0
// Headless tests for api.js epoch and dirty-guard behaviour. The module reads
// sessionStorage/location/window lazily, so plain stubs are enough.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const store = new Map();
globalThis.sessionStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};
globalThis.localStorage = globalThis.sessionStorage;
let reloads = 0;
globalThis.location = {
  hash: '',
  hostname: '127.0.0.1',
  pathname: '/',
  search: '',
  reload: () => {
    reloads++;
  },
};
let confirmAnswer = true;
let confirmCalls = 0;
globalThis.window = {
  confirm: () => {
    confirmCalls++;
    return confirmAnswer;
  },
};

const {
  hardRefreshForEpoch,
  noteEngineEpoch,
  settleEpochReload,
  registerDirtyGuard,
  unregisterDirtyGuard,
  releaseDeferredEpochReload,
  events,
  isServerFrame,
} = await import(pathToFileURL(path.join(process.env.JK_APP_DIR, 'api.js')));

beforeEach(() => {
  store.clear();
  reloads = 0;
  confirmCalls = 0;
  confirmAnswer = true;
  unregisterDirtyGuard(currentGuard);
  currentGuard = null;
});

let currentGuard = null;
function guard(fn) {
  currentGuard = fn;
  registerDirtyGuard(fn);
}

test('clean buffer (or no guard) reloads without prompting', () => {
  hardRefreshForEpoch();
  assert.equal(reloads, 1);
  assert.equal(confirmCalls, 0);

  guard(() => false);
  store.clear();
  hardRefreshForEpoch();
  assert.equal(reloads, 2);
  assert.equal(confirmCalls, 0);
});

test('dirty buffer prompts; accepting reloads', () => {
  guard(() => true);
  confirmAnswer = true;
  hardRefreshForEpoch();
  assert.equal(confirmCalls, 1);
  assert.equal(reloads, 1);
});

test('declined reload defers, stops nagging, and releases when clean', () => {
  let dirty = true;
  guard(() => dirty);
  confirmAnswer = false;
  hardRefreshForEpoch();
  assert.equal(confirmCalls, 1);
  assert.equal(reloads, 0);

  // Background polls hit the same path — no second prompt while deferred.
  hardRefreshForEpoch();
  hardRefreshForEpoch();
  assert.equal(confirmCalls, 1);
  assert.equal(reloads, 0);

  // Buffer goes clean (saved or discarded): the deferred reload proceeds.
  dirty = false;
  releaseDeferredEpochReload();
  assert.equal(reloads, 1);

  // Release is one-shot.
  releaseDeferredEpochReload();
  assert.equal(reloads, 1);
});

test('reload latch still breaks reload loops', () => {
  store.set('jk-epoch-reload', '1');
  hardRefreshForEpoch();
  assert.equal(reloads, 0, 'in-flight latch swallows the second reload');
  assert.equal(store.has('jk-epoch-reload'), false, 'latch consumed');
});

// The engine restarted twice: the first restart's reload landed while no engine answered, and the
// page booted against the second. Its bootstrap saw a changed epoch, the latch swallowed the
// reload, and the boot gave up: a dashboard that never connected until reloaded by hand.
test("a page's own bootstrap latches a changed epoch and carries on, never reloading", () => {
  store.set('jk-engine-epoch', 'engine-2');
  store.set('jk-epoch-reload', '1');
  assert.equal(noteEngineEpoch({ engineEpoch: 'engine-3' }, { fresh: true }), 'ok');
  assert.equal(store.get('jk-engine-epoch'), 'engine-3');
  assert.equal(store.has('jk-epoch-reload'), false, 'the reload that brought the page here is over');
  assert.equal(reloads, 0);

  assert.equal(noteEngineEpoch({ engineEpoch: 'engine-4' }), 'mismatch', 'a page that has read from engine 3 must reload');
});

test('a page that booted with no engine answering reloads on the next engine, not swallowing it', () => {
  store.set('jk-engine-epoch', 'engine-2');
  store.set('jk-epoch-reload', '1');
  settleEpochReload();
  hardRefreshForEpoch();
  assert.equal(reloads, 1);
});

// A failed build's diagnostics arrive as a named `error` frame. EventSource dispatches named frames
// to onerror as well, and the stream used to read that as a disconnect: every red build flipped
// every open dashboard to OFFLINE until it was reloaded.
test('a named error frame is an event, not a disconnect', async () => {
  const sources = [];
  globalThis.document = { hidden: false, addEventListener() {}, removeEventListener() {} };
  globalThis.EventSource = class {
    static CLOSED = 2;
    constructor(url) {
      this.url = url;
      this.readyState = 1;
      this.listeners = new Map();
      sources.push(this);
    }
    addEventListener(type, fn) {
      this.listeners.set(type, fn);
    }
  };
  const states = [];
  const got = [];
  events((ev) => got.push(ev), (s) => states.push(s));
  const source = sources[0];
  source.onopen();
  // What a browser does with `event: error\ndata: {...}`: the named listener and onerror both run.
  const frame = { type: 'error', data: JSON.stringify({ type: 'error', message: 'type not found: Bird' }) };
  source.listeners.get('error')(frame);
  source.onerror(frame);
  await new Promise((r) => setTimeout(r, 5));
  assert.deepEqual(states, ['live'], 'the stream stays live through a failed build');
  assert.equal(got.length, 1);
  assert.equal(got[0].type, 'error');
  assert.equal(got[0].data.message, 'type not found: Bird');

  // The connection really dropping: a data-less error.
  source.onerror({ type: 'error' });
  assert.deepEqual(states, ['live', 'offline']);
});

// A hidden tab's timers run as late as once a minute: the presence ping and a notification to show
// are handed over as they arrive, not queued behind the drain.
test('the presence ping and a notification skip the drain queue', () => {
  const sources = [];
  globalThis.document = { hidden: true, addEventListener() {}, removeEventListener() {} };
  globalThis.EventSource = class {
    static CLOSED = 2;
    constructor() {
      this.readyState = 1;
      this.listeners = new Map();
      sources.push(this);
    }
    addEventListener(type, fn) {
      this.listeners.set(type, fn);
    }
  };
  const got = [];
  events((ev) => got.push(ev), () => {});
  const source = sources[0];
  source.listeners.get('presence')({ data: JSON.stringify({ stream: 's-1' }) });
  source.listeners.get('notify')({ data: JSON.stringify({ id: 'd1' }) });
  source.listeners.get('status')({ data: JSON.stringify({}) });
  assert.deepEqual(got.map((e) => e.type), ['presence', 'notify'], 'synchronously, before any drain');
});

test('isServerFrame tells an engine frame from a dropped connection', () => {
  assert.equal(isServerFrame({ type: 'error', data: '{}' }), true);
  assert.equal(isServerFrame({ type: 'error' }), false);
  assert.equal(isServerFrame(undefined), false);
});
