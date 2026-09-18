// SPDX-License-Identifier: Apache-2.0
// Headless tests for the dashboard's event-folding layer (docs/contributors/webclient.md).
// Run by WebClientJsTest via `node --test`. Bindings live in fold-harness.mjs.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  etaTotalMillis,
  finish,
  fmtBytes,
  foldEvent,
  historyRecord,
  ioLines,
  isTestFailureDiag,
  liveStepDetail,
  MAX_CARDS,
  MAX_DIAGNOSTICS,
  MAX_OUTPUT_LINES,
  MAX_TEST_FAILURE_DIAGNOSTICS,
  moduleSummary,
  normalizeDiagnostic,
  outcomeOf,
  queued,
  seedFromHistory,
  start,
  startAnchor,
  stepTimingLabel,
  testFailureReport,
  weightDenominator,
  weightNumerator,
} from './fold-harness.mjs';

test('request-start ignores format and lock (Activity is build-like only)', () => {
  const cards = [];
  foldEvent(cards, { type: 'request-start', data: { jid: 1, kind: 'format', dir: '/w' } });
  foldEvent(cards, { type: 'request-start', data: { jid: 2, kind: 'lock', dir: '/w' } });
  foldEvent(cards, start(3, '/w'));
  assert.equal(cards.length, 1);
  assert.equal(cards[0].id, 3);
  assert.equal(cards[0].kind, 'build');
});

test('request-start of a jk mvn run opens a card that names the tool', () => {
  const cards = [];
  foldEvent(cards, { type: 'request-start', data: { jid: 4, kind: 'mvn', dir: '/w' } });
  assert.equal(cards.length, 1);
  assert.equal(cards[0].kind, 'mvn');
});

test('request-queued opens a queued card that request-start turns live', () => {
  const cards = [];
  foldEvent(cards, queued(7, '/w/q', { ahead: 2 }));
  assert.equal(cards.length, 1);
  assert.equal(cards[0].state, 'queued');
  assert.equal(cards[0].ahead, 2);
  assert.equal(outcomeOf(cards[0]), 'queued');
  foldEvent(cards, start(7, '/w/q', { startedAt: 5_000, serverNow: 5_000 }));
  assert.equal(cards.length, 1); // the same card, not a second one
  assert.equal(cards[0].state, 'running');
  assert.equal(cards[0].ahead, null);
  assert.equal(cards[0].startedAt, 5_000);
  assert.equal(outcomeOf(cards[0]), 'running');
});

test('a queued job cancelled before it ran resolves as cancelled', () => {
  const cards = [];
  foldEvent(cards, queued(8, '/w/q'));
  foldEvent(cards, finish(8, { success: false, cancelled: true, millis: 0 }));
  assert.equal(outcomeOf(cards[0]), 'cancelled');
});

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
  foldEvent(cards, { type: 'module-start', data: { jid: 1, dir: '/w/a' } });
  foldEvent(cards, { type: 'module-finish', data: { jid: 1, dir: '/w/a', success: true, millis: 10 } });
  foldEvent(cards, { type: 'module-finish', data: { jid: 1, dir: '/w/b', success: false, millis: 5 } });
  foldEvent(cards, finish(1, { millis: 20 })); // no success field — the socket-request shape
  assert.equal(outcomeOf(cards[0]), 'failed'); // any failed module fails the card
});

test('all-success module rows derive success; no rows stay neutral', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'buildplan-finish', data: { jid: 1, dir: '/w', success: true } });
  foldEvent(cards, finish(1, {}));
  assert.equal(outcomeOf(cards[0]), 'success');

  foldEvent(cards, start(2, '/x'));
  foldEvent(cards, finish(2, {}));
  assert.equal(outcomeOf(cards[0]), 'finished'); // nothing to derive from
});

