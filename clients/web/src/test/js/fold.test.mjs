// SPDX-License-Identifier: Apache-2.0
// Headless tests for the dashboard's event-folding logic (docs/webclient.md). Run by
// WebClientFoldTest via `node --test`, which copies fold.js to fold.mjs and passes its path in
// JK_FOLD_MJS (fold.js's .js extension would be treated as CommonJS by a bare node import).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const {
  foldEvent,
  outcomeOf,
  moduleSummary,
  phaseChainOf,
  seedFromHistory,
  startAnchor,
  ioLines,
  fmtBytes,
  fmtStepMillis,
  stepTimingLabel,
  detailForDisplay,
  liveStepDetail,
  detailSegments,
  looksLikeJavaMember,
  orderedModules,
  MAX_CARDS,
  MAX_OUTPUT_LINES,
  normalizeDiagnostic,
  testFailureReport,
  stackFrameLines,
  isTestFailureDiag,
  parseAssertJMessage,
  shortTestLabel,
  shortDisplayLabel,
  simpleTypeName,
  simplifyMethodParams,
} = await import(pathToFileURL(process.env.JK_FOLD_MJS));

const historyRecord = (id, dir, extra = {}) => ({
  id,
  kind: 'build',
  dir,
  coord: 'g:a',
  startedAt: 1000,
  finishedAt: 2000,
  millis: 1000,
  cancelled: false,
  success: true,
  modules: [],
  steps: [],
  diagnostics: [],
  ...extra,
});

const start = (id, dir, extra = {}) => ({
  type: 'request-start',
  data: { requestId: id, kind: 'build', dir, ...extra },
});
const finish = (id, data = {}) => ({ type: 'request-finish', data: { requestId: id, ...data } });

test('request-start opens a running card, newest first', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w/a'));
  foldEvent(cards, start(2, '/w/b'));
  assert.equal(cards.length, 2);
  assert.equal(cards[0].id, 2); // newest first
  assert.equal(cards[0].state, 'running');
  assert.equal(outcomeOf(cards[0]), 'running');
});

test('http-triggered finish carries explicit success', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, finish(1, { success: true, millis: 1435 }));
  assert.equal(outcomeOf(cards[0]), 'success');
  assert.equal(cards[0].millis, 1435);
});

test('socket finish without success derives the outcome from module rows', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'module-start', data: { requestId: 1, dir: '/w/a' } });
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/a', success: true, millis: 10 } });
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/b', success: false, millis: 5 } });
  foldEvent(cards, finish(1, { millis: 20 })); // no success field — the socket-request shape
  assert.equal(outcomeOf(cards[0]), 'failed'); // any failed module fails the card
});

test('all-success module rows derive success; no rows stay neutral', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'buildplan-finish', data: { requestId: 1, dir: '/w', success: true } });
  foldEvent(cards, finish(1, {}));
  assert.equal(outcomeOf(cards[0]), 'success');

  foldEvent(cards, start(2, '/x'));
  foldEvent(cards, finish(2, {}));
  assert.equal(outcomeOf(cards[0]), 'finished'); // nothing to derive from
});

test('cancelled wins over derived outcomes', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'buildplan-finish', data: { requestId: 1, dir: '/w', success: true } });
  foldEvent(cards, finish(1, { cancelled: true }));
  assert.equal(outcomeOf(cards[0]), 'cancelled');
});

test('didWork false marks module checked; summary says checked not built (JK-1296)', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'module-finish',
    data: { requestId: 1, dir: '/w/a', success: true, millis: 10, didWork: false },
  });
  foldEvent(cards, {
    type: 'module-finish',
    data: { requestId: 1, dir: '/w/b', success: true, millis: 12, didWork: false },
  });
  foldEvent(cards, finish(1, { success: true }));
  assert.equal(cards[0].modules[0].state, 'checked');
  assert.equal(cards[0].modules[1].state, 'checked');
  assert.equal(outcomeOf(cards[0]), 'success');
  assert.equal(moduleSummary(cards[0]), 'checked 2 modules, all up to date');
});

test('buildplan-finish creates a module row when module-start never fired (single-plan requests)', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'buildplan-finish', data: { requestId: 1, dir: '/w', success: false } });
  assert.equal(cards[0].modules.length, 1);
  assert.equal(cards[0].modules[0].state, 'failed');
});

test('events for unknown request ids and unknown types are ignored', () => {
  const cards = [];
  foldEvent(cards, { type: 'module-start', data: { requestId: 99, dir: '/w' } });
  foldEvent(cards, { type: 'plan-module', data: { requestId: 99 } });
  assert.equal(cards.length, 0);
});

test('workspace-progress sets request-level aggregate percent (JK-1120)', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'workspace-progress',
    data: { requestId: 1, dir: '/w', numerator: 150, denominator: 200, progress: 75, phase: 'execute' },
  });
  assert.equal(cards[0].progressPercent, 75);
  assert.equal(cards[0].progressNum, 150);
  assert.equal(cards[0].progressDen, 200);
});

test('rehydrate seeds the countdown from current remaining, not original R0 (JK-1820)', () => {
  const cards = [];
  foldEvent(cards, start(7, '/w'));
  // Late join: the rehydrated snapshot carries the run's original R0 AND current remaining.
  foldEvent(cards, {
    type: 'workspace-progress',
    at: 10_000,
    data: { requestId: 7, dir: '/w', numerator: 800, denominator: 1000, R0: 180_000, remainingMs: 60_000 },
  });
  assert.equal(cards[0].r0Ms, 60_000);
  assert.equal(cards[0].r0At, 10_000);
  assert.equal(cards[0].residualRemainingMs, 60_000);
  assert.equal(cards[0].residualAt, 10_000);
  // R0 seed freezes once; residual re-anchors mid-run for countdown + bar.
  foldEvent(cards, {
    type: 'workspace-progress',
    at: 20_000,
    data: { requestId: 7, dir: '/w', numerator: 900, denominator: 1000, R0: 180_000, remainingMs: 30_000 },
  });
  assert.equal(cards[0].r0Ms, 60_000);
  assert.equal(cards[0].residualRemainingMs, 30_000);
  assert.equal(cards[0].residualAt, 20_000);
});

test('fresh start seeds from the first snapshot where remaining equals R0', () => {
  const cards = [];
  foldEvent(cards, start(8, '/w'));
  foldEvent(cards, {
    type: 'workspace-progress',
    at: 1_000,
    data: { requestId: 8, dir: '/w', numerator: 0, denominator: 1000, R0: 90_000, remainingMs: 90_000 },
  });
  assert.equal(cards[0].r0Ms, 90_000);
});

test('the feed is bounded at MAX_CARDS', () => {
  const cards = [];
  for (let i = 1; i <= MAX_CARDS + 7; i++) foldEvent(cards, start(i, '/w/' + i));
  assert.equal(cards.length, MAX_CARDS);
  assert.equal(cards[0].id, MAX_CARDS + 7); // newest kept, oldest shed
});

test('coord and client timestamps ride the card', () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w', { coord: 'cc.jumpkick:jk' }), at: 1000 });
  assert.equal(cards[0].coord, 'cc.jumpkick:jk');
  assert.equal(cards[0].startedAt, 1000);
  foldEvent(cards, { ...finish(1, { millis: 500 }), at: 1500 });
  assert.equal(cards[0].finishedAt, 1500);
});

test('steps fold per module, each module keeping its own chain', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'task-start', data: { requestId: 1, dir: '/w/a', task: 'compile', stage: 'compile' } });
  foldEvent(cards, { type: 'task-start', data: { requestId: 1, dir: '/w/b', task: 'compile', stage: 'compile' } });
  foldEvent(cards, { type: 'task-finish', data: { requestId: 1, dir: '/w/a', task: 'compile', stage: 'compile', status: 'SUCCESS' } });
  foldEvent(cards, { type: 'task-start', data: { requestId: 1, dir: '/w/a', task: 'test', stage: 'test' } });
  foldEvent(cards, { type: 'task-finish', data: { requestId: 1, dir: '/w/a', task: 'test', stage: 'test', status: 'FAIL' } });
  const byDir = (dir) => cards[0].modules.find((m) => m.dir === dir);
  assert.equal(cards[0].modules.length, 2); // two modules, not one merged chain
  assert.deepEqual(
    byDir('/w/a').steps.map((p) => p.name + ':' + p.state),
    ['compile:success', 'test:failed'],
  );
  assert.deepEqual(
    byDir('/w/b').steps.map((p) => p.name + ':' + p.state),
    ['compile:running'], // /w/b's compile is independent of /w/a's
  );
  // the phase wire-field rides each step row
  assert.deepEqual(byDir('/w/a').steps.map((p) => p.phase), ['compile', 'test']);
});

