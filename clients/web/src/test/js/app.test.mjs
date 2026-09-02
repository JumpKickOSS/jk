// SPDX-License-Identifier: Apache-2.0
// Headless tests for the root app component's route logic. The harness copies the
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

test('same-project navigation does not refetch project meta', () => {
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

test('rapid file clicks during a slow meta fetch fire exactly one request', async () => {
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

test('a stale project-meta response never overwrites the current project', async () => {
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

test('manifest saves and build finishes refresh the open project header', async () => {
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

// --- the dashboard renders test counts from the journal's nested `tests` object ---

/**
 * A real journal record, verbatim from a jk self-host run:
 *   ~/.jk/state/builds/projects/a66f86e5.../runs/1/record.json
 * Every top-level field is byte-for-byte what the engine wrote; only the `modules` array is
 * truncated to its first entry (62 KB otherwise), and nothing under test reads past it.
 * `GET /api/history` streams these bodies to the SPA verbatim, so this IS the wire shape.
 */
const REAL_RECORD = {
    "id": "20260824T053110035",
    "buildNumber": 1,
    "schema": 2,
    "kind": "build",
    "dir": "/home/bsant/src/oss/jk",
    "coord": "cc.jumpkick:jk",
    "projectId": "a66f86e5f1e675e17a67c9c36474f968",
    "startedAt": 1787549470032,
    "finishedAt": 1787549568034,
    "millis": 98002,
    "success": true,
    "cancelled": false,
    "exitCode": 0,
    "jkVersion": "0.12.0",
    "tests": {
      "total": 4323,
      "succeeded": 4314,
      "failed": 0,
      "skipped": 9
    },
    "modules": [
      {
        "coord": "cc.jumpkick:jk-dynamic-surface",
        "dir": "/home/bsant/src/oss/jk/shared/dynamic-surface",
        "success": true,
        "exitCode": 0,
        "millis": 2027,
        "tasks": [
          {
            "name": "parse-build",
            "stage": "resolve",
            "status": "SUCCESS",
            "millis": 4
          },
          {
            "name": "resolve-deps",
            "stage": "resolve",
            "status": "SUCCESS",
            "millis": 77
          },
          {
            "name": "ensure-jdk",
            "stage": "resolve",
            "status": "SKIPPED",
            "millis": 4
          },
          {
            "name": "build-logic-before-compile",
            "stage": "generate",
            "status": "SKIPPED",
            "millis": 1
          },
          {
            "name": "compile-java",
            "stage": "compile",
            "status": "SKIPPED",
            "millis": 7
          },
          {
            "name": "build-logic-after-compile",
            "stage": "compile",
            "status": "SKIPPED",
            "millis": 0
          },
          {
            "name": "write-stamp",
            "stage": "compile",
            "status": "SKIPPED",
            "millis": 0
          },
          {
            "name": "copy-resources",
            "stage": "compile",
            "status": "SKIPPED",
            "millis": 0
          },
          {
            "name": "compile-test",
            "stage": "test",
            "status": "SUCCESS",
            "millis": 1432
          },
          {
            "name": "build-logic-before-package",
            "stage": "package",
            "status": "SKIPPED",
            "millis": 0
          },
          {
            "name": "run-tests",
            "stage": "test",
            "status": "SUCCESS",
            "millis": 482
          },
          {
            "name": "package-jar",
            "stage": "package",
            "status": "SUCCESS",
            "millis": 11
          },
          {
            "name": "deliver",
            "stage": "package",
            "status": "SUCCESS",
            "millis": 0
          }
        ]
      }
    ],
    "tasks": [],
    "diagnostics": [],
    "trigger": "cli",
    "commit": "c944294f",
    "running": false,
    "requestId": 1,
    "benefit": {
      "estimatedUncachedMillis": 116072,
      "savedMillis": 18070,
      "coveredSkips": 0,
      "totalSkips": 166
    },
    "io": {
      "remoteUp": 0,
      "remoteDown": 3300890,
      "localUp": 63382703,
      "localDown": 0
    }
  };

test('projectDetail renders the tests column from the nested tests object', () => {
  const v = vm({ selectedProjectId: REAL_RECORD.projectId, projectHistory: [REAL_RECORD] });
  const detail = appOptions.computed.projectDetail.call(v);

  assert.equal(detail.empty, false, 'a matching record is not an empty project');
  assert.equal(detail.rows.length, 1);
  // index.html renders `{{ r.tests.succeeded }} / {{ r.tests.total }}` off exactly this object.
  assert.deepEqual(detail.rows[0].tests, { total: 4323, succeeded: 4314, failed: 0, skipped: 9 });
  assert.equal(detail.rows[0].tests.succeeded, 4314);
  assert.equal(detail.rows[0].tests.total, 4323);
});

test('a record with no test phase renders no test counts rather than zeros', () => {
  // 217 of the 218 records on this host wrote `"tests": null` — no test step ran. The column has
  // to show an em dash, not "0 / 0", so the row must carry null and not a zeroed object.
  const noTests = { ...REAL_RECORD, tests: null };
  const v = vm({ selectedProjectId: noTests.projectId, projectHistory: [noTests] });

  assert.equal(appOptions.computed.projectDetail.call(v).rows[0].tests, null);
});

test('the SPA reads no retired flat test-count spelling', async () => {
  // Every module, not just app.js: the reader can move between files and the ban has to follow it.
  const fs = await import('node:fs/promises');
  const dir = process.env.JK_APP_DIR;
  for (const file of (await fs.readdir(dir)).filter((f) => f.endsWith('.js'))) {
    const src = await fs.readFile(path.join(dir, file), 'utf8');
    for (const retired of ['testsFailed', 'testFailed', 'testsTotal', 'testTotal',
                           'testsSucceeded', 'testSucceeded', 'testsSkipped', 'testSkipped']) {
      assert.ok(!src.includes('.' + retired), file + ' still reads r.' + retired);
      assert.ok(!src.includes("'" + retired + "'"), file + ' still reads ' + retired + ' by name');
    }
  }
});

test('the Projects tab decides a record outcome with the one outcome rule', () => {
  // The journal writes a record's steps under `tasks`, at the top level and per module. The tab
  // used to walk `r.steps` / `m.steps` — a spelling no emitter has ever produced — so a FAIL task
  // could not move the badge and the row fell through to the record's own `success` bit.
  const failedTask = {
    ...REAL_RECORD,
    success: true,
    cancelled: false,
    modules: [],
    tasks: [{ name: 'run-tests', stage: 'test', status: 'FAIL', millis: 12 }],
  };
  const v = vm({ selectedProjectId: failedTask.projectId, projectHistory: [failedTask] });
  assert.equal(appOptions.computed.projectDetail.call(v).rows[0].outcome, 'failed');
  assert.equal(appOptions.computed.projectsList.call(v)[0].state, 'failed');
});

test('a module-level FAIL task also reaches the Projects tab', () => {
  const failedModule = {
    ...REAL_RECORD,
    success: true,
    cancelled: false,
    tasks: [],
    modules: [{ dir: '/w/core', finished: true, success: false, millis: 5,
                tasks: [{ name: 'compile-java', stage: 'compile', status: 'FAIL', millis: 5 }] }],
  };
  const v = vm({ selectedProjectId: failedModule.projectId, projectHistory: [failedModule] });
  assert.equal(appOptions.computed.projectDetail.call(v).rows[0].outcome, 'failed');
});

test('mib is whole MiB, and asks for a decimal rather than being shadowed by a second copy', () => {
  // Two `mib(bytes)` keys in one methods object: the later one silently won, so every heap figure
  // on the Admin panel and in the footer carried a decimal the first definition meant to round off.
  const v = vm();
  assert.equal(v.mib(281_018_368), '268 MiB');
  assert.equal(v.mib(281_018_368, 1), '268.0 MiB');
  assert.equal(v.mib(null), '—');
  assert.equal(v.mib(-1), '—');
});

test('one duration formatter: the card, the spark tooltip and the live clock agree', async () => {
  const { fmtDuration, fmtClockSeconds } = await import(
    pathToFileURL(path.join(process.env.JK_APP_DIR, 'format.js'))
  );
  const v = vm();
  const hour = 3_605_000;
  // The card's "took" text, the sparkline tooltip's, and the running card's counter face.
  assert.equal(v.duration(hour), '1h 00m 05s');
  assert.equal(fmtDuration(hour), '1h 00m 05s');
  assert.equal(fmtClockSeconds(3605), '1h 00m 05s');
  assert.equal(v.duration(820), '820 ms');
  assert.equal(v.duration(5500), '5.5 s');
  assert.equal(v.duration(65_500), '1m 05s');
  assert.equal(v.duration(null), '');
});

test('one relative-time formatter, and it can say days', () => {
  const now = 10 * 86_400_000;
  const v = vm({ now });
  assert.equal(v.ago(now - 30 * 3_600_000), '1d ago');
  assert.equal(v.ago(now - 90 * 60_000), '1h ago');
  assert.equal(v.ago(now - 5 * 60_000), '5m ago');
  assert.equal(v.ago(now - 1000), 'just now');
  assert.equal(v.ago(null), '');
  assert.equal(v.agoLong(now - (86_400_000 + 2 * 3_600_000 + 3 * 60_000 + 4000)), '1d 2h 3m 4s ago');
  assert.equal(v.agoLong(null), 'never');
});
