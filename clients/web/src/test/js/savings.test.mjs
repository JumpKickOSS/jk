// SPDX-License-Identifier: Apache-2.0
// The savings panel: the /api/stats rows, the same objects `jk stats -O jsonl` prints, become one
// tile per figure; the time saved says it is an estimate and how much of it was measured.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const { SAVINGS_DAYS, savingsQuery, savingsRow, savedTime, savingsTiles, receiptTiles, savingsComputed } = await spa('savings.js');
const { fmtDuration } = await spa('format.js');

const all = {
  scope: 'all', since: 0, until: 9, runs: 12, savedMillis: 125_000, estimatedUncachedMillis: 300_000,
  coveredSkips: 3, estimatedSkips: 4, stepsSkipped: 40, suitesSkipped: 2, testClassesNotRerun: 6,
  graphCalls: 9, refactorings: 1, edits: 3, guardViolations: 2, guardStops: 1,
};
const app = { ...all, scope: 'project', projectId: 'p-app', dir: '/ws/app', savedMillis: 100_000 };
const loose = { ...all, scope: 'project', dir: '/tmp/loose', savedMillis: 25_000 };
const rows = [all, app, loose];

test('the request asks for the last seven days, as jk stats does by default', () => {
  assert.equal(SAVINGS_DAYS, 7);
  assert.equal(savingsQuery(10 * 86_400_000), '/api/stats?since=' + 3 * 86_400_000);
  assert.equal(savingsQuery(1000), '/api/stats?since=0');
});

test('a project row is found by its id, else by the checkout of a row with no id', () => {
  assert.equal(savingsRow(rows, 'p-app', '/elsewhere'), app);
  assert.equal(savingsRow(rows, null, '/tmp/loose'), loose);
  assert.equal(savingsRow(rows, 'p-none', '/ws/app'), null, 'a row with an id is not taken by its dir');
  assert.equal(savingsRow(null, 'p-app', '/ws/app'), null);
});

test('the time saved is marked as an estimate with the share that was measured', () => {
  assert.deepEqual(savedTime(all), { value: '~' + fmtDuration(125_000), note: 'estimate · 75% measured' });
  assert.equal(savedTime({ ...all, estimatedSkips: 0 }).note, 'estimate');
});

test('every figure jk stats prints has a tile, in its order', () => {
  const tiles = savingsTiles(all);
  assert.deepEqual(tiles.map((t) => t.key), ['saved', 'steps', 'classes', 'graph', 'refactorings', 'guards']);
  const by = Object.fromEntries(tiles.map((t) => [t.key, t]));
  assert.equal(by.steps.value, 40);
  assert.equal(by.steps.note, '2 test suites');
  assert.equal(by.classes.value, 6);
  assert.equal(by.graph.value, 9);
  assert.equal(by.graph.label, 'code answers');
  assert.equal(by.refactorings.value, '1 · 3');
  assert.equal(by.guards.value, 2);
  assert.equal(by.guards.note, 'in 1 run');
  assert.deepEqual(savingsTiles(null), []);
});

test('the computed rows read the overall row and the open project', () => {
  const vm = { savings: rows, selectedProjectId: 'p-app', projectDir: () => '/ws/app' };
  assert.equal(savingsComputed.savingsAll.call(vm), all);
  assert.equal(savingsComputed.savingsProject.call(vm), app);
  assert.equal(savingsComputed.savingsAll.call({ savings: null }), null);
});

test("an episode's receipt shows the panel's tiles for what it counted, and none when it saved nothing", () => {
  const episode = {
    ...all, scope: 'episode', runs: 3, savedMillis: 0, coveredSkips: 0, estimatedSkips: 0, stepsSkipped: 5,
    suitesSkipped: 1, testClassesNotRerun: 0, graphCalls: 4, refactorings: 0, edits: 0, guardViolations: 0, guardStops: 0,
  };
  assert.deepEqual(receiptTiles(episode).map((t) => t.key), ['steps', 'graph']);
  assert.equal(receiptTiles(episode)[0].note, '1 test suites');
  assert.deepEqual(receiptTiles({ ...episode, edits: 2 }).map((t) => t.key), ['steps', 'graph', 'refactorings']);
  assert.deepEqual(receiptTiles({ ...all }).map((t) => t.key), savingsTiles(all).map((t) => t.key));
  assert.deepEqual(receiptTiles({ scope: 'episode', runs: 1 }), []);
  assert.deepEqual(receiptTiles(null), []);
});