test('cancelled wins over derived outcomes', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'buildplan-finish', data: { jid: 1, dir: '/w', success: true } });
  foldEvent(cards, finish(1, { cancelled: true }));
  assert.equal(outcomeOf(cards[0]), 'cancelled');
});

test('didWork false marks module checked; summary says checked not built', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'module-finish',
    data: { jid: 1, dir: '/w/a', success: true, millis: 10, didWork: false },
  });
  foldEvent(cards, {
    type: 'module-finish',
    data: { jid: 1, dir: '/w/b', success: true, millis: 12, didWork: false },
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
  foldEvent(cards, { type: 'buildplan-finish', data: { jid: 1, dir: '/w', success: false } });
  assert.equal(cards[0].modules.length, 1);
  assert.equal(cards[0].modules[0].state, 'failed');
});

test('events for unknown request ids and unknown types are ignored', () => {
  const cards = [];
  foldEvent(cards, { type: 'module-start', data: { jid: 99, dir: '/w' } });
  foldEvent(cards, { type: 'plan-module', data: { jid: 99 } });
  assert.equal(cards.length, 0);
});

test('workspace-progress sets request-level aggregate percent', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'workspace-progress',
    data: { jid: 1, dir: '/w', numerator: 150, denominator: 200, progress: 75, phase: 'execute' },
  });
  assert.equal(cards[0].progressPercent, 75);
  assert.equal(cards[0].progressNum, 150);
  assert.equal(cards[0].progressDen, 200);
});