test('orderedModules puts running first (newest activity), finished last', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  // a starts first, finishes; b starts later and stays running; c fails.
  foldEvent(cards, { type: 'module-start', data: { requestId: 1, dir: '/w/a' }, at: 100 });
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/a', success: true, millis: 10 }, at: 200 });
  foldEvent(cards, { type: 'module-start', data: { requestId: 1, dir: '/w/b' }, at: 300 });
  foldEvent(cards, { type: 'task-start', data: { requestId: 1, dir: '/w/b', task: 'compile', stage: 'compile' }, at: 400 });
  foldEvent(cards, { type: 'module-start', data: { requestId: 1, dir: '/w/c' }, at: 350 });
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/c', success: false, millis: 5 }, at: 360 });
  // Later tick on b → b is the most recently active runner.
  foldEvent(cards, {
    type: 'label',
    data: { requestId: 1, dir: '/w/b', task: 'compile', label: 'compiling' },
    at: 500,
  });

  const ordered = orderedModules(cards[0].modules);
  assert.deepEqual(
    ordered.map((m) => m.dir + ':' + m.state),
    ['/w/b:running', '/w/c:failed', '/w/a:success'],
  );
});

test('single-plan step events (empty dir) become one module with a chain', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  foldEvent(cards, { type: 'task-start', data: { requestId: 1, dir: '', task: 'compile-java', stage: 'compile' } });
  foldEvent(cards, { type: 'task-finish', data: { requestId: 1, dir: '', task: 'compile-java', stage: 'compile', status: 'SUCCESS' } });
  assert.equal(cards[0].modules.length, 1);
  assert.equal(cards[0].modules[0].dir, '');
  assert.deepEqual(cards[0].modules[0].steps.map((p) => p.name + ':' + p.state), ['compile-java:success']);
  // phase stored on the row; the UI renders it as the "compile/java" hierarchy (phase strips the
  // redundant leading "compile-" from the step name)
  assert.equal(cards[0].modules[0].steps[0].phase, 'compile');
});

test('a step-start without a phase stores an empty phase', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'task-start', data: { requestId: 1, dir: '', task: 'lock' } });
  assert.equal(cards[0].modules[0].steps[0].phase, ''); // default, never undefined
});

test('finished live cards reconcile by (dir, buildNumber) despite clock skew (JK-1519)', async () => {
  const { seedFromHistory } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, { ...start(1, '/w', { buildNumber: 6 }), at: 100_000 });
  foldEvent(cards, { ...finish(1, { success: true, millis: 400 }), at: 100_400 });
  // The journal record carries ENGINE time — 10s of clock skew vs the browser receipt stamps.
  seedFromHistory(cards, [
    { id: 'r6', dir: '/w', buildNumber: 6, kind: 'build', finishedAt: 110_400, startedAt: 110_000,
      success: true, millis: 400, modules: [], steps: [], diagnostics: [] },
  ]);
  assert.equal(cards.length, 1); // no duplicate h:r6 card
  assert.equal(cards[0].historyId, 'r6');
});

test('a stale running stub does not flip a finished live card back to running (JK-1519)', async () => {
  const { seedFromHistory } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, { ...start(1, '/w', { buildNumber: 6 }), at: 100_000 });
  foldEvent(cards, { ...finish(1, { success: true, millis: 400 }), at: 100_400 });
  // Reconcile raced the journal write: the record still says running.
  seedFromHistory(cards, [
    { id: 'r6', dir: '/w', buildNumber: 6, kind: 'build', running: true, startedAt: 110_000,
      modules: [], steps: [], diagnostics: [] },
  ]);
  const live = cards.find((c) => c.id === 1);
  assert.equal(live.state, 'finished'); // untouched by the stale stub
  assert.equal(live.historyId, undefined);
  assert.equal(cards.length, 1); // and the stub is not seeded as a phantom running row
});

test('history backfill maps per-module steps; single-project synthesizes one module', async () => {
  const { seedFromHistory } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  // workspace record: modules carry their own steps, and each diagnostic attaches to its module dir
  const ws = [];
  seedFromHistory(ws, [{
    id: 'w1', kind: 'build', dir: '/w', coord: 'g:w', finishedAt: 5000, success: false,
    modules: [
      { coord: 'g:core', dir: '/w/core', success: true, millis: 100, steps: [{ name: 'compile', status: 'SUCCESS' }] },
      { coord: 'g:api', dir: '/w/api', success: false, millis: 90, steps: [{ name: 'test', status: 'FAIL' }] },
    ],
    steps: [],
    diagnostics: [
      { severity: 'error', dir: '/w/api', step: 'test', message: 'boom', test: 'it()', exceptionClass: '' },
      { severity: 'warning', dir: '/w/core', step: 'lint', message: 'unused import' },
    ],
  }]);
  assert.equal(ws[0].modules.length, 2);
  const wcore = ws[0].modules.find((m) => m.dir === '/w/core');
  const wapi = ws[0].modules.find((m) => m.dir === '/w/api');
  assert.deepEqual(wcore.steps.map((p) => p.name + ':' + p.state), ['compile:success']);
  assert.equal(wcore.diagnostics.length, 0); // the warning is dropped, not shown as failure output
  assert.equal(wapi.diagnostics.length, 1);
  assert.equal(wapi.diagnostics[0].message, 'boom');
  // single-project record: no modules, steps at top level → synthesize one module owning the errors
  const sp = [];
  seedFromHistory(sp, [{
    id: 's1', kind: 'build', dir: '/p', coord: 'g:p', finishedAt: 6000, success: false,
    modules: [], steps: [{ name: 'compile-java', status: 'FAIL' }],
    diagnostics: [{ severity: 'error', dir: '', step: 'compile-java', message: 'cannot find symbol' }],
  }]);
  assert.equal(sp[0].modules.length, 1);
  assert.deepEqual(sp[0].modules[0].steps.map((p) => p.name + ':' + p.state), ['compile-java:failed']);
  assert.equal(sp[0].modules[0].diagnostics.length, 1);
  assert.equal(sp[0].modules[0].diagnostics[0].message, 'cannot find symbol');
});

test('workspace history replay applies the per-kind diagnostic ceilings', async () => {
  // JK-1947: the single-project path was bounded (JK-1881) but the workspace path streamed a
  // pathological record's diagnostics into the card unbounded.
  const mod = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const { seedFromHistory, MAX_TEST_FAILURE_DIAGNOSTICS, MAX_DIAGNOSTICS } = mod;
  const diagnostics = [];
  for (let i = 0; i < MAX_TEST_FAILURE_DIAGNOSTICS + 40; i++) {
    diagnostics.push({
      severity: 'error', dir: '/w/api', step: 'test', code: 'test-failure', message: 'assert ' + i,
      test: 'case' + i + '()', exceptionClass: 'org.opentest4j.AssertionFailedError',
    });
  }
  for (let i = 0; i < MAX_DIAGNOSTICS + 5; i++) {
    diagnostics.push({ severity: 'error', dir: '/w/api', step: 'compile-java', message: 'err ' + i });
  }
  const ws = [];
  seedFromHistory(ws, [{
    id: 'w2', kind: 'build', dir: '/w', coord: 'g:w', finishedAt: 7000, success: false,
    modules: [
      { coord: 'g:api', dir: '/w/api', success: false, millis: 90, steps: [{ name: 'test', status: 'FAIL' }] },
    ],
    steps: [],
    diagnostics,
  }]);
  const api = ws[0].modules.find((m) => m.dir === '/w/api');
  assert.equal(api.diagnostics.length, MAX_TEST_FAILURE_DIAGNOSTICS + MAX_DIAGNOSTICS);
});

