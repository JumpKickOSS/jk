// SPDX-License-Identifier: Apache-2.0
// Headless tests for the dashboard outcome / phase-chain layer. Run by WebClientJsTest.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  finish,
  fmtDuration,
  foldEvent,
  moduleSummary,
  orderedModules,
  phaseChainOf,
  start,
  stepTimingLabel,
} from './fold-harness.mjs';

test('orderedModules puts running first (newest activity), finished last', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  // a starts first, finishes; b starts later and stays running; c fails.
  foldEvent(cards, { type: 'module-start', data: { jid: 1, dir: '/w/a' }, at: 100 });
  foldEvent(cards, { type: 'module-finish', data: { jid: 1, dir: '/w/a', success: true, millis: 10 }, at: 200 });
  foldEvent(cards, { type: 'module-start', data: { jid: 1, dir: '/w/b' }, at: 300 });
  foldEvent(cards, { type: 'task-start', data: { jid: 1, dir: '/w/b', task: 'compile', stage: 'compile' }, at: 400 });
  foldEvent(cards, { type: 'module-start', data: { jid: 1, dir: '/w/c' }, at: 350 });
  foldEvent(cards, { type: 'module-finish', data: { jid: 1, dir: '/w/c', success: false, millis: 5 }, at: 360 });
  // Later tick on b → b is the most recently active runner.
  foldEvent(cards, {
    type: 'label',
    data: { jid: 1, dir: '/w/b', task: 'compile', label: 'compiling' },
    at: 500,
  });

  const ordered = orderedModules(cards[0].modules);
  assert.deepEqual(
    ordered.map((m) => m.dir + ':' + m.state),
    ['/w/b:running', '/w/c:failed', '/w/a:success'],
  );
});

test('module summary counts modules and failures', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  assert.equal(moduleSummary(cards[0]), '');
  foldEvent(cards, { type: 'module-finish', data: { jid: 1, dir: '/w/a', success: true, millis: 5 } });
  assert.equal(moduleSummary(cards[0]), 'built 1 module');
  foldEvent(cards, { type: 'module-finish', data: { jid: 1, dir: '/w/b', success: true, millis: 5 } });
  assert.equal(moduleSummary(cards[0]), 'built 2 modules');
  foldEvent(cards, { type: 'module-finish', data: { jid: 1, dir: '/w/c', success: false, millis: 5 } });
  assert.equal(moduleSummary(cards[0]), '3 modules · 1 failed');
});

test('phaseChainOf collapses steps into coarse phase nodes in encounter order', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  const step = (name, phase, status) => {
    foldEvent(cards, { type: 'task-start', data: { jid: 1, dir: '', task: name, stage: phase } });
    if (status) foldEvent(cards, { type: 'task-finish', data: { jid: 1, dir: '', task: name, stage: phase, status } });
  };
  step('resolve-deps', 'resolve', 'SUCCESS');
  step('compile-java', 'compile', 'SUCCESS');
  step('compile-kotlin', 'compile', 'SUCCESS');
  step('run-tests', 'test', 'SUCCESS');
  const chain = phaseChainOf(cards[0].modules[0]);
  assert.deepEqual(chain.map((p) => p.label), ['Compile', 'Test']); // Resolve omitted when it succeeded
  assert.deepEqual(chain.map((p) => p.state), ['success', 'success']);
  assert.deepEqual(chain[0].steps.map((s) => s.name), ['compile-java', 'compile-kotlin']); // Compile collapses both
});

test('phaseChainOf keeps Resolve only when a resolve step failed', () => {
  const ok = phaseChainOf({
    steps: [
      { name: 'resolve-deps', phase: 'resolve', state: 'success' },
      { name: 'ksp', phase: 'generate', state: 'success' },
      { name: 'compile-java', phase: 'compile', state: 'success' },
    ],
  });
  assert.deepEqual(ok.map((p) => p.label), ['Generate', 'Compile']);

  const failed = phaseChainOf({
    steps: [
      { name: 'resolve-deps', phase: 'resolve', state: 'failed' },
      { name: 'compile-java', phase: 'compile', state: 'success' },
    ],
  });
  assert.deepEqual(failed.map((p) => p.label), ['Resolve', 'Compile']);
  assert.equal(failed[0].state, 'failed');

  // a RUNNING resolve is the only live indicator during cold-cache resolution —
  // it must stay visible; likewise a cancelled one explains where the run stopped.
  const running = phaseChainOf({
    steps: [{ name: 'resolve-deps', phase: 'resolve', state: 'running' }],
  });
  assert.deepEqual(running.map((p) => p.label), ['Resolve']);
  assert.equal(running[0].state, 'running');

  const cancelled = phaseChainOf({
    steps: [{ name: 'resolve-deps', phase: 'resolve', state: 'cancelled' }],
  });
  assert.deepEqual(cancelled.map((p) => p.label), ['Resolve']);
});

test('phaseChainOf paints Compile skipped when compile-java is skipped and only copy-resources succeeded', () => {
  // Build #110 jk-cli: compile-java SKIPPED@2ms, copy-resources SUCCESS@2ms — not a javac run.
  const chain = phaseChainOf({
    steps: [
      { name: 'compile-java', phase: 'compile', state: 'skipped', millis: 2 },
      { name: 'build-logic-after-compile', phase: 'compile', state: 'skipped', millis: 0 },
      { name: 'write-stamp', phase: 'compile', state: 'skipped', millis: 0 },
      { name: 'copy-resources', phase: 'compile', state: 'success', millis: 2 },
    ],
  });
  assert.equal(chain.length, 1);
  assert.equal(chain[0].label, 'Compile');
  assert.equal(chain[0].state, 'skipped');
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
  foldEvent(cards, { type: 'request-start', data: { jid: 1, kind: 'build', dir: '/w' } });
  foldEvent(cards, {
    type: 'task-finish',
    data: { jid: 1, dir: '', task: 'write-stamp', stage: 'compile', status: 'SUCCESS', millis: 0 },
  });
  assert.equal(cards[0].modules[0].steps[0].state, 'skipped');
});

test('task-finish stores engine millis on the step row', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  foldEvent(cards, {
    type: 'task-start',
    data: { jid: 1, dir: '', task: 'ensure-jdk', stage: 'resolve' },
    at: 1000,
  });
  foldEvent(cards, {
    type: 'task-finish',
    data: { jid: 1, dir: '', task: 'ensure-jdk', stage: 'resolve', status: 'SUCCESS', millis: 360 },
    at: 1500,
  });
  assert.equal(cards[0].modules[0].steps[0].millis, 360);
});

test('task-finish falls back to receipt delta when millis is absent', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  foldEvent(cards, {
    type: 'task-start',
    data: { jid: 1, dir: '', task: 'compile-tests', stage: 'compile' },
    at: 1000,
  });
  foldEvent(cards, {
    type: 'task-finish',
    data: { jid: 1, dir: '', task: 'compile-tests', stage: 'compile', status: 'SUCCESS' },
    at: 1212,
  });
  assert.equal(cards[0].modules[0].steps[0].millis, 212);
});

test('the compact duration face is what the step chain shows', () => {
  const compact = (ms) => fmtDuration(ms, { compact: true });
  assert.equal(compact(null), '');
  assert.equal(compact(0), '0ms');
  assert.equal(compact(360), '360ms');
  assert.equal(compact(1200), '1.2s');
  assert.equal(compact(12_000), '12s');
  assert.equal(compact(65_000), '1m 05s');
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