test('rehydrate seeds the countdown from current remaining, not original R0', () => {
  const cards = [];
  foldEvent(cards, start(7, '/w'));
  // Late join: the rehydrated snapshot carries the run's original R0 AND current remaining.
  foldEvent(cards, {
    type: 'workspace-progress',
    at: 10_000,
    data: { jid: 7, dir: '/w', numerator: 800, denominator: 1000, R0: 180_000, remainingMs: 60_000 },
  });
  assert.equal(cards[0].r0Ms, 60_000);
  assert.equal(cards[0].r0At, 10_000);
  assert.equal(cards[0].residualRemainingMs, 60_000);
  assert.equal(cards[0].residualAt, 10_000);
  // R0 seed freezes once; residual re-anchors mid-run for countdown + bar.
  foldEvent(cards, {
    type: 'workspace-progress',
    at: 20_000,
    data: { jid: 7, dir: '/w', numerator: 900, denominator: 1000, R0: 180_000, remainingMs: 30_000 },
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
    data: { jid: 8, dir: '/w', numerator: 0, denominator: 1000, R0: 90_000, remainingMs: 90_000 },
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
  foldEvent(cards, { type: 'task-start', data: { jid: 1, dir: '/w/a', task: 'compile', stage: 'compile' } });
  foldEvent(cards, { type: 'task-start', data: { jid: 1, dir: '/w/b', task: 'compile', stage: 'compile' } });
  foldEvent(cards, { type: 'task-finish', data: { jid: 1, dir: '/w/a', task: 'compile', stage: 'compile', status: 'SUCCESS' } });
  foldEvent(cards, { type: 'task-start', data: { jid: 1, dir: '/w/a', task: 'test', stage: 'test' } });
  foldEvent(cards, { type: 'task-finish', data: { jid: 1, dir: '/w/a', task: 'test', stage: 'test', status: 'FAIL' } });
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

test('single-plan step events (empty dir) become one module with a chain', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  foldEvent(cards, { type: 'task-start', data: { jid: 1, dir: '', task: 'compile-java', stage: 'compile' } });
  foldEvent(cards, { type: 'task-finish', data: { jid: 1, dir: '', task: 'compile-java', stage: 'compile', status: 'SUCCESS' } });
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
  foldEvent(cards, { type: 'task-start', data: { jid: 1, dir: '', task: 'lock' } });
  assert.equal(cards[0].modules[0].steps[0].phase, ''); // default, never undefined
});

test('finished live cards reconcile by (dir, buildNumber) despite clock skew', async () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w', { buildNumber: 6 }), at: 100_000 });
  foldEvent(cards, { ...finish(1, { success: true, millis: 400 }), at: 100_400 });
  // The journal record carries ENGINE time — 10s of clock skew vs the browser receipt stamps.
  seedFromHistory(cards, [
    { id: 'r6', dir: '/w', buildNumber: 6, kind: 'build', finishedAt: 110_400, startedAt: 110_000,
      success: true, millis: 400, modules: [], tasks: [], diagnostics: [] },
  ]);
  assert.equal(cards.length, 1); // no duplicate h:r6 card
  assert.equal(cards[0].historyId, 'r6');
});

test('a stale running stub does not flip a finished live card back to running', async () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w', { buildNumber: 6 }), at: 100_000 });
  foldEvent(cards, { ...finish(1, { success: true, millis: 400 }), at: 100_400 });
  // Reconcile raced the journal write: the record still says running.
  seedFromHistory(cards, [
    { id: 'r6', dir: '/w', buildNumber: 6, kind: 'build', running: true, startedAt: 110_000,
      modules: [], tasks: [], diagnostics: [] },
  ]);
  const live = cards.find((c) => c.id === 1);
  assert.equal(live.state, 'finished'); // untouched by the stale stub
  assert.equal(live.historyId, undefined);
  assert.equal(cards.length, 1); // and the stub is not seeded as a phantom running row
});

test('history backfill maps per-module steps; single-project synthesizes one module', async () => {
  // workspace record: modules carry their own steps, and each diagnostic attaches to its module dir
  const ws = [];
  seedFromHistory(ws, [{
    id: 'w1', kind: 'build', dir: '/w', coord: 'g:w', finishedAt: 5000, success: false,
    modules: [
      { coord: 'g:core', dir: '/w/core', success: true, millis: 100, tasks: [{ name: 'compile', status: 'SUCCESS' }] },
      { coord: 'g:api', dir: '/w/api', success: false, millis: 90, tasks: [{ name: 'test', status: 'FAIL' }] },
    ],
    tasks: [],
    diagnostics: [
      { severity: 'error', dir: '/w/api', task: 'test', message: 'boom', test: 'it()', exceptionClass: '' },
      { severity: 'warning', dir: '/w/core', task: 'lint', message: 'unused import' },
    ],
  }]);
  assert.equal(ws[0].modules.length, 2);
  const wcore = ws[0].modules.find((m) => m.dir === '/w/core');
  const wapi = ws[0].modules.find((m) => m.dir === '/w/api');
  assert.deepEqual(wcore.steps.map((p) => p.name + ':' + p.state), ['compile:success']);
  assert.equal(wcore.diagnostics.length, 0); // the warning is dropped, not shown as failure output
  assert.equal(wapi.diagnostics.length, 1);
  assert.equal(wapi.diagnostics[0].message, 'boom');
  // single-project record: no modules, tasks at top level → synthesize one module owning the errors
  const sp = [];
  seedFromHistory(sp, [{
    id: 's1', kind: 'build', dir: '/p', coord: 'g:p', finishedAt: 6000, success: false,
    modules: [], tasks: [{ name: 'compile-java', status: 'FAIL' }],
    diagnostics: [{ severity: 'error', dir: '', task: 'compile-java', message: 'cannot find symbol' }],
  }]);
  assert.equal(sp[0].modules.length, 1);
  assert.deepEqual(sp[0].modules[0].steps.map((p) => p.name + ':' + p.state), ['compile-java:failed']);
  assert.equal(sp[0].modules[0].diagnostics.length, 1);
  assert.equal(sp[0].modules[0].diagnostics[0].message, 'cannot find symbol');
});

test('history seeding reads the journal vocabulary: tasks / stage / task / testClass / stack', () => {
  const cards = [];
  seedFromHistory(cards, [historyRecord('h-vocab', '/w', {
    success: false,
    modules: [{
      coord: 'g:core', dir: '/w/core', finished: true, success: false, millis: 90,
      tasks: [{ name: 'run-tests', stage: 'test', status: 'FAIL', millis: 90 }],
    }],
    diagnostics: [{
      severity: 'error', dir: '/w/core', task: 'run-tests', code: 'test-failure',
      message: 'expected 1 but was 2', testClass: 'core.T', method: 'adds()',
      stack: 'at core.T.adds(T.java:3)',
    }],
  })]);
  const mod = cards[0].modules[0];
  assert.deepEqual(mod.steps.map((s) => s.name + '/' + s.phase + '/' + s.state), ['run-tests/test/failed']);
  assert.equal(mod.state, 'failed');
  assert.equal(mod.diagnostics[0].step, 'run-tests');
  assert.equal(mod.diagnostics[0].className, 'core.T');
  assert.equal(mod.diagnostics[0].stack, 'at core.T.adds(T.java:3)');
});

test('one name per journal field: the retired spellings are not read back', () => {
  // The engine writes tasks/stage/task/testClass/stack and nothing else, so the fold keeps exactly
  // one reader per journal field. A record spelled the other way yields nothing rather than being
  // tolerated — re-adding a fallback in fold.js turns this red.
  const cards = [];
  seedFromHistory(cards, [historyRecord('h-retired', '/w', {
    success: false,
    modules: [{
      coord: 'g:core', dir: '/w/core', finished: true, success: false, millis: 90,
      steps: [{ name: 'run-tests', group: 'test', phase: 'test', status: 'FAIL', millis: 90 }],
    }],
    diagnostics: [{
      severity: 'error', dir: '/w/core', step: 'run-tests', code: 'test-failure',
      message: 'expected 1 but was 2', className: 'core.T',
      throwable: { stack: 'at core.T.adds(T.java:3)' },
    }],
  })]);
  const mod = cards[0].modules[0];
  assert.deepEqual(mod.steps, []);
  assert.equal(mod.diagnostics[0].step, '');
  assert.equal(mod.diagnostics[0].className, '');
  assert.equal(mod.diagnostics[0].stack, '');
});

test('history seeding keeps a FAILED-step module failed inside a cancelled record', async () => {
  // module A fails compile (FAIL step journaled), the rest of the workspace is
  // cancelled → rec.cancelled=true. Live painted A failed; the reload seed graying A out to
  // 'cancelled' desynced the two and dropped A from the failure details. FAIL steps win, same
  // precedence as outcomeOf.
  const cards = [];
  seedFromHistory(cards, [{
    id: 'c1', kind: 'build', dir: '/w', coord: 'g:w', finishedAt: 5000, success: false,
    cancelled: true,
    modules: [
      { coord: 'g:a', dir: '/w/a', finished: true, success: false, millis: 90,
        tasks: [{ name: 'compile-java', status: 'FAIL' }] },
      { coord: 'g:b', dir: '/w/b', finished: true, success: false, cancelled: true, millis: 10,
        tasks: [{ name: 'compile-java', status: 'CANCELLED' }] },
    ],
    tasks: [], diagnostics: [],
  }]);
  const a = cards[0].modules.find((m) => m.dir === '/w/a');
  const b = cards[0].modules.find((m) => m.dir === '/w/b');
  assert.equal(a.state, 'failed');
  assert.equal(b.state, 'cancelled');
});

test('workspace history replay applies the per-kind diagnostic ceilings', async () => {
  // the single-project path was bounded but the workspace path streamed a
  // pathological record's diagnostics into the card unbounded.
  const diagnostics = [];
  for (let i = 0; i < MAX_TEST_FAILURE_DIAGNOSTICS + 40; i++) {
    diagnostics.push({
      severity: 'error', dir: '/w/api', task: 'test', code: 'test-failure', message: 'assert ' + i,
      test: 'case' + i + '()', exceptionClass: 'org.opentest4j.AssertionFailedError',
    });
  }
  for (let i = 0; i < MAX_DIAGNOSTICS + 5; i++) {
    diagnostics.push({ severity: 'error', dir: '/w/api', task: 'compile-java', message: 'err ' + i });
  }
  const ws = [];
  seedFromHistory(ws, [{
    id: 'w2', kind: 'build', dir: '/w', coord: 'g:w', finishedAt: 7000, success: false,
    modules: [
      { coord: 'g:api', dir: '/w/api', success: false, millis: 90, tasks: [{ name: 'test', status: 'FAIL' }] },
    ],
    tasks: [],
    diagnostics,
  }]);
  const api = ws[0].modules.find((m) => m.dir === '/w/api');
  assert.equal(api.diagnostics.length, MAX_TEST_FAILURE_DIAGNOSTICS + MAX_DIAGNOSTICS);
});

test('output keeps a bounded tail and clears on finish', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  for (let i = 1; i <= MAX_OUTPUT_LINES + 5; i++) {
    foldEvent(cards, { type: 'output', data: { jid: 1, dir: '/w/m', task: 'test', line: 'line ' + i } });
  }
  assert.equal(cards[0].output.length, MAX_OUTPUT_LINES);
  assert.equal(cards[0].output.at(-1).line, 'line ' + (MAX_OUTPUT_LINES + 5));
  foldEvent(cards, finish(1, { success: true }));
  assert.equal(cards[0].output.length, 0); // the console tail is an in-flight affordance
});