test('output keeps a bounded tail and clears on finish', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  for (let i = 1; i <= MAX_OUTPUT_LINES + 5; i++) {
    foldEvent(cards, { type: 'output', data: { requestId: 1, dir: '/w/m', task: 'test', line: 'line ' + i } });
  }
  assert.equal(cards[0].output.length, MAX_OUTPUT_LINES);
  assert.equal(cards[0].output.at(-1).line, 'line ' + (MAX_OUTPUT_LINES + 5));
  foldEvent(cards, finish(1, { success: true }));
  assert.equal(cards[0].output.length, 0); // the console tail is an in-flight affordance
});

test('diagnostics attach to their module by dir, survive finish, and are capped per module', async () => {
  const { MAX_DIAGNOSTICS } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'diagnostic',
    data: { requestId: 1, dir: '/w/core', task: 'test', code: 'fail', message: 'expected 3 but was 4',
            test: 'adds()', exceptionClass: 'AssertionFailedError' },
  });
  foldEvent(cards, {
    type: 'diagnostic',
    data: { requestId: 1, dir: '/w/api', task: 'lock', code: 'resolve', message: 'no versions for com.foo:bar' },
  });
  foldEvent(cards, finish(1, { success: false }));
  const core = cards[0].modules.find((m) => m.dir === '/w/core');
  const api = cards[0].modules.find((m) => m.dir === '/w/api');
  assert.equal(core.diagnostics.length, 1); // NOT cleared on finish, unlike output
  assert.equal(core.diagnostics[0].test, 'adds()');
  assert.equal(api.diagnostics.length, 1);
  assert.equal(api.diagnostics[0].step, 'lock');
  for (let i = 0; i < MAX_DIAGNOSTICS + 5; i++) {
    foldEvent(cards, { type: 'diagnostic', data: { requestId: 1, dir: '/w/core', task: 'p', message: 'm' + i } });
  }
  assert.equal(core.diagnostics.length, MAX_DIAGNOSTICS); // compile/other still capped
  const startLen = core.diagnostics.length;
  for (let i = 0; i < 15; i++) {
    foldEvent(cards, {
      type: 'diagnostic',
      data: {
        requestId: 1,
        dir: '/w/core',
        task: 'run-tests',
        code: 'test-failure',
        message: 'fail ' + i,
        class: 'T',
        method: 'm' + i + '()',
      },
    });
  }
  assert.equal(core.diagnostics.length, startLen + 15); // test-failure has its own, higher cap
});

test('test-failure diagnostics are bounded by their own ceiling (JK-1881)', async () => {
  const { MAX_TEST_FAILURE_DIAGNOSTICS } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  for (let i = 0; i < MAX_TEST_FAILURE_DIAGNOSTICS + 40; i++) {
    foldEvent(cards, {
      type: 'diagnostic',
      data: {
        requestId: 1,
        dir: '/w/core',
        task: 'run-tests',
        code: 'test-failure',
        message: 'fail ' + i,
        class: 'T',
        method: 'm' + i + '()',
      },
    });
  }
  const core = cards[0].modules.find((m) => m.dir === '/w/core');
  assert.equal(
    core.diagnostics.filter((d) => isTestFailureDiag(d)).length,
    MAX_TEST_FAILURE_DIAGNOSTICS,
  );
  // Other codes still get their slice under the flood.
  foldEvent(cards, { type: 'diagnostic', data: { requestId: 1, dir: '/w/core', task: 'p', message: 'other' } });
  assert.equal(core.diagnostics.length, MAX_TEST_FAILURE_DIAGNOSTICS + 1);
});

test('module summary counts modules and failures', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  assert.equal(moduleSummary(cards[0]), '');
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/a', success: true, millis: 5 } });
  assert.equal(moduleSummary(cards[0]), '1 module');
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/b', success: false, millis: 5 } });
  assert.equal(moduleSummary(cards[0]), '2 modules · 1 failed');
});

test('seedFromHistory adds finished cards, newest first', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('20260101T000000000-aaaa', '/w/a', { finishedAt: 1000 }),
    historyRecord('20260101T000001000-bbbb', '/w/b', { finishedAt: 5000 }),
  ]);
  assert.equal(cards.length, 2);
  assert.equal(cards[0].historyId, '20260101T000001000-bbbb'); // newest first
  assert.equal(outcomeOf(cards[0]), 'success');
  assert.equal(cards[0].id, 'h:20260101T000001000-bbbb');
});

test('seedFromHistory is idempotent (no duplicate on re-seed)', () => {
  const cards = [];
  const rec = historyRecord('20260101T000000000-aaaa', '/w/a');
  seedFromHistory(cards, [rec]);
  seedFromHistory(cards, [rec]);
  assert.equal(cards.length, 1);
});

test('seedFromHistory seeds a running in-flight journal row', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('20260101T000000000-run1', '/w/a', {
      finishedAt: 0,
      millis: 0,
      success: false,
      running: true,
      buildNumber: 27,
    }),
  ]);
  assert.equal(cards.length, 1);
  assert.equal(cards[0].state, 'running');
  assert.equal(cards[0].buildNumber, 27);
  assert.equal(outcomeOf(cards[0]), 'running');
  assert.equal(cards[0].id, 'h:20260101T000000000-run1'); // no live requestId yet
});

test('seedFromHistory uses live requestId when history is enriched', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('20260101T000000000-run1', '/w/a', {
      finishedAt: 0,
      millis: 0,
      running: true,
      buildNumber: 27,
      requestId: 42,
      progress: 61,
    }),
  ]);
  assert.equal(cards[0].id, 42);
  assert.equal(cards[0].progressPercent, 61);
});

test('mid-build history enrich carries startedAt, residual, and prior phases', () => {
  const cards = [];
  const t0 = 1_700_000_000_000;
  const before = Date.now();
  seedFromHistory(cards, [
    historyRecord('20260101T000000000-run1', '/w/a', {
      finishedAt: 0,
      millis: 0,
      running: true,
      buildNumber: 27,
      requestId: 99,
      startedAt: t0,
      progress: 55,
      remainingMs: 40_000,
      R0: 90_000,
      numerator: 110,
      denominator: 200,
      tasks: [
        { name: 'resolve', stage: 'resolve', status: 'SUCCESS', millis: 120 },
        { name: 'compile-java', stage: 'compile', status: 'RUN', millis: 0 },
      ],
    }),
  ]);
  const after = Date.now();
  const c = cards[0];
  assert.equal(c.id, 99);
  assert.equal(c.startedAt, t0);
  assert.equal(c.progressPercent, 55);
  assert.equal(c.peakPct, 55);
  assert.equal(c.residualRemainingMs, 40_000);
  // remaining is as-of the GET — residualAt is "now", not admission time.
  assert.ok(c.residualAt >= before && c.residualAt <= after);
  assert.equal(c.r0Ms, 90_000);
  assert.equal(c.r0At, t0);
  assert.equal(c.modules.length, 1);
  assert.equal(c.modules[0].dir, ''); // single-plan live key
  assert.equal(c.modules[0].state, 'running');
  assert.deepEqual(
    c.modules[0].steps.map((s) => s.name + ':' + s.state),
    ['resolve:success', 'compile-java:running'],
  );
});

test('seedFromHistory prefers engine startedAt over late SSE receipt time', () => {
  const cards = [];
  const t0 = 1_000;
  // Tab joined late: request-start arrived without startedAt → browser clock.
  foldEvent(cards, { type: 'request-start', data: { requestId: 7, kind: 'build', dir: '/w', buildNumber: 3 }, at: 50_000 });
  assert.equal(cards[0].startedAt, 50_000);
  seedFromHistory(cards, [
    historyRecord('r', '/w', {
      finishedAt: 0,
      millis: 0,
      running: true,
      buildNumber: 3,
      requestId: 7,
      startedAt: t0,
      progress: 40,
    }),
  ]);
  assert.equal(cards.length, 1);
  assert.equal(cards[0].startedAt, t0);
  assert.equal(cards[0].progressPercent, 40);
});

