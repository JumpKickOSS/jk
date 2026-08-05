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
  ioLines,
  fmtBytes,
  detailForDisplay,
  liveStepDetail,
  detailSegments,
  looksLikeJavaMember,
  orderedModules,
  MAX_CARDS,
  MAX_OUTPUT_LINES,
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
  foldEvent(cards, { type: 'pipeline-finish', data: { requestId: 1, dir: '/w', success: true } });
  foldEvent(cards, finish(1, {}));
  assert.equal(outcomeOf(cards[0]), 'success');

  foldEvent(cards, start(2, '/x'));
  foldEvent(cards, finish(2, {}));
  assert.equal(outcomeOf(cards[0]), 'finished'); // nothing to derive from
});

test('cancelled wins over derived outcomes', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'pipeline-finish', data: { requestId: 1, dir: '/w', success: true } });
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

test('pipeline-finish creates a module row when module-start never fired (single-pipeline requests)', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'pipeline-finish', data: { requestId: 1, dir: '/w', success: false } });
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
  foldEvent(cards, { type: 'step-start', data: { requestId: 1, dir: '/w/a', step: 'compile', phase: 'compile' } });
  foldEvent(cards, { type: 'step-start', data: { requestId: 1, dir: '/w/b', step: 'compile', phase: 'compile' } });
  foldEvent(cards, { type: 'step-finish', data: { requestId: 1, dir: '/w/a', step: 'compile', phase: 'compile', status: 'SUCCESS' } });
  foldEvent(cards, { type: 'step-start', data: { requestId: 1, dir: '/w/a', step: 'test', phase: 'test' } });
  foldEvent(cards, { type: 'step-finish', data: { requestId: 1, dir: '/w/a', step: 'test', phase: 'test', status: 'FAIL' } });
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
  foldEvent(cards, { type: 'step-start', data: { requestId: 1, dir: '/w/b', step: 'compile', phase: 'compile' }, at: 400 });
  foldEvent(cards, { type: 'module-start', data: { requestId: 1, dir: '/w/c' }, at: 350 });
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/c', success: false, millis: 5 }, at: 360 });
  // Later tick on b → b is the most recently active runner.
  foldEvent(cards, {
    type: 'label',
    data: { requestId: 1, dir: '/w/b', step: 'compile', label: 'compiling' },
    at: 500,
  });

  const ordered = orderedModules(cards[0].modules);
  assert.deepEqual(
    ordered.map((m) => m.dir + ':' + m.state),
    ['/w/b:running', '/w/c:failed', '/w/a:success'],
  );
});

test('single-pipeline step events (empty dir) become one module with a chain', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  foldEvent(cards, { type: 'step-start', data: { requestId: 1, dir: '', step: 'compile-java', phase: 'compile' } });
  foldEvent(cards, { type: 'step-finish', data: { requestId: 1, dir: '', step: 'compile-java', phase: 'compile', status: 'SUCCESS' } });
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
  foldEvent(cards, { type: 'step-start', data: { requestId: 1, dir: '', step: 'lock' } });
  assert.equal(cards[0].modules[0].steps[0].phase, ''); // default, never undefined
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

test('output keeps a bounded tail and clears on finish', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  for (let i = 1; i <= MAX_OUTPUT_LINES + 5; i++) {
    foldEvent(cards, { type: 'output', data: { requestId: 1, dir: '/w/m', step: 'test', line: 'line ' + i } });
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
    data: { requestId: 1, dir: '/w/core', step: 'test', code: 'fail', message: 'expected 3 but was 4',
            test: 'adds()', exceptionClass: 'AssertionFailedError' },
  });
  foldEvent(cards, {
    type: 'diagnostic',
    data: { requestId: 1, dir: '/w/api', step: 'lock', code: 'resolve', message: 'no versions for com.foo:bar' },
  });
  foldEvent(cards, finish(1, { success: false }));
  const core = cards[0].modules.find((m) => m.dir === '/w/core');
  const api = cards[0].modules.find((m) => m.dir === '/w/api');
  assert.equal(core.diagnostics.length, 1); // NOT cleared on finish, unlike output
  assert.equal(core.diagnostics[0].test, 'adds()');
  assert.equal(api.diagnostics.length, 1);
  assert.equal(api.diagnostics[0].step, 'lock');
  for (let i = 0; i < MAX_DIAGNOSTICS + 5; i++) {
    foldEvent(cards, { type: 'diagnostic', data: { requestId: 1, dir: '/w/core', step: 'p', message: 'm' + i } });
  }
  assert.equal(core.diagnostics.length, MAX_DIAGNOSTICS); // capped per module
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
    type: 'step-start',
    data: { requestId: 99, dir: '/w/a', step: 'compile-java', phase: 'compile' },
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
  foldEvent(cards, { type: 'pipeline-progress', data: { requestId: 1, dir: '/w/a', numerator: 50, denominator: 100 } });
  foldEvent(cards, { type: 'pipeline-progress', data: { requestId: 1, dir: '/w/b', numerator: 20, denominator: 100 } });
  assert.equal(weightNumerator(cards[0]), 70);
  // denominator = max(planWeight 300, sum of module dens 200) = 300 — stable, no backward jump
  assert.equal(weightDenominator(cards[0]), 300);
});

test('weight denominator falls back to summed module dens when no plan (single build)', async () => {
  const { weightDenominator } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'pipeline-progress', data: { requestId: 1, dir: '', numerator: 4, denominator: 12 } });
  assert.equal(weightDenominator(cards[0]), 12);
});

test('pipeline-progress updates latest per dir (no double count on repeat)', async () => {
  const { weightNumerator } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'pipeline-progress', data: { requestId: 1, dir: '/w/a', numerator: 10, denominator: 100 } });
  foldEvent(cards, { type: 'pipeline-progress', data: { requestId: 1, dir: '/w/a', numerator: 80, denominator: 100 } });
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

test('phaseChainOf collapses steps into coarse phase nodes in encounter order', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  const step = (name, phase, status) => {
    foldEvent(cards, { type: 'step-start', data: { requestId: 1, dir: '', step: name, phase } });
    if (status) foldEvent(cards, { type: 'step-finish', data: { requestId: 1, dir: '', step: name, phase, status } });
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
    type: 'step-start',
    data: { requestId: 1, dir: '', step: 'run-tests', phase: 'test' },
  });
  foldEvent(cards, {
    type: 'label',
    data: { requestId: 1, dir: '', step: 'run-tests', label: 'g:a :: FooTest.bar()  [w2]' },
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
