// SPDX-License-Identifier: Apache-2.0
// Headless tests for api.js epoch/dirty-guard behavior (JK-1973). The module reads
// sessionStorage/location/window lazily, so plain stubs are enough.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
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
  registerDirtyGuard,
  unregisterDirtyGuard,
  releaseDeferredEpochReload,
} = await import(pathToFileURL(process.env.JK_API_MJS));

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