test('request-start rehydrate carries engine startedAt', () => {
  const cards = [];
  foldEvent(cards, {
    type: 'request-start',
    data: { requestId: 3, kind: 'build', dir: '/w', buildNumber: 1, startedAt: 9_000 },
    at: 99_000,
  });
  assert.equal(cards[0].startedAt, 9_000);
  // Second rehydrate must not clobber the earlier engine start with a later receipt.
  foldEvent(cards, {
    type: 'request-start',
    data: { requestId: 3, kind: 'build', dir: '/w', buildNumber: 1, startedAt: 9_000 },
    at: 120_000,
  });
  assert.equal(cards[0].startedAt, 9_000);
  assert.equal(cards.length, 1);
});

test('run-snapshot applies phases + progress in one frame (no phase-replay backlog)', () => {
  const cards = [];
  const t0 = 5_000;
  foldEvent(cards, {
    type: 'run-snapshot',
    data: {
      requestId: 11,
      kind: 'build',
      dir: '/w',
      buildNumber: 4,
      startedAt: t0,
      progress: 62,
      remainingMs: 30_000,
      R0: 80_000,
      numerator: 124,
      denominator: 200,
      tasks: [
        { name: 'resolve', stage: 'resolve', status: 'SUCCESS', millis: 80 },
        { name: 'compile-java', stage: 'compile', status: 'RUN', millis: 0 },
      ],
    },
    at: 50_000,
  });
  assert.equal(cards.length, 1);
  assert.equal(cards[0].id, 11);
  assert.equal(cards[0].startedAt, t0);
  assert.equal(cards[0].progressPercent, 62);
  assert.equal(cards[0].peakPct, 62);
  assert.equal(cards[0].residualRemainingMs, 30_000);
  assert.equal(cards[0].modules[0].steps.length, 2);
  assert.equal(cards[0].modules[0].steps[1].state, 'running');
  // Live progress after snapshot must attach to the same card (not a second one).
  foldEvent(cards, {
    type: 'workspace-progress',
    data: {
      requestId: 11,
      dir: '/w',
      numerator: 140,
      denominator: 200,
      progress: 70,
      remainingMs: 25_000,
    },
    at: 55_000,
  });
  assert.equal(cards.length, 1);
  assert.equal(cards[0].progressPercent, 70);
  assert.equal(cards[0].residualRemainingMs, 25_000);
});

test('pre-execute eta re-seed replaces a provisional R0; execute freezes it (JK-1854)', () => {
  const cards = [];
  foldEvent(cards, { type: 'request-start', data: { requestId: 41, kind: 'build', dir: '/w' }, at: 1000 });
  // Coarse lock+prior figure during lock contention.
  foldEvent(cards, { type: 'eta', data: { requestId: 41, millis: 90_000 }, at: 1100 });
  assert.equal(cards[0].r0Ms, 90_000);
  // Post-forecast refined seed, still pre-execute: replaces (CLI parity).
  foldEvent(cards, { type: 'eta', data: { requestId: 41, millis: 30_000 }, at: 2000 });
  assert.equal(cards[0].r0Ms, 30_000);
  assert.equal(cards[0].residualRemainingMs, 30_000);
  // Execute begins (module work folds) — a later eta no longer rewrites R0.
  foldEvent(cards, {
    type: 'task-start',
    data: { requestId: 41, dir: '/w/app', task: 'compile-java', stage: 'compile' },
    at: 3000,
  });
  foldEvent(cards, { type: 'eta', data: { requestId: 41, millis: 70_000 }, at: 4000 });
  assert.equal(cards[0].r0Ms, 30_000);
});

test('run-snapshot carries finished/didWork/historyId and the SPA stops guessing (JK-1846)', () => {
  const cards = [];
  foldEvent(cards, {
    type: 'run-snapshot',
    data: {
      requestId: 31,
      kind: 'build',
      dir: '/w',
      historyId: '20260101T000000000-lock1',
      startedAt: 1000,
      serverNow: 5000,
      modules: [
        // Module-level failure with no FAIL-status task: guessing called this "running".
        { dir: '/w/app', finished: true, success: false, millis: 900, didWork: true,
          tasks: [{ name: 'compile-java', stage: 'compile', status: 'SUCCESS', millis: 900 }] },
        { dir: '/w/lib', finished: true, success: true, millis: 12, didWork: false,
          tasks: [{ name: 'check', stage: 'compile', status: 'SUCCESS', millis: 12 }] },
        { dir: '/w/cli', finished: false, success: false, millis: 0,
          tasks: [{ name: 'compile-java', stage: 'compile', status: 'RUN', millis: 0 }] },
      ],
    },
    at: 9000,
  });
  const card = cards[0];
  assert.equal(card.historyId, '20260101T000000000-lock1');
  const byDir = Object.fromEntries(card.modules.map((m) => [m.dir, m]));
  assert.equal(byDir['/w/app'].state, 'failed');
  assert.equal(byDir['/w/lib'].state, 'checked');
  assert.equal(byDir['/w/lib'].didWork, false);
  assert.equal(byDir['/w/cli'].state, 'running');
});

test('serverNow re-anchors engine startedAt to the client epoch under skew (JK-1839)', () => {
  const cards = [];
  // Engine clock runs 30s AHEAD of the browser: engine says the run started 10s ago.
  const clientReceipt = 100_000;
  const engineNow = 130_000;
  const engineStart = engineNow - 10_000;
  foldEvent(cards, {
    type: 'run-snapshot',
    data: {
      requestId: 21,
      kind: 'build',
      dir: '/w',
      startedAt: engineStart,
      serverNow: engineNow,
      progress: 40,
      remainingMs: 20_000,
      tasks: [{ name: 'compile-java', stage: 'compile', status: 'RUN', millis: 0 }],
    },
    at: clientReceipt,
  });
  const card = cards[0];
  // Engine-epoch identity is preserved for history reconciliation…
  assert.equal(card.startedAt, engineStart);
  // …but elapsed math gets a client-epoch anchor: 10s before receipt, skew cancelled.
  assert.equal(card.startedAtClient, clientReceipt - 10_000);
  assert.equal(startAnchor(card), clientReceipt - 10_000);

  // A later live request-start (serverNow ≈ engine now) must not move the anchor forward.
  foldEvent(cards, {
    type: 'request-start',
    data: { requestId: 21, kind: 'build', dir: '/w', startedAt: engineStart, serverNow: engineNow + 5_000 },
    at: clientReceipt + 5_000,
  });
  assert.equal(card.startedAtClient, clientReceipt - 10_000);
});

test('stale run-snapshot never resurrects a finished card (JK-1837)', () => {
  const cards = [];
  foldEvent(cards, { type: 'request-start', data: { requestId: 9, kind: 'build', dir: '/w' }, at: 1000 });
  foldEvent(cards, {
    type: 'request-finish',
    data: { requestId: 9, dir: '/w', success: true, millis: 4200 },
    at: 5000,
  });
  // A snapshot captured while the run was still live lands after the finish frame.
  foldEvent(cards, {
    type: 'run-snapshot',
    data: {
      requestId: 9,
      kind: 'build',
      dir: '/w',
      startedAt: 800,
      progress: 90,
      tasks: [{ name: 'run-tests', stage: 'test', status: 'RUN', millis: 0 }],
    },
    at: 5001,
  });
  assert.equal(cards.length, 1);
  assert.equal(cards[0].state, 'finished');
  assert.equal(cards[0].millis, 4200);
});

test('seedFromHistory finishes a running card when the record says the run is over (JK-1837)', () => {
  const cards = [];
  foldEvent(cards, {
    type: 'request-start',
    data: { requestId: 12, kind: 'build', dir: '/w/a', buildNumber: 31 },
    at: 1000,
  });
  foldEvent(cards, {
    type: 'task-start',
    data: { requestId: 12, dir: '/w/a', task: 'run-tests', stage: 'test' },
    at: 1500,
  });
  assert.equal(cards[0].state, 'running');
  // The request-finish frame was lost in the connect window; the journal is durable truth.
  seedFromHistory(cards, [
    historyRecord('20260101T000000000-run9', '/w/a', {
      running: false,
      buildNumber: 31,
      success: true,
      finishedAt: 9000,
      millis: 8000,
      tasks: [{ name: 'run-tests', stage: 'test', status: 'SUCCESS', millis: 7000 }],
    }),
  ]);
  assert.equal(cards.length, 1);
  assert.equal(cards[0].state, 'finished');
  assert.equal(cards[0].success, true);
  assert.equal(cards[0].millis, 8000);
  const steps = cards[0].modules[0].steps;
  assert.equal(steps.find((s) => s.name === 'run-tests').state, 'success');
});

