// SPDX-License-Identifier: Apache-2.0
// A run as one line and one strip of module cells, and the run page's rule that only the failed
// and the slow modules open (strip.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const { cellState, doingLine, doingSegments, moduleCells, moduleFocus, moduleName, SLOW_MIN_MILLIS } = await spa('strip.js');

const step = (name, phase, state, over = {}) => ({ name, phase, state, millis: state === 'running' ? null : 10, message: '', ...over });
const mod = (dir, state, over = {}) => ({ dir, coord: 'dev:' + dir.split('/').pop(), state, millis: 1_000, steps: [], diagnostics: [], lastActivity: 0, ...over });
const card = (modules, over = {}) => ({ id: 1, state: 'running', success: null, cancelled: false, finishedAt: null, modules, ...over });

test('a module is named by its artifact, else its directory', () => {
  assert.equal(moduleName({ coord: 'dev.x:service', dir: '/w/backend/service' }), 'service');
  assert.equal(moduleName({ coord: null, dir: '/w/backend/service' }), 'service');
  assert.equal(moduleName({ coord: null, dir: '' }), 'project');
});

test('one cell per module, coloured by state, padded to the workspace with waiting cells', () => {
  const c = card(
    [
      mod('/w/core', 'success', { steps: [step('compile-java', 'compile', 'success')] }),
      mod('/w/service', 'running', { steps: [step('compile-java', 'compile', 'success'), step('test', 'test', 'running')] }),
      mod('/w/web', 'failed', { diagnostics: [{ message: 'x' }] }),
    ],
    { moduleCount: 5 },
  );
  const cells = moduleCells(c);
  assert.deepEqual(cells.map((x) => x.state), ['done', 'running', 'failed', 'waiting', 'waiting']);
  assert.equal(cells[1].tip, 'service · Test', 'hover names the module and its current phase');
  assert.equal(cellState(mod('/w/a', 'success', { diagnostics: [{}] })), 'failed', 'a module with failure output is failed');
});

test('the live line says what the build is doing right now', () => {
  const c = card([
    mod('/w/core', 'success'),
    mod('/w/service', 'running', {
      lastActivity: 5,
      steps: [step('test', 'test', 'running', { message: 'QuoteServiceIT' })],
    }),
    mod('/w/web', 'running', { lastActivity: 2, steps: [step('compile-java', 'compile', 'running')] }),
  ]);
  assert.equal(doingLine(c), 'testing service · QuoteServiceIT (+1 more)');
  assert.equal(doingLine(card([])), 'starting');
  assert.equal(doingLine(card([mod('/w/a', 'success')], { state: 'finished', success: true, finishedAt: 1 })), '', 'a finished run has no live line');
});

test('the live line is coloured in the CLI roles: the class gold, the method purple', () => {
  const c = card([
    mod('/w/service', 'running', {
      lastActivity: 5,
      steps: [step('test', 'test', 'running', { message: 'dev:service :: com.acme.QuoteServiceIT.weekendStay()' })],
    }),
    mod('/w/web', 'running', { lastActivity: 2, steps: [step('compile-java', 'compile', 'running')] }),
  ]);
  const segs = doingSegments(c);
  assert.equal(segs.map((s) => s.text).join(''), doingLine(c), 'the segments spell the plain line');
  assert.equal(doingLine(c), 'testing service · QuoteServiceIT.weekendStay() (+1 more)');
  const cls = (text) => segs.filter((s) => s.text === text).map((s) => s.cls);
  assert.deepEqual(cls('testing '), ['det-mid']);
  assert.deepEqual(cls('service'), ['live-mod']);
  assert.deepEqual(cls(' · '), ['det-dim']);
  assert.deepEqual(cls('QuoteServiceIT'), ['det-type']);
  assert.deepEqual(cls('weekendStay'), ['det-fn']);
  assert.deepEqual(cls('1'), ['det-num'], 'the count of other running modules is a number');
  assert.deepEqual(doingSegments(card([])), [{ text: 'starting', cls: 'det-mid' }]);
  assert.deepEqual(doingSegments(card([mod('/w/a', 'success')], { state: 'finished', success: true, finishedAt: 1 })), []);
});

