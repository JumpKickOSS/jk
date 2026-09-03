// SPDX-License-Identifier: Apache-2.0
// Headless tests for dashboard label / detail helpers. Run by WebClientJsTest.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  detailForDisplay,
  detailSegments,
  looksLikeJavaMember,
  shortDisplayLabel,
  shortTestLabel,
  simpleTypeName,
  simplifyMethodParams,
} from './fold-harness.mjs';

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

test('detailSegments paints the bar-less unknown-size download label', () => {
  // Feed rows without archiveSize emit "downloading Temurin 25" alone.
  const bare = detailSegments('downloading Temurin 25');
  assert.ok(bare.some((s) => s.cls === 'det-mid' && s.text === 'downloading'));
  assert.ok(bare.some((s) => s.cls === 'det-jdk' && s.text === 'Temurin 25'));
  assert.equal(bare.filter((s) => s.cls === 'det-bar-fill' || s.cls === 'det-bar-empty').length, 0);
  assert.ok(!bare.some((s) => /%$/.test(s.text)));
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