test('reconnect run-snapshot preserves live diagnostics, failed state, and checked modules (JK-1834)', () => {
  const cards = [];
  foldEvent(cards, { type: 'request-start', data: { requestId: 7, kind: 'build', dir: '/w' }, at: 1000 });
  // Live stream reports a checked module, then a module-level failure with diagnostics.
  foldEvent(cards, {
    type: 'module-finish',
    data: { requestId: 7, dir: '/w/lib', success: true, didWork: false, millis: 12 },
    at: 1500,
  });
  foldEvent(cards, {
    type: 'diagnostic',
    data: { requestId: 7, dir: '/w/app', task: 'run-tests', code: 'test-failure', message: 'FooTest.bar failed' },
    at: 2000,
  });
  foldEvent(cards, {
    type: 'module-finish',
    data: { requestId: 7, dir: '/w/app', success: false, millis: 900 },
    at: 2100,
  });
  // EventSource reconnects: the new subscription's snapshot has chains but no diagnostics/didWork.
  foldEvent(cards, {
    type: 'run-snapshot',
    data: {
      requestId: 7,
      kind: 'build',
      dir: '/w',
      startedAt: 900,
      progress: 80,
      modules: [
        { dir: '/w/lib', success: true, millis: 12, tasks: [{ name: 'check', stage: 'compile', status: 'SUCCESS', millis: 12 }] },
        { dir: '/w/app', success: false, millis: 900, tasks: [{ name: 'run-tests', stage: 'test', status: 'RUN', millis: 0 }] },
        { dir: '/w/cli', success: false, millis: 0, tasks: [{ name: 'compile-java', stage: 'compile', status: 'RUN', millis: 0 }] },
      ],
    },
    at: 3000,
  });
  const card = cards[0];
  const byDir = Object.fromEntries(card.modules.map((m) => [m.dir, m]));
  // Diagnostics and the reported failure survive the snapshot replace.
  assert.equal(byDir['/w/app'].diagnostics.length, 1);
  assert.equal(byDir['/w/app'].diagnostics[0].message, 'FooTest.bar failed');
  assert.equal(byDir['/w/app'].state, 'failed');
  // didWork=false keeps its checked rendering.
  assert.equal(byDir['/w/lib'].state, 'checked');
  // Snapshot-only modules still appear with the engine's chains.
  assert.equal(byDir['/w/cli'].state, 'running');
  assert.equal(byDir['/w/cli'].steps[0].name, 'compile-java');
});

test('mid-build refresh: history stub rebinds on workspace-progress and finishes', () => {
  // Hard refresh while a build is streaming: journal seeds h:… then SSE events use numeric requestId.
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('20260101T000000000-run1', '/w/a', {
      finishedAt: 0,
      millis: 0,
      running: true,
      buildNumber: 27,
      startedAt: 1000,
    }),
  ]);
  assert.equal(cards[0].id, 'h:20260101T000000000-run1');

  foldEvent(cards, {
    type: 'workspace-progress',
    data: { requestId: 99, dir: '/w/a', numerator: 50, denominator: 100, progress: 50 },
  });
  assert.equal(cards[0].id, 99); // rebind
  assert.equal(cards[0].progressPercent, 50);

  foldEvent(cards, {
    type: 'task-start',
    data: { requestId: 99, dir: '/w/a', task: 'compile-java', phase: 'compile' },
  });
  assert.equal(cards[0].modules[0].steps[0].name, 'compile-java');
  assert.equal(cards[0].modules[0].steps[0].state, 'running');

  foldEvent(cards, finish(99, { success: true, millis: 8000 }));
  assert.equal(outcomeOf(cards[0]), 'success');
  assert.equal(cards[0].millis, 8000);
});

test('request-start rehydrate is idempotent when card already has requestId', () => {
  const cards = [];
  foldEvent(cards, start(5, '/w/a', { buildNumber: 3 }));
  foldEvent(cards, start(5, '/w/a', { buildNumber: 3 })); // SSE connect rehydrate
  assert.equal(cards.length, 1);
  assert.equal(cards[0].id, 5);
});

test('seedFromHistory reconciles running SSE card with durable in-flight row', () => {
  const cards = [];
  foldEvent(cards, start(9, '/w/a', { buildNumber: 27 }));
  seedFromHistory(cards, [
    historyRecord('20260101T000000000-run1', '/w/a', {
      finishedAt: 0,
      millis: 0,
      running: true,
      buildNumber: 27,
    }),
  ]);
  assert.equal(cards.length, 1);
  assert.equal(cards[0].id, 9);
  assert.equal(cards[0].historyId, '20260101T000000000-run1');
  assert.equal(cards[0].buildNumber, 27);
});

test('seedFromHistory reconciles a live card instead of duplicating it', () => {
  const cards = [];
  foldEvent(cards, start(7, '/w/a'));
  foldEvent(cards, finish(7, { success: true, millis: 1000 }));
  cards[0].finishedAt = 2000; // matches the record below
  seedFromHistory(cards, [historyRecord('20260101T000000000-aaaa', '/w/a', { finishedAt: 2000 })]);
  assert.equal(cards.length, 1); // live card reused, not duplicated
  assert.equal(cards[0].id, 7); // still the live numeric-id card
  assert.equal(cards[0].historyId, '20260101T000000000-aaaa'); // now deletable
});

test('seedFromHistory respects MAX_CARDS', () => {
  const cards = [];
  const records = [];
  for (let i = 0; i < MAX_CARDS + 10; i++) {
    records.push(historyRecord('id-' + String(i).padStart(4, '0'), '/w/' + i, { finishedAt: 1000 + i }));
  }
  seedFromHistory(cards, records);
  assert.equal(cards.length, MAX_CARDS);
});

test('weight progress aggregates numerator/denominator across modules', async () => {
  const { weightNumerator, weightDenominator } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'plan', data: { requestId: 1, weight: 300 } });
  foldEvent(cards, { type: 'plan-progress', data: { requestId: 1, dir: '/w/a', numerator: 50, denominator: 100 } });
  foldEvent(cards, { type: 'plan-progress', data: { requestId: 1, dir: '/w/b', numerator: 20, denominator: 100 } });
  assert.equal(weightNumerator(cards[0]), 70);
  // denominator = max(planWeight 300, sum of module dens 200) = 300 — stable, no backward jump
  assert.equal(weightDenominator(cards[0]), 300);
});

test('weight denominator falls back to summed module dens when no plan (single build)', async () => {
  const { weightDenominator } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'plan-progress', data: { requestId: 1, dir: '', numerator: 4, denominator: 12 } });
  assert.equal(weightDenominator(cards[0]), 12);
});

test('plan-progress updates latest per dir (no double count on repeat)', async () => {
  const { weightNumerator } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'plan-progress', data: { requestId: 1, dir: '/w/a', numerator: 10, denominator: 100 } });
  foldEvent(cards, { type: 'plan-progress', data: { requestId: 1, dir: '/w/a', numerator: 80, denominator: 100 } });
  assert.equal(weightNumerator(cards[0]), 80); // latest wins, not 10+80
});

test('eta is captured and cleared on finish', () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w'), at: 1000 });
  foldEvent(cards, { ...{ type: 'eta', data: { requestId: 1, millis: 5000 } }, at: 1200 });
  assert.equal(cards[0].etaMillis, 5000);
  assert.equal(cards[0].etaAt, 1200);
  foldEvent(cards, finish(1, { success: true }));
  assert.equal(cards[0].etaMillis, null); // countdown stops on finish
});

