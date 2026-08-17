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

test('rapid file clicks during a slow meta fetch fire exactly one request (JK-2064)', async () => {
  const flush = () => new Promise((r) => setImmediate(r));
  globalThis.location.hash = '#project/abc123/files/src/A.java';
  const v = vm({ selectedProjectId: null, projectMeta: null, projectHistory: [{}] });
  let fetches = 0;
  let resolveMeta;
  v.fetchProjectMeta = () => {
    fetches++;
    return new Promise((r) => {
      resolveMeta = r;
    });
  };
  v.applyRoute(); // project switch starts the load
  assert.equal(fetches, 1);
  // The fetch is still in flight (projectMeta null) — more clicks must not refetch.
  v.applyRoute();
  globalThis.location.hash = '#project/abc123/files/src/B.java';
  v.applyRoute();
  assert.equal(fetches, 1, 'in-flight load absorbs further clicks');
  resolveMeta({ dir: '/x', coord: 'g:x' });
  await flush();
  assert.equal(v.projectMeta.dir, '/x');
  v.applyRoute();
  assert.equal(fetches, 1, 'loaded meta still suppresses refetch');

  // A failed load must not latch the guard — the next click retries.
  globalThis.location.hash = '#project/xyz789';
  const w = vm({ selectedProjectId: null, projectMeta: null, projectHistory: [{}] });
  w.handleHttpError = () => {};
  let attempts = 0;
  w.fetchProjectMeta = () => {
    attempts++;
    return Promise.reject(new Error('engine hiccup'));
  };
  w.applyRoute();
  await flush();
  w.applyRoute();
  await flush();
  assert.equal(attempts, 2, 'failure re-arms the guard');
});

test('a stale project-meta response never overwrites the current project (JK-1995)', async () => {
  const v = vm({ selectedProjectId: 'slowA', projectMeta: null, projectHistory: [{}] });
  let resolveSlow;
  v.fetchProjectMeta = () =>
    new Promise((r) => {
      resolveSlow = r;
    });
  const call = v.loadProjectMeta('slowA');
  // The user switches to fastB (whose meta already landed) while slowA's response is pending.
  v.selectedProjectId = 'fastB';
  v.projectMeta = { dir: '/fastB' };
  v.selectedProjectDir = '/fastB';
  resolveSlow({ dir: '/slowA', coord: 'g:slowA' });
  await call;
  assert.equal(v.projectMeta.dir, '/fastB', 'stale response dropped');
  assert.equal(v.selectedProjectDir, '/fastB');

  // The matching case still applies normally.
  const w = vm({ selectedProjectId: 'same', projectMeta: null, projectHistory: [{}] });
  w.fetchProjectMeta = () => Promise.resolve({ dir: '/same' });
  await w.loadProjectMeta('same');
  assert.equal(w.projectMeta.dir, '/same');
  assert.equal(w.selectedProjectDir, '/same');
});

test('manifest saves and build finishes refresh the open project header (JK-2065)', async () => {
  const flush = () => new Promise((r) => setImmediate(r));
  const v = vm({
    view: 'project',
    selectedProjectId: 'abc123',
    projectMeta: { coord: 'g:old', dir: '/old' },
    selectedProjectDir: '/old',
    projectHistory: [{}],
  });
  let fetches = 0;
  v.fetchProjectMeta = () => {
    fetches++;
    return Promise.resolve({ coord: 'g:new', dir: '/new' });
  };
  // A jk.toml save re-pulls meta in place — no null flicker on the way.
  v.onCodeSaved({ path: 'jk.toml' });
  assert.notEqual(v.projectMeta, null, 'header never blanks during the refresh');
  await flush();
  assert.equal(fetches, 1);
  assert.equal(v.projectMeta.coord, 'g:new');
  assert.equal(v.selectedProjectDir, '/new');
  // Non-manifest saves do not refetch.
  v.onCodeSaved({ path: 'src/Main.java' });
  await flush();
  assert.equal(fetches, 1);
  // Build finish path calls refreshProjectMeta directly; outside project view it is a no-op.
  await v.refreshProjectMeta();
  assert.equal(fetches, 2);
  v.view = 'activity';
  await v.refreshProjectMeta();
  assert.equal(fetches, 2);
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

test('fmtDateTime is yyyy-MM-dd hh:mm:ss in the local zone', () => {
  const v = vm();
  const d = new Date(2026, 7, 16, 14, 3, 9);
  assert.equal(v.fmtDateTime(d.getTime()), '2026-08-16 14:03:09');
  assert.equal(v.fmtDateTime(null), '');
  assert.equal(v.fmtDateTime(0), '');
  assert.equal(v.fmtDateTime('nope'), '');
});

test('cancelled cards hide success and failure details', () => {
  const v = vm();
  const card = {
    state: 'finished',
    cancelled: true,
    success: false,
    modules: [
      { dir: '/w/a', state: 'success', diagnostics: [], steps: [{ name: 'compile', state: 'success' }] },
      { dir: '/w/b', state: 'cancelled', diagnostics: [], steps: [] },
    ],
  };
  assert.equal(v.outcome(card), 'cancelled');
  assert.deepEqual(v.okModules(card), []);
  assert.deepEqual(v.failedModules(card), []);
});
