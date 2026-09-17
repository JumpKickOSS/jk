// SPDX-License-Identifier: Apache-2.0
// The per-iteration strip: a record's `delta` (what changed since the run before from the same
// origin) becomes chips with counts first, a bounded row list behind each, and rides the card.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const { deltaChip, deltaSummary, rowLines, signedDuration, RunDelta } = await spa('delta.js');
const { historyCard, seedFromHistory } = await spa('fold.js');

const rows = (shown, count = shown.length) => ({ count, shown });

const delta = {
  previousBuildNumber: 12,
  previousSuccess: false,
  previousMillis: 8400,
  files: rows(['src/main/java/Foo.java', 'src/test/java/FooTest.java', 'jk.toml']),
  appeared: rows([]),
  gone: rows(['error · compile-java · src/main/java/Foo.java:12 · cannot find symbol'], 2),
  broke: rows([]),
  fixed: rows(['com.example.FooTest#adds()']),
  added: rows([]),
  dropped: rows([]),
};

test('chips read counts first: files, diagnostics, tests, wall', () => {
  const s = deltaSummary(delta, 6100);
  assert.equal(s.since.startsWith('since #12 · failed · '), true);
  assert.deepEqual(
    s.chips.map((c) => c.key),
    ['files', 'diagnostics', 'tests', 'wall'],
  );
  const [files, diags, tests, wall] = s.chips;
  assert.equal(files.text, '3 files');
  assert.deepEqual(files.lines, ['src/main/java/Foo.java', 'src/test/java/FooTest.java', 'jk.toml']);
  assert.equal(diags.text, '+0 / −2 diagnostics');
  assert.equal(diags.cls, 'good');
  assert.deepEqual(diags.lines, [
    'gone: error · compile-java · src/main/java/Foo.java:12 · cannot find symbol',
    'gone: +1 more',
  ]);
  assert.equal(tests.text, 'tests: 1 fixed');
  assert.equal(tests.cls, 'good');
  assert.deepEqual(tests.lines, ['fixed: com.example.FooTest#adds()']);
  assert.equal(wall.cls, 'good');
  assert.equal(wall.text.startsWith('−'), true);
});

test('a broken test or a new diagnostic colours its chip bad; absent comparisons are absent chips', () => {
  const worse = deltaSummary(
    {
      ...delta,
      files: undefined,
      appeared: rows(['error · run-tests · FooTest#adds() · expected 4']),
      gone: rows([]),
      broke: rows(['com.example.FooTest#adds()']),
      fixed: rows([]),
    },
    9000,
  );
  assert.deepEqual(
    worse.chips.map((c) => c.key),
    ['diagnostics', 'tests', 'wall'],
  );
  assert.equal(worse.chips[0].cls, 'bad');
  assert.equal(worse.chips[1].text, 'tests: 1 broke');
  assert.equal(worse.chips[1].cls, 'bad');
  assert.equal(worse.chips[2].cls, 'bad');

  const noTests = deltaSummary({ ...delta, broke: undefined, fixed: undefined, added: undefined, dropped: undefined }, 100);
  assert.deepEqual(
    noTests.chips.map((c) => c.key),
    ['files', 'diagnostics', 'wall'],
  );
  assert.equal(deltaSummary(null, 1), null);
});

test('row lines keep the bounded head and say how many more there are', () => {
  assert.deepEqual(rowLines(rows(['a', 'b'], 10)), ['a', 'b', '+8 more']);
  assert.deepEqual(rowLines(rows([])), []);
  assert.deepEqual(rowLines(undefined), []);
  assert.equal(signedDuration(0), '±0');
  assert.equal(signedDuration(-2300).startsWith('−'), true);
  assert.equal(signedDuration(410).startsWith('+'), true);
});

test('the delta rides the card from the journal row, and a live card picks it up on reconcile', () => {
  const rec = {
    id: 'r13',
    buildNumber: 13,
    kind: 'build',
    dir: '/ws',
    success: true,
    running: false,
    startedAt: 13_000,
    finishedAt: 19_100,
    millis: 6100,
    trigger: 'mcp',
    session: 'claude-code 3f9a',
    delta,
  };
  assert.deepEqual(historyCard(rec).delta, delta);
  assert.equal(historyCard({ ...rec, delta: undefined }).delta, null);

  const live = { ...historyCard({ ...rec, delta: undefined }), id: 77, historyId: null, delta: null };
  seedFromHistory([live], [rec]);
  assert.deepEqual(live.delta, delta);
});

test('the component renders the strip and opens the rows on click', () => {
  const summary = RunDelta.computed.summary.call({ delta, millis: 6100 });
  assert.equal(summary.chips.length, 4);
  const lines = RunDelta.computed.openLines.call({ summary });
  assert.deepEqual(lines.slice(0, 2), ['files · src/main/java/Foo.java', 'files · src/test/java/FooTest.java']);
  assert.equal(RunDelta.template.includes('run-delta-chip'), true);
});

test('one chip per run row: the strongest signal, with the whole strip behind it as the tooltip', () => {
  // A fix session: nothing broke, two diagnostics gone, a test fixed, faster — the fix reads first.
  const fixed = deltaChip(delta, 6100);
  assert.equal(fixed.text, '1 fixed');
  assert.equal(fixed.cls, 'good');
  assert.equal(fixed.tip.startsWith('since #12 · failed · '), true);
  assert.equal(fixed.tip.includes('3 files'), true);
  assert.equal(fixed.tip.includes('+0 / −2 diagnostics'), true);

  // Something broke: that outranks every gain.
  const broke = deltaChip({ ...delta, broke: rows(['com.example.FooTest#adds()']) }, 6100);
  assert.equal(broke.text, '1 broke');
  assert.equal(broke.cls, 'bad');

  // A diagnostic appeared and no test flipped.
  const worse = deltaChip(
    { ...delta, appeared: rows(['error · compile-java · Foo.java:1 · x']), gone: rows([]), fixed: rows([]) },
    6100,
  );
  assert.equal(worse.text, '+1 diagnostic');
  assert.equal(worse.cls, 'bad');

  // Only files moved: the count; nothing at all: the wall against the previous attempt.
  const files = deltaChip({ ...delta, gone: rows([]), fixed: rows([]) }, 6100);
  assert.equal(files.text, '3 files');
  assert.equal(files.cls, '');
  const wall = deltaChip({ ...delta, files: undefined, gone: rows([]), fixed: rows([]) }, 6100);
  assert.equal(wall.text.startsWith('−'), true);
  assert.equal(wall.cls, 'good');
  assert.equal(deltaChip(null, 6100), null);
});