test('diagnostics attach to their module by dir, survive finish, and are capped per module', async () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'error',
    data: { jid: 1, dir: '/w/core', task: 'test', code: 'fail', message: 'expected 3 but was 4',
            test: 'adds()', exceptionClass: 'AssertionFailedError' },
  });
  foldEvent(cards, {
    type: 'error',
    data: { jid: 1, dir: '/w/api', task: 'lock', code: 'resolve', message: 'no versions for com.foo:bar' },
  });
  foldEvent(cards, finish(1, { success: false }));
  const core = cards[0].modules.find((m) => m.dir === '/w/core');
  const api = cards[0].modules.find((m) => m.dir === '/w/api');
  assert.equal(core.diagnostics.length, 1); // NOT cleared on finish, unlike output
  assert.equal(core.diagnostics[0].test, 'adds()');
  assert.equal(api.diagnostics.length, 1);
  assert.equal(api.diagnostics[0].step, 'lock');
  for (let i = 0; i < MAX_DIAGNOSTICS + 5; i++) {
    foldEvent(cards, { type: 'error', data: { jid: 1, dir: '/w/core', task: 'p', message: 'm' + i } });
  }
  assert.equal(core.diagnostics.length, MAX_DIAGNOSTICS); // compile/other still capped
  const startLen = core.diagnostics.length;
  for (let i = 0; i < 15; i++) {
    foldEvent(cards, {
      type: 'error',
      data: {
        jid: 1,
        dir: '/w/core',
        task: 'run-tests',
        code: 'test-failure',
        message: 'fail ' + i,
        testClass: 'T',
        method: 'm' + i + '()',
      },
    });
  }
  assert.equal(core.diagnostics.length, startLen + 15); // test-failure has its own, higher cap
});