test('the run page opens only the failed and the slow modules', () => {
  const mods = [
    mod('/w/a', 'success', { millis: 1_000 }),
    mod('/w/b', 'success', { millis: 1_200 }),
    mod('/w/c', 'success', { millis: 900 }),
    mod('/w/slow', 'success', { millis: 40_000 }),
    mod('/w/broken', 'failed', { millis: 2_000, diagnostics: [{ message: 'boom' }] }),
  ];
  const focus = moduleFocus(card(mods, { state: 'finished', success: false, finishedAt: 1 }));
  assert.deepEqual(focus.expanded.map((f) => [f.module.dir, f.reason]), [['/w/broken', 'failed'], ['/w/slow', 'slow']]);
  assert.deepEqual(focus.rest.map((m) => m.dir).sort(), ['/w/a', '/w/b', '/w/c']);
});

test('slow means well above the median and at least the floor; one module is never slow', () => {
  const quick = [mod('/w/a', 'success', { millis: 10 }), mod('/w/b', 'success', { millis: 400 })];
  assert.equal(moduleFocus(card(quick)).expanded.length, 0, `under ${SLOW_MIN_MILLIS} ms nothing is slow`);
  assert.equal(moduleFocus(card([mod('/w/a', 'success', { millis: 90_000 })])).expanded.length, 0);
  assert.equal(moduleFocus(card([mod('/w/a', 'failed')])).expanded.length, 1, 'a one-module run that failed opens');
});

test('a cancelled run opens nothing; its modules stay folded', () => {
  const c = card(
    [mod('/w/a', 'success', { steps: [step('compile', 'compile', 'success')] }), mod('/w/b', 'cancelled')],
    { state: 'finished', cancelled: true, success: false, finishedAt: 1 },
  );
  const focus = moduleFocus(c);
  assert.deepEqual(focus.expanded, []);
  assert.equal(focus.rest.length, 2);
});

const { workerLanes, MAX_LANES } = await spa('strip.js');

test('worker lanes are a fixed set: sticky rows, idle when freed, never more than four', () => {
  const run = (dir, phase, name, message = '') =>
    mod(dir, 'running', { steps: [step(name, phase, 'running', { message })] });
  const c = card([run('/w/rest', 'test', 'test', 'CatalogControllerTest  [w1]'), run('/w/core', 'compile', 'compile-java')], { moduleCount: 9 });
  let lanes = workerLanes(c);
  assert.equal(lanes.length, MAX_LANES, 'seeded from the module count, capped at four');
  assert.equal(MAX_LANES, 4);
  const first = lanes.find((l) => l.module === 'rest');
  assert.equal(first.op, 'Test');
  assert.equal(first.segs.map((s) => s.text).join(''), 'CatalogControllerTest', 'the [wN] tag is dropped');
  assert.equal(lanes.filter((l) => l.idle).length, 2);
  const slotOfRest = lanes.findIndex((l) => l.module === 'rest');
  c.modules[1].state = 'success'; // core finishes; rest keeps its row
  lanes = workerLanes(c);
  assert.equal(lanes.length, MAX_LANES, 'the row count never shrinks');
  assert.equal(lanes.findIndex((l) => l.module === 'rest'), slotOfRest);
  assert.equal(lanes.filter((l) => l.idle).length, 3);
  assert.deepEqual(workerLanes({ ...c, state: 'success', success: true }), [], 'a finished run has no lanes');
});

test('a one-module build reserves one row and grows only to the peak concurrency', () => {
  const c = card([mod('/w/a', 'running', { steps: [step('compile-java', 'compile', 'running')] })]);
  assert.equal(workerLanes(c).length, 1);
  c.modules.push(mod('/w/b', 'running', { steps: [step('test', 'test', 'running')] }));
  assert.equal(workerLanes(c).length, 2);
});

test('the settled top-bar chip carries its outcome icon where the spinner was', async () => {
  const { BuildHead } = await spa('buildhead.js');
  const icon = (state) => BuildHead.computed.icon.call({ state });
  assert.equal(icon('success'), 'check');
  assert.equal(icon('failed'), 'x');
  assert.equal(icon('cancelled'), 'ban');
  assert.equal(icon('running'), '', 'a running chip has the spinner instead');
});
