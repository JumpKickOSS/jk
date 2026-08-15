// SPDX-License-Identifier: Apache-2.0
// Headless tests for the root app component's route logic (JK-1986). The harness copies the
// web modules into a type:module temp dir (JK_APP_DIR) and imports app.js's exported
// appOptions; browser mount is guarded behind `typeof document`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const store = new Map();
globalThis.sessionStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};
globalThis.localStorage = globalThis.sessionStorage;
globalThis.location = { hash: '', hostname: '127.0.0.1', pathname: '/', search: '' };

const { appOptions } = await import(pathToFileURL(path.join(process.env.JK_APP_DIR, 'app.js')));

/** A detached component instance: data + methods, no Vue. */
function vm(over = {}) {
  const v = Object.assign(Object.create(appOptions.methods), appOptions.data(), over);
  v.loadProjectHistory = () => {};
  v.refreshMetrics = () => {};
  v.refresh = () => {};
  v.setView = () => {};
  return v;
}

test('same-project navigation does not refetch project meta (JK-1945)', () => {
  globalThis.location.hash = '#project/abc123/files/src/Main.java';
  const v = vm({ selectedProjectId: 'abc123', projectMeta: { dir: '/x' }, view: 'project' });
  let calls = 0;
  v.loadProjectMeta = () => calls++;
  v.applyRoute();
  assert.equal(calls, 0, 'no refetch on a file click within the same project');
});

test('project switch and missing meta both refetch', () => {
  globalThis.location.hash = '#project/other99';
  const switched = vm({ selectedProjectId: 'abc123', projectMeta: { dir: '/x' } });
  let calls = 0;
  switched.loadProjectMeta = (id) => {
    calls++;
    assert.equal(id, 'other99');
  };
  switched.applyRoute();
  assert.equal(calls, 1);

  globalThis.location.hash = '#project/abc123';
  const missing = vm({ selectedProjectId: 'abc123', projectMeta: null });
  let calls2 = 0;
  missing.loadProjectMeta = () => calls2++;
  missing.applyRoute();
  assert.equal(calls2, 1, 'never-loaded meta is fetched even without a switch');
});

test('leaving project view or switching projects collapses the graph panel', () => {
  globalThis.location.hash = '#project/abc123';
  const v = vm({ selectedProjectId: 'abc123', projectMeta: { dir: '/x' }, projectGraphOpen: true });
  v.loadProjectMeta = () => {};
  v.applyRoute();
  assert.equal(v.projectGraphOpen, true, 'same project keeps the panel');

  globalThis.location.hash = '#project/other99';
  v.loadProjectMeta = () => {};
  v.applyRoute();
  assert.equal(v.projectGraphOpen, false, 'switch collapses');
});