test('test-failure diagnostics are bounded by their own ceiling', async () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  for (let i = 0; i < MAX_TEST_FAILURE_DIAGNOSTICS + 40; i++) {
    foldEvent(cards, {
      type: 'error',
      data: {
        jid: 1,
        dir: '/w/core',
        task: 'run-tests',
        code: 'test-failure',
        message: 'fail ' + i,
        testClass: 'T',
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
  foldEvent(cards, { type: 'error', data: { jid: 1, dir: '/w/core', task: 'p', message: 'other' } });
  assert.equal(core.diagnostics.length, MAX_TEST_FAILURE_DIAGNOSTICS + 1);
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
  assert.equal(cards[0].id, 'h:20260101T000000000-run1'); // no live jid yet
});

test('seedFromHistory uses the live jid when history is enriched', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('20260101T000000000-run1', '/w/a', {
      finishedAt: 0,
      millis: 0,
      running: true,
      buildNumber: 27,
      jid: 42,
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
      jid: 99,
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
  foldEvent(cards, { type: 'request-start', data: { jid: 7, kind: 'build', dir: '/w', buildNumber: 3 }, at: 50_000 });
  assert.equal(cards[0].startedAt, 50_000);
  seedFromHistory(cards, [
    historyRecord('r', '/w', {
      finishedAt: 0,
      millis: 0,
      running: true,
      buildNumber: 3,
      jid: 7,
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
    data: { jid: 3, kind: 'build', dir: '/w', buildNumber: 1, startedAt: 9_000 },
    at: 99_000,
  });
  assert.equal(cards[0].startedAt, 9_000);
  // Second rehydrate must not clobber the earlier engine start with a later receipt.
  foldEvent(cards, {
    type: 'request-start',
    data: { jid: 3, kind: 'build', dir: '/w', buildNumber: 1, startedAt: 9_000 },
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
      jid: 11,
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
      jid: 11,
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

test('pre-execute eta re-seed replaces a provisional R0; execute freezes it', () => {
  const cards = [];
  foldEvent(cards, { type: 'request-start', data: { jid: 41, kind: 'build', dir: '/w' }, at: 1000 });
  // Coarse lock+prior figure during lock contention.
  foldEvent(cards, { type: 'eta', data: { jid: 41, millis: 90_000 }, at: 1100 });
  assert.equal(cards[0].r0Ms, 90_000);
  // Post-forecast refined seed, still pre-execute: replaces (CLI parity).
  foldEvent(cards, { type: 'eta', data: { jid: 41, millis: 30_000 }, at: 2000 });
  assert.equal(cards[0].r0Ms, 30_000);
  assert.equal(cards[0].residualRemainingMs, 30_000);
  // Execute begins (module work folds) — a later eta no longer rewrites R0.
  foldEvent(cards, {
    type: 'task-start',
    data: { jid: 41, dir: '/w/app', task: 'compile-java', stage: 'compile' },
    at: 3000,
  });
  foldEvent(cards, { type: 'eta', data: { jid: 41, millis: 70_000 }, at: 4000 });
  assert.equal(cards[0].r0Ms, 30_000);
});

test('run-snapshot carries finished/didWork/historyId and the SPA stops guessing', () => {
  const cards = [];
  foldEvent(cards, {
    type: 'run-snapshot',
    data: {
      jid: 31,
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

test('serverNow re-anchors engine startedAt to the client epoch under skew', () => {
  const cards = [];
  // Engine clock runs 30s AHEAD of the browser: engine says the run started 10s ago.
  const clientReceipt = 100_000;
  const engineNow = 130_000;
  const engineStart = engineNow - 10_000;
  foldEvent(cards, {
    type: 'run-snapshot',
    data: {
      jid: 21,
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
    data: { jid: 21, kind: 'build', dir: '/w', startedAt: engineStart, serverNow: engineNow + 5_000 },
    at: clientReceipt + 5_000,
  });
  assert.equal(card.startedAtClient, clientReceipt - 10_000);
});

test('stale run-snapshot never resurrects a finished card', () => {
  const cards = [];
  foldEvent(cards, { type: 'request-start', data: { jid: 9, kind: 'build', dir: '/w' }, at: 1000 });
  foldEvent(cards, {
    type: 'request-finish',
    data: { jid: 9, dir: '/w', success: true, millis: 4200 },
    at: 5000,
  });
  // A snapshot captured while the run was still live lands after the finish frame.
  foldEvent(cards, {
    type: 'run-snapshot',
    data: {
      jid: 9,
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

test('seedFromHistory finishes a running card when the record says the run is over', () => {
  const cards = [];
  foldEvent(cards, {
    type: 'request-start',
    data: { jid: 12, kind: 'build', dir: '/w/a', buildNumber: 31 },
    at: 1000,
  });
  foldEvent(cards, {
    type: 'task-start',
    data: { jid: 12, dir: '/w/a', task: 'run-tests', stage: 'test' },
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

test('reconnect run-snapshot preserves live diagnostics, failed state, and checked modules', () => {
  const cards = [];
  foldEvent(cards, { type: 'request-start', data: { jid: 7, kind: 'build', dir: '/w' }, at: 1000 });
  // Live stream reports a checked module, then a module-level failure with diagnostics.
  foldEvent(cards, {
    type: 'module-finish',
    data: { jid: 7, dir: '/w/lib', success: true, didWork: false, millis: 12 },
    at: 1500,
  });
  foldEvent(cards, {
    type: 'error',
    data: { jid: 7, dir: '/w/app', task: 'run-tests', code: 'test-failure', message: 'FooTest.bar failed' },
    at: 2000,
  });
  foldEvent(cards, {
    type: 'module-finish',
    data: { jid: 7, dir: '/w/app', success: false, millis: 900 },
    at: 2100,
  });
  // EventSource reconnects: the new subscription's snapshot has chains but no diagnostics/didWork.
  foldEvent(cards, {
    type: 'run-snapshot',
    data: {
      jid: 7,
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
  // Hard refresh while a build is streaming: journal seeds h:… then SSE events use the numeric jid.
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
    data: { jid: 99, dir: '/w/a', numerator: 50, denominator: 100, progress: 50 },
  });
  assert.equal(cards[0].id, 99); // rebind
  assert.equal(cards[0].progressPercent, 50);

  foldEvent(cards, {
    type: 'task-start',
    data: { jid: 99, dir: '/w/a', task: 'compile-java', stage: 'compile' },
  });
  assert.equal(cards[0].modules[0].steps[0].name, 'compile-java');
  assert.equal(cards[0].modules[0].steps[0].state, 'running');

  foldEvent(cards, finish(99, { success: true, millis: 8000 }));
  assert.equal(outcomeOf(cards[0]), 'success');
  assert.equal(cards[0].millis, 8000);
});

test('request-start rehydrate is idempotent when card already has its jid', () => {
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
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'plan', data: { jid: 1, weight: 300 } });
  foldEvent(cards, { type: 'progress', data: { jid: 1, dir: '/w/a', numerator: 50, denominator: 100 } });
  foldEvent(cards, { type: 'progress', data: { jid: 1, dir: '/w/b', numerator: 20, denominator: 100 } });
  assert.equal(weightNumerator(cards[0]), 70);
  // denominator = max(planWeight 300, sum of module dens 200) = 300 — stable, no backward jump
  assert.equal(weightDenominator(cards[0]), 300);
});

test('weight denominator falls back to summed module dens when no plan (single build)', async () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'progress', data: { jid: 1, dir: '', numerator: 4, denominator: 12 } });
  assert.equal(weightDenominator(cards[0]), 12);
});

test('plan-progress updates latest per dir (no double count on repeat)', async () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'progress', data: { jid: 1, dir: '/w/a', numerator: 10, denominator: 100 } });
  foldEvent(cards, { type: 'progress', data: { jid: 1, dir: '/w/a', numerator: 80, denominator: 100 } });
  assert.equal(weightNumerator(cards[0]), 80); // latest wins, not 10+80
});

test('eta is captured and cleared on finish', () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w'), at: 1000 });
  foldEvent(cards, { ...{ type: 'eta', data: { jid: 1, millis: 5000 } }, at: 1200 });
  assert.equal(cards[0].etaMillis, 5000);
  assert.equal(cards[0].etaAt, 1200);
  foldEvent(cards, finish(1, { success: true }));
  assert.equal(cards[0].etaMillis, null); // countdown stops on finish
});

test('etaTotalMillis anchors remaining work at the emission time, not request-start', async () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w'), at: 1000 });
  // 10s into the run (slow lock/prepare), the engine projects 30s of REMAINING work.
  foldEvent(cards, { ...{ type: 'eta', data: { jid: 1, millis: 30_000 } }, at: 11_000 });
  // Run-wide total = elapsed-at-emission (10s) + remaining (30s), not 30s.
  assert.equal(etaTotalMillis(cards[0]), 40_000);

  // A later re-projection replaces the anchor — still no double count.
  foldEvent(cards, { ...{ type: 'eta', data: { jid: 1, millis: 25_000 } }, at: 21_000 });
  assert.equal(etaTotalMillis(cards[0]), 45_000);

  // Journal-seeded shape (no etaAt): millis is treated as the total.
  assert.equal(etaTotalMillis({ etaMillis: 30_000, etaAt: null, startedAt: 1000 }), 30_000);
  assert.equal(etaTotalMillis({ etaMillis: null }), null);
  assert.equal(etaTotalMillis({ etaMillis: 0 }), null);
});

test('history seed preserves per-step millis for tooltips', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('h1', '/w', {
      tasks: [
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

test('label event stores live tick text on the running step', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'task-start',
    data: { jid: 1, dir: '', task: 'run-tests', phase: 'test' },
  });
  foldEvent(cards, {
    type: 'label',
    data: { jid: 1, dir: '', task: 'run-tests', label: 'g:a :: FooTest.bar()  [w2]' },
  });
  const step = cards[0].modules[0].steps[0];
  assert.equal(step.message, 'g:a :: FooTest.bar()  [w2]');
  assert.equal(liveStepDetail('g:a', cards[0].modules[0].steps), 'FooTest.bar()  [w2]');
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
          tasks: [
            { name: 'compile-java', status: 'SUCCESS', stage: 'compile' },
            { name: 'run-tests', status: 'FAIL', stage: 'test' },
          ],
        },
      ],
      diagnostics: [
        {
          severity: 'error',
          dir: '/w/core',
          task: 'run-tests',
          code: 'test-failure',
          message: 'expected 1 but was 90',
        },
      ],
    }),
  ]);
  assert.equal(outcomeOf(cards[0]), 'failed');
});

test('cancelled module-finish after user cancel does not flip the card to failed', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'module-finish',
    data: { jid: 1, dir: '/w/a', success: true, millis: 10 },
  });
  foldEvent(cards, {
    type: 'module-finish',
    data: { jid: 1, dir: '/w/b', success: false, cancelled: true, millis: 5 },
  });
  foldEvent(cards, finish(1, { success: false, cancelled: true }));
  assert.equal(cards[0].modules.find((m) => m.dir === '/w/b').state, 'cancelled');
  assert.equal(outcomeOf(cards[0]), 'cancelled');
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
          tasks: [
            { name: 'compile-java', status: 'SUCCESS', stage: 'compile' },
            { name: 'run-tests', status: 'CANCELLED', stage: 'test' },
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
    testClass: 'cc.jumpkick.runtime.DogfoodFailureSnippetTest',
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

test('live diagnostic event folds snippet fields onto the module', () => {
  const cards = [];
  foldEvent(cards, start(42, '/w'));
  foldEvent(cards, {
    type: 'error',
    data: {
      jid: 42,
      dir: '/w',
      task: 'run-tests',
      code: 'test-failure',
      message: 'expected: 1\nbut was: 2',
      module: 'g:a',
      testClass: 'pkg.FooTest',
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
          tasks: [{ name: 'run-tests', status: 'FAIL', stage: 'test' }],
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
          testClass: 'core.T',
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

// ---- compiler-failure rich report (CLI CompilerDiagnostic parity) ----