test('etaTotalMillis anchors remaining work at the emission time, not request-start (JK-1517)', async () => {
  const { etaTotalMillis } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, { ...start(1, '/w'), at: 1000 });
  // 10s into the run (slow lock/prepare), the engine projects 30s of REMAINING work.
  foldEvent(cards, { ...{ type: 'eta', data: { requestId: 1, millis: 30_000 } }, at: 11_000 });
  // Run-wide total = elapsed-at-emission (10s) + remaining (30s), not 30s.
  assert.equal(etaTotalMillis(cards[0]), 40_000);

  // A later re-projection replaces the anchor — still no double count.
  foldEvent(cards, { ...{ type: 'eta', data: { requestId: 1, millis: 25_000 } }, at: 21_000 });
  assert.equal(etaTotalMillis(cards[0]), 45_000);

  // Journal-seeded shape (no etaAt): millis is treated as the total.
  assert.equal(etaTotalMillis({ etaMillis: 30_000, etaAt: null, startedAt: 1000 }), 30_000);
  assert.equal(etaTotalMillis({ etaMillis: null }), null);
  assert.equal(etaTotalMillis({ etaMillis: 0 }), null);
});

test('phaseChainOf collapses steps into coarse phase nodes in encounter order', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  const step = (name, phase, status) => {
    foldEvent(cards, { type: 'task-start', data: { requestId: 1, dir: '', task: name, stage: phase } });
    if (status) foldEvent(cards, { type: 'task-finish', data: { requestId: 1, dir: '', task: name, stage: phase, status } });
  };
  step('resolve-deps', 'resolve', 'SUCCESS');
  step('compile-java', 'compile', 'SUCCESS');
  step('compile-kotlin', 'compile', 'SUCCESS');
  step('run-tests', 'test', 'SUCCESS');
  const chain = phaseChainOf(cards[0].modules[0]);
  assert.deepEqual(chain.map((p) => p.label), ['Resolve', 'Compile', 'Test']); // one node per phase, in order
  assert.deepEqual(chain.map((p) => p.state), ['success', 'success', 'success']);
  assert.deepEqual(chain[1].steps.map((s) => s.name), ['compile-java', 'compile-kotlin']); // Compile collapses both
});

test('phaseChainOf paints skip when compile is SKIPPED and stamp is 0ms success', () => {
  // Journal shape: compile-java SKIPPED + write-stamp SUCCESS@0ms must not be solid green.
  const chain = phaseChainOf({
    steps: [
      { name: 'compile-java', phase: 'compile', state: 'skipped', millis: 0 },
      { name: 'write-stamp', phase: 'compile', state: 'success', millis: 0 },
      { name: 'build-logic-after-compile', phase: 'compile', state: 'success', millis: 0 },
    ],
  });
  assert.equal(chain[0].state, 'skipped');
});

test('task-finish SUCCESS with 0ms paints as skipped', () => {
  const cards = [];
  foldEvent(cards, { type: 'request-start', data: { requestId: 1, kind: 'build', dir: '/w' } });
  foldEvent(cards, {
    type: 'task-finish',
    data: { requestId: 1, dir: '', task: 'write-stamp', stage: 'compile', status: 'SUCCESS', millis: 0 },
  });
  assert.equal(cards[0].modules[0].steps[0].state, 'skipped');
});

test('task-finish stores engine millis on the step row', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  foldEvent(cards, {
    type: 'task-start',
    data: { requestId: 1, dir: '', task: 'ensure-jdk', stage: 'resolve' },
    at: 1000,
  });
  foldEvent(cards, {
    type: 'task-finish',
    data: { requestId: 1, dir: '', task: 'ensure-jdk', stage: 'resolve', status: 'SUCCESS', millis: 360 },
    at: 1500,
  });
  assert.equal(cards[0].modules[0].steps[0].millis, 360);
});

test('task-finish falls back to receipt delta when millis is absent', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  foldEvent(cards, {
    type: 'task-start',
    data: { requestId: 1, dir: '', task: 'compile-tests', stage: 'compile' },
    at: 1000,
  });
  foldEvent(cards, {
    type: 'task-finish',
    data: { requestId: 1, dir: '', task: 'compile-tests', stage: 'compile', status: 'SUCCESS' },
    at: 1212,
  });
  assert.equal(cards[0].modules[0].steps[0].millis, 212);
});

test('history seed preserves per-step millis for tooltips', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('h1', '/w', {
      steps: [
        { name: 'ensure-jdk', stage: 'resolve', status: 'SUCCESS', millis: 360 },
        { name: 'resolve-deps', stage: 'resolve', status: 'SUCCESS', millis: 1200 },
      ],
    }),
  ]);
  const steps = cards[0].modules[0].steps;
  assert.equal(steps[0].millis, 360);
  assert.equal(steps[1].millis, 1200);
  assert.equal(stepTimingLabel(steps[0]), 'ensure-jdk (360ms)');
  assert.equal(stepTimingLabel(steps[1]), 'resolve-deps (1.2s)');
});

test('fmtStepMillis is compact for tooltips', () => {
  assert.equal(fmtStepMillis(null), '');
  assert.equal(fmtStepMillis(0), '0ms');
  assert.equal(fmtStepMillis(360), '360ms');
  assert.equal(fmtStepMillis(1200), '1.2s');
  assert.equal(fmtStepMillis(12_000), '12s');
  assert.equal(fmtStepMillis(65_000), '1m 5s');
  assert.equal(stepTimingLabel({ name: 'compile-tests', millis: 212 }), 'compile-tests (212ms)');
  assert.equal(stepTimingLabel({ name: 'compile-tests', millis: null }), 'compile-tests');
});

test('phaseChainOf state precedence: failed > running > success', () => {
  const running = phaseChainOf({ steps: [
    { name: 'a', phase: 'compile', state: 'success' },
    { name: 'b', phase: 'compile', state: 'running' },
  ] });
  assert.equal(running[0].state, 'running'); // running dominates a sibling success
  const failed = phaseChainOf({ steps: [
    { name: 'a', phase: 'test', state: 'running' },
    { name: 'b', phase: 'test', state: 'failed' },
  ] });
  assert.equal(failed[0].state, 'failed'); // failed dominates running
});

test('phaseChainOf appends an unknown/plugin phase verbatim (dumb client)', () => {
  const chain = phaseChainOf({ steps: [
    { name: 'compile-java', phase: 'compile', state: 'success' },
    { name: 'deploy-k8s', phase: 'deploy', state: 'running' }, // a phase the client has never heard of
  ] });
  assert.deepEqual(chain.map((p) => p.label), ['Compile', 'Deploy']); // no enum coupling — just capitalized
});

test('phaseChainOf keeps an unphased step as its own node, keyed by name', () => {
  const chain = phaseChainOf({ steps: [{ name: 'lock', phase: '', state: 'success' }] });
  assert.equal(chain.length, 1);
  assert.equal(chain[0].phase, '');
  assert.equal(chain[0].key, 'lock'); // last-resort: keyed by the step name so it is never dropped
  assert.equal(chain[0].label, 'Lock');
});

test('label event stores live tick text on the running step', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'task-start',
    data: { requestId: 1, dir: '', task: 'run-tests', phase: 'test' },
  });
  foldEvent(cards, {
    type: 'label',
    data: { requestId: 1, dir: '', task: 'run-tests', label: 'g:a :: FooTest.bar()  [w2]' },
  });
  const step = cards[0].modules[0].steps[0];
  assert.equal(step.message, 'g:a :: FooTest.bar()  [w2]');
  assert.equal(liveStepDetail('g:a', cards[0].modules[0].steps), 'FooTest.bar()  [w2]');
});

test('detailForDisplay strips a leading module :: prefix', () => {
  assert.equal(detailForDisplay('g:a', 'g:a :: FooTest.t()'), 'FooTest.t()');
  assert.equal(detailForDisplay('g:a', 'shrinking jar'), 'shrinking jar');
  assert.equal(detailForDisplay('g:a', ''), '');
});

test('looksLikeJavaMember detects Class.method form', () => {
  assert.equal(looksLikeJavaMember('FooTest.bar(Path)'), true);
  assert.equal(looksLikeJavaMember('FooTest'), true);
  assert.equal(looksLikeJavaMember('shrinking jar'), false);
});

