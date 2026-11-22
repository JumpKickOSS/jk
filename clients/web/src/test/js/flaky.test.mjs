// SPDX-License-Identifier: Apache-2.0
// The project page's Flaky tests tab: GET /api/project/flaky becomes one row per class with a cell
// per recent run, and the tab reads its list again when the tab opens or a class is quarantined.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const { QUARANTINE_NOTICE, historyCells, policyLine, issueHref, flakyModel, flakyMethods, flakyComputed } =
  await spa('flaky.js');
const { routeFromHash, buildProjectHash } = await spa('route.js');

const body = {
  dir: '/ws/shop',
  policy: { flakes: 2, runs: 4 },
  file: 'jk-quarantine.toml',
  classes: [
    {
      name: 'com.acme.OrdersTest',
      module: 'orders',
      history: 'FR.R',
      flakes: 2,
      quarantined: { reason: 'failed, then passed on a re-run', since: '2026-11-22', issue: 'https://x/88' },
    },
    { name: 'com.acme.ByHandTest', module: '', history: '', flakes: 0, quarantined: { reason: '', since: '', issue: 'ACME-9' } },
    { name: 'com.acme.CartTest', module: '.', history: 'R', flakes: 1 },
  ],
};

test('each run is a cell, oldest first, padded on the left to the window', () => {
  assert.deepEqual(
    historyCells('.RF', 5).map((c) => c.kind),
    ['none', 'none', 'pass', 'rerun', 'fail'],
  );
  assert.deepEqual(historyCells('', 2).map((c) => c.tip), ['no run', 'no run']);
});

test('the policy line says the threshold, or that jk adds none on its own', () => {
  assert.equal(policyLine({ flakes: 2, runs: 20 }), 'quarantined at 2 re-run passes in 20 runs');
  assert.match(policyLine({ flakes: 0, runs: 20 }), /quarantine = false/);
});

test('an issue links only when it is an http(s) URL', () => {
  assert.equal(issueHref('https://x/88'), 'https://x/88');
  assert.equal(issueHref('ACME-9'), null);
  assert.equal(issueHref('javascript:alert(1)'), null);
});

test('the model keeps the engine order and names what each row needs', () => {
  const m = flakyModel(body);
  assert.equal(m.quarantined, 2);
  assert.equal(m.policy, 'quarantined at 2 re-run passes in 4 runs');
  assert.deepEqual(
    m.rows.map((r) => [r.simple, r.module, r.flakes, r.runs, r.quarantined, r.issueHref]),
    [
      ['OrdersTest', 'orders', 2, 4, true, 'https://x/88'],
      ['ByHandTest', 'every module', 0, 0, true, null],
      ['CartTest', '.', 1, 1, false, null],
    ],
  );
  assert.equal(m.rows[1].issue, 'ACME-9');
  assert.equal(m.rows[2].cells.length, 4);
  assert.equal(flakyModel(null), null);
  assert.equal(flakyModel({ ...body, error: 'jk-quarantine.toml unreadable: x' }).error, 'jk-quarantine.toml unreadable: x');
});

test('the Flaky tests tab has a route of its own', () => {
  const r = routeFromHash('#project/p1/flaky?dir=%2Fws%2Fshop');
  assert.deepEqual([r.view, r.projectId, r.tab, r.dir], ['project', 'p1', 'flaky', '/ws/shop']);
  assert.equal(buildProjectHash({ projectId: 'p1', dir: '/ws/shop', tab: 'flaky' }), '#project/p1/flaky?dir=%2Fws%2Fshop');
  assert.equal(buildProjectHash({ projectId: 'p1', tab: 'runs' }), '#project/p1');
});

test('the tab shows the checkout while it is open, and only the list read for that checkout', () => {
  const vm = { view: 'project', projectTab: 'flaky', projectDir: () => '/ws/shop', flaky: { dir: '/ws/shop', body } };
  vm.flakyFor = flakyComputed.flakyFor.call(vm);
  assert.equal(vm.flakyFor, '/ws/shop');
  assert.equal(flakyComputed.projectFlaky.call(vm).rows.length, 3);
  assert.equal(flakyComputed.projectFlaky.call({ ...vm, flakyFor: '/ws/other' }), null);
  assert.equal(flakyComputed.flakyFor.call({ ...vm, projectTab: 'runs' }), null);
});

test('a quarantine notice reads the open tab again, and nothing else does', () => {
  let reads = 0;
  const vm = { flakyFor: '/ws/shop', loadProjectFlaky: () => reads++ };
  const added = (kind) => ({ change: 'added', notice: { kind } });
  flakyMethods.flakyNoticed.call(vm, added(QUARANTINE_NOTICE));
  flakyMethods.flakyNoticed.call(vm, added('change'));
  flakyMethods.flakyNoticed.call(vm, { change: 'seen' });
  flakyMethods.flakyNoticed.call({ ...vm, flakyFor: null }, added(QUARANTINE_NOTICE));
  assert.equal(reads, 1);
});