test('detailSegments syntax-highlights test members and mid-grays prose', () => {
  const java = detailSegments('VariantSwitchTest.switching_variants(Path)');
  assert.ok(java.some((s) => s.cls === 'det-type' && s.text === 'VariantSwitchTest'));
  assert.ok(java.some((s) => s.cls === 'det-fn' && s.text === 'switching_variants'));
  assert.ok(java.some((s) => s.cls === 'det-type' && s.text === 'Path'));

  const prose = detailSegments('compiling 12 Groovy test sources');
  assert.ok(prose.some((s) => s.cls === 'det-num' && s.text === '12'));
  assert.ok(prose.some((s) => s.cls === 'det-mid' && s.text === 'compiling'));

  const withWorker = detailSegments('FooTest.bar()  [w2]');
  assert.ok(withWorker.some((s) => s.cls === 'det-mid' && s.text === '  [w2]'));
});

test('detailSegments paints ensure-jdk download and install labels', () => {
  const down = detailSegments('downloading Temurin 25 ▰▰▰▰▰▱▱▱▱▱ 50%');
  assert.ok(down.some((s) => s.cls === 'det-mid' && s.text === 'downloading'));
  assert.ok(down.some((s) => s.cls === 'det-jdk' && s.text === 'Temurin 25'));
  assert.ok(down.filter((s) => s.cls === 'det-bar-fill' && s.text === '▰').length === 5);
  assert.ok(down.filter((s) => s.cls === 'det-bar-empty' && s.text === '▱').length === 5);
  assert.ok(down.some((s) => s.cls === 'det-mid' && s.text === '50%'));

  const inst = detailSegments('installing Temurin 25 ▰▰▰▰▰▰▰▰▰▰ 100%');
  assert.ok(inst.some((s) => s.cls === 'det-mid' && s.text === 'installing'));
  assert.ok(inst.some((s) => s.cls === 'det-jdk' && s.text === 'Temurin 25'));
  assert.ok(inst.filter((s) => s.cls === 'det-bar-fill').length === 10);
});

test('request-finish folds the run\'s byte counters onto the card', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, finish(1, {
    success: true,
    millis: 900,
    remoteUpBytes: 0,
    remoteDownBytes: 8_388_608,
    localUpBytes: 2_048,
    localDownBytes: 4_096,
  }));
  assert.deepEqual(cards[0].io, { remoteUp: 0, remoteDown: 8_388_608, localUp: 2_048, localDown: 4_096 });
  assert.deepEqual(ioLines(cards[0]), [
    { scope: 'remote', label: 'remote data', up: 0, down: 8_388_608 },
    { scope: 'local', label: 'local data', up: 2_048, down: 4_096 },
  ]);
});

test('a run that moved no bytes gets no io block', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, finish(1, { success: true, millis: 12 })); // engine omits the fields entirely
  assert.equal(cards[0].io, null);
  assert.deepEqual(ioLines(cards[0]), []);
});

test('ioLines drops a scope with no traffic (warm offline build shows local only)', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, finish(1, { success: true, millis: 40, localDownBytes: 1_000 }));
  assert.deepEqual(ioLines(cards[0]), [{ scope: 'local', label: 'local data', up: 0, down: 1_000 }]);
  assert.deepEqual(ioLines({}), []); // no card / no io → nothing to render
});

test('history records carry their nested io block onto the seeded card', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('h1', '/w', { io: { remoteUp: 10, remoteDown: 20, localUp: 30, localDown: 40 } }),
    historyRecord('h2', '/w2'),
  ]);
  const seeded = cards.find((c) => c.historyId === 'h1');
  assert.deepEqual(seeded.io, { remoteUp: 10, remoteDown: 20, localUp: 30, localDown: 40 });
  assert.equal(cards.find((c) => c.historyId === 'h2').io, null); // older record, no io key
});

test('fmtBytes picks the unit that keeps the number small', () => {
  assert.equal(fmtBytes(0), '0 B');
  assert.equal(fmtBytes(-1), '0 B');
  assert.equal(fmtBytes(null), '0 B');
  assert.equal(fmtBytes(512), '512 B');
  assert.equal(fmtBytes(1024), '1 KiB'); // trailing .0 dropped
  assert.equal(fmtBytes(102_400), '100 KiB');
  assert.equal(fmtBytes(13_000_000), '12.4 MiB');
  assert.equal(fmtBytes(536_870_912), '512 MiB'); // >= 100 → whole numbers
  assert.equal(fmtBytes(1_607_467_008), '1.5 GiB'); // never 1533 MiB
  assert.equal(fmtBytes(1_099_511_627_776), '1 TiB');
});

test('FAIL steps beat a cancelled bit on the card (test failure must not read as cancelled)', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('fail1', '/w', {
      success: false,
      cancelled: true, // journal race / cooperative fail-fast used to stamp this
      modules: [
        {
          coord: 'g:core',
          dir: '/w/core',
          success: false,
          exitCode: 4,
          millis: 100,
          steps: [
            { name: 'compile-java', status: 'SUCCESS', phase: 'compile' },
            { name: 'run-tests', status: 'FAIL', phase: 'test' },
          ],
        },
      ],
      diagnostics: [
        {
          severity: 'error',
          dir: '/w/core',
          step: 'run-tests',
          code: 'test-failure',
          message: 'expected 1 but was 90',
        },
      ],
    }),
  ]);
  assert.equal(outcomeOf(cards[0]), 'failed');
});

test('cancelled without FAIL steps still reads as cancelled', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('cancel1', '/w', {
      success: false,
      cancelled: true,
      modules: [
        {
          coord: 'g:core',
          dir: '/w/core',
          success: false,
          exitCode: 1,
          millis: 50,
          steps: [
            { name: 'compile-java', status: 'SUCCESS', phase: 'compile' },
            { name: 'run-tests', status: 'CANCELLED', phase: 'test' },
          ],
        },
      ],
    }),
  ]);
  assert.equal(outcomeOf(cards[0]), 'cancelled');
});

// ---- test-failure rich report (CLI TestFailureHighlight parity) ----

test('normalizeDiagnostic keeps snippet / identity fields for test failures', () => {
  const d = normalizeDiagnostic({
    task: 'run-tests',
    code: 'test-failure',
    message: '[dogfood] expected: "42"\n but was: "41"',
    module: 'cc.jumpkick:jk-engine',
    class: 'cc.jumpkick.runtime.DogfoodFailureSnippetTest',
    method: 'deliberately_fails_to_show_source_snippet()',
    exceptionClass: 'org.opentest4j.AssertionFailedError',
    file: 'src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java',
    line: 23,
    snippetStart: 19,
    snippet: ['', '        // comment', '                .isEqualTo(42);', '    }', '}'],
  });
  assert.equal(d.step, 'run-tests');
  assert.equal(d.className, 'cc.jumpkick.runtime.DogfoodFailureSnippetTest');
  assert.equal(d.file, 'src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java');
  assert.equal(d.line, 23);
  assert.equal(d.snippetStart, 19);
  assert.equal(d.snippet.length, 5);
  assert.equal(d.module, 'cc.jumpkick:jk-engine');
});

test('isTestFailureDiag only matches structured per-test code', () => {
  assert.equal(isTestFailureDiag({ code: 'test-failure' }), true);
  assert.equal(isTestFailureDiag({ code: 'error' }), false);
  assert.equal(isTestFailureDiag(null), false);
});

test('parseAssertJMessage reformats description + expected/but was', () => {
  const a = parseAssertJMessage('[dogfood: hello]\nexpected: "42"\n but was: "41"');
  assert.ok(a);
  assert.equal(a.desc, 'dogfood: hello');
  assert.equal(a.expected, '42');
  assert.equal(a.actual, '41');
  assert.equal(parseAssertJMessage('plain boom'), null);
});

test('shortTestLabel strips package and keeps method params simplified', () => {
  assert.equal(simpleTypeName('org.opentest4j.AssertionFailedError'), 'AssertionFailedError');
  assert.equal(
    shortTestLabel({
      className: 'cc.jumpkick.runtime.DogfoodFailureSnippetTest',
      method: 'deliberately_fails_to_show_source_snippet()',
    }),
    'DogfoodFailureSnippetTest.deliberately_fails_to_show_source_snippet()',
  );
  assert.equal(
    shortTestLabel({
      className: 'cc.jumpkick.Foo',
      method: 'bar(java.nio.file.Path)',
    }),
    'Foo.bar(Path)',
  );
  // Wire may send FQCN on the free-form method field alone.
  assert.equal(
    shortTestLabel({
      method: 'cc.jumpkick.runtime.FooTest.freshen(java.nio.file.Path, java.lang.String)',
    }),
    'FooTest.freshen(Path, String)',
  );
  assert.equal(
    shortTestLabel({
      className: 'demo.FooTest',
      method: 'bar(java.lang.String[])',
    }),
    'FooTest.bar(String[])',
  );
  assert.equal(
    shortTestLabel({
      className: 'demo.FooTest',
      method: 'bar()',
      worker: 2,
    }),
    'FooTest.bar()  [w2]',
  );
});

test('shortDisplayLabel never leaves package FQCNs in client text', () => {
  assert.equal(
    shortDisplayLabel('cc.jumpkick.runtime.FooTest.bar(java.nio.file.Path)'),
    'FooTest.bar(Path)',
  );
  assert.equal(simplifyMethodParams('m(java.lang.String[])'), 'm(String[])');
  assert.equal(simplifyMethodParams('foo(java.lang.String)[#2]'), 'foo(String)[#2]');
  assert.equal(shortDisplayLabel('FooTest.bar(Path)  [w2]'), 'FooTest.bar(Path)  [w2]');
  // Live detail path shortens too.
  assert.equal(
    detailForDisplay('g:a', 'g:a :: cc.jumpkick.Foo.bar(java.util.List)'),
    'Foo.bar(List)',
  );
  const segs = detailSegments('cc.jumpkick.Foo.bar(java.nio.file.Path)');
  const text = segs.map((s) => s.text).join('');
  assert.equal(text, 'Foo.bar(Path)');
  assert.ok(!text.includes('java.nio'));
  // Prose / versions / jars stay intact.
  assert.equal(shortDisplayLabel('package jk-engine-0.12.0.jar'), 'package jk-engine-0.12.0.jar');
  assert.equal(shortDisplayLabel('compiling 12 sources'), 'compiling 12 sources');
  assert.equal(detailForDisplay('g:a', 'g:a :: shrinking jar'), 'shrinking jar');
});

test('testFailureReport builds CLI-shaped model with snippet rows and error line', () => {
  const d = normalizeDiagnostic({
    code: 'test-failure',
    message: '[dogfood: hello]\nexpected: "42"\n but was: "41"',
    module: 'cc.jumpkick:jk-engine',
    class: 'cc.jumpkick.runtime.DogfoodFailureSnippetTest',
    method: 'deliberately_fails_to_show_source_snippet()',
    exceptionClass: 'org.opentest4j.AssertionFailedError',
    file: 'src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java',
    line: 23,
    snippetStart: 20,
    snippet: [
      '        // comment',
      '        assertThat(41)',
      '                .isEqualTo(42);',
      '    }',
    ],
  });
  // Force error line into snippet range for the * marker
  d.line = 22;
  d.snippetStart = 20;
  const rep = testFailureReport(d, { count: 1, showHeader: true });
  assert.ok(rep);
  assert.equal(rep.showHeader, true);
  assert.equal(rep.count, 1);
  assert.equal(rep.module, 'cc.jumpkick:jk-engine');
  assert.equal(rep.label, 'DogfoodFailureSnippetTest.deliberately_fails_to_show_source_snippet()');
  assert.ok(rep.assertj);
  assert.equal(rep.assertj.desc, 'dogfood: hello');
  assert.equal(rep.assertj.expected, '42');
  assert.equal(rep.assertj.actual, '41');
  assert.equal(rep.file, 'src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java');
  assert.equal(rep.exceptionClass, 'AssertionFailedError');
  assert.equal(rep.line, 22);
  assert.equal(rep.rows.length, 4);
  assert.equal(rep.rows[0].num, 20);
  assert.deepEqual(rep.frames, []);
  assert.equal(rep.rows[2].num, 22);
  assert.equal(rep.rows[2].error, true);
  assert.equal(rep.rows[0].error, false);
  // subsequent failure suppresses the shared header
  const second = testFailureReport(d, { count: 2, showHeader: false });
  assert.equal(second.showHeader, false);
  assert.equal(second.count, 2);
});

test('testFailureReport falls back to stack frames when snippet is missing', () => {
  const d = normalizeDiagnostic({
    code: 'test-failure',
    message: 'boom',
    class: 'pkg.FooTest',
    method: 'bar()',
    exceptionClass: 'java.lang.AssertionError',
    stack: 'java.lang.AssertionError: boom\n\tat pkg.FooTest.bar(FooTest.java:4)\n\tat java.base/java.lang.Thread.run(Thread.java:1)\n',
  });
  const rep = testFailureReport(d, { count: 1 });
  assert.ok(rep);
  assert.equal(rep.file, '');
  assert.equal(rep.exceptionClass, 'AssertionError');
  assert.deepEqual(rep.frames, [
    '\tat pkg.FooTest.bar(FooTest.java:4)',
    '\tat java.base/java.lang.Thread.run(Thread.java:1)',
  ]);
  assert.deepEqual(stackFrameLines('not a stack'), []);
});

test('live diagnostic event folds snippet fields onto the module', () => {
  const cards = [];
  foldEvent(cards, start(42, '/w'));
  foldEvent(cards, {
    type: 'diagnostic',
    data: {
      requestId: 42,
      dir: '/w',
      task: 'run-tests',
      code: 'test-failure',
      message: 'expected: 1\nbut was: 2',
      module: 'g:a',
      class: 'pkg.FooTest',
      method: 'bar()',
      exceptionClass: 'org.opentest4j.AssertionFailedError',
      file: 'src/test/java/pkg/FooTest.java',
      line: 10,
      snippetStart: 8,
      snippet: ['  void bar() {', '    assertEquals(1, 2);', '  }'],
    },
  });
  const d = cards[0].modules[0].diagnostics[0];
  assert.equal(d.code, 'test-failure');
  assert.equal(d.file, 'src/test/java/pkg/FooTest.java');
  assert.equal(d.snippet.length, 3);
  assert.equal(d.className, 'pkg.FooTest');
  const rep = testFailureReport(d, { count: 1 });
  assert.equal(rep.label, 'FooTest.bar()');
  // line 10 is the third snippet row (start 8 → 8, 9, 10)
  assert.equal(rep.rows[2].num, 10);
  assert.equal(rep.rows[2].error, true);
  assert.equal(rep.rows[1].error, false);
});

test('history seed keeps test-failure snippet for Activity backfill', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('hist-tf', '/w', {
      success: false,
      modules: [
        {
          coord: 'g:core',
          dir: '/w/core',
          success: false,
          exitCode: 4,
          millis: 100,
          steps: [{ name: 'run-tests', status: 'FAIL', phase: 'test' }],
        },
      ],
      diagnostics: [
        {
          severity: 'error',
          dir: '/w/core',
          task: 'run-tests',
          code: 'test-failure',
          message: 'expected: x\nbut was: y',
          module: 'g:core',
          class: 'core.T',
          method: 'm()',
          exceptionClass: 'AssertionFailedError',
          file: 'src/test/java/core/T.java',
          line: 5,
          snippetStart: 3,
          snippet: ['class T {', '  void m() { fail(); }', '}'],
        },
      ],
    }),
  ]);
  const mod = cards[0].modules.find((m) => m.dir === '/w/core');
  assert.ok(mod);
  assert.equal(mod.diagnostics.length, 1);
  assert.equal(mod.diagnostics[0].snippet.length, 3);
  assert.equal(mod.diagnostics[0].file, 'src/test/java/core/T.java');
  const rep = testFailureReport(mod.diagnostics[0], { count: 1 });
  assert.equal(rep.label, 'T.m()');
  assert.equal(rep.exceptionClass, 'AssertionFailedError');
});
