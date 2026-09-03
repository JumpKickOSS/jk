// SPDX-License-Identifier: Apache-2.0
// Headless tests for dashboard compiler and test-failure reports. Run by WebClientJsTest.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  compilerFailureReports,
  isCompilerDiag,
  isTestFailureDiag,
  normalizeDiagnostic,
  parseAssertJMessage,
  parseCompilerBlock,
  snippetWindow,
  stackFrameLines,
  testFailureReport,
} from './fold-harness.mjs';

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

test('testFailureReport builds CLI-shaped model with snippet rows and error line', () => {
  const d = normalizeDiagnostic({
    code: 'test-failure',
    message: '[dogfood: hello]\nexpected: "42"\n but was: "41"',
    module: 'cc.jumpkick:jk-engine',
    testClass: 'cc.jumpkick.runtime.DogfoodFailureSnippetTest',
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
    testClass: 'pkg.FooTest',
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

test('isCompilerDiag matches javac kotlinc groovyc', () => {
  assert.equal(isCompilerDiag({ code: 'javac' }), true);
  assert.equal(isCompilerDiag({ code: 'kotlinc' }), true);
  assert.equal(isCompilerDiag({ code: 'groovyc' }), true);
  assert.equal(isCompilerDiag({ code: 'test-failure' }), false);
  assert.equal(isCompilerDiag({ code: 'error' }), false);
  assert.equal(isCompilerDiag(null), false);
});

test('parseCompilerBlock parses real groovyc output (space + column trailer)', () => {
  // groovyc's "path: 5: message @ line 5, column 1." never matched, so groovyc blobs
  // always fell back to an unstructured "Compile failure" blob.
  const units = parseCompilerBlock(
    '/w/src/main/groovy/Foo.groovy: 5: unexpected token: } @ line 5, column 1.', 'error');
  assert.equal(units.length, 1);
  assert.equal(units[0].file, '/w/src/main/groovy/Foo.groovy');
  assert.equal(units[0].line, 5);
  assert.equal(units[0].col, 1);
});

test('parseCompilerBlock extracts error kv, caret column, and snippet line', () => {
  const units = parseCompilerBlock(
    '/ws/server/engine/src/main/java/cc/jumpkick/compile/AssemblyPackager.java:35: error: class, interface, enum, or record expected\n' +
      'public final classaa AssemblyPackager {\n' +
      '             ^\n',
  );
  assert.equal(units.length, 1);
  assert.equal(units[0].file, '/ws/server/engine/src/main/java/cc/jumpkick/compile/AssemblyPackager.java');
  assert.equal(units[0].line, 35);
  assert.equal(units[0].col, 14);
  assert.equal(units[0].kvs.length, 1);
  assert.equal(units[0].kvs[0].key, 'error');
  assert.equal(units[0].kvs[0].value, 'class, interface, enum, or record expected');
  assert.equal(units[0].snippet, 'public final classaa AssemblyPackager {');
});

test('multi-unit blob keeps each unit under its own file despite a diag-level file', () => {
  // file was not gated on i===0 like line/col, so unit 2 rendered as
  // "<first file>:<unit2 line>" — wrong locus, deep link, and context-window fetch.
  const d = {
    code: 'javac',
    file: '/w/A.java',
    line: 3,
    col: 1,
    message: '/w/A.java:3: error: cannot find symbol\n/w/B.java:7: error: incompatible types',
  };
  const reps = compilerFailureReports(d, { showHeader: true, module: 'g:app' });
  assert.equal(reps.length, 2);
  assert.equal(reps[0].file, '/w/A.java');
  assert.equal(reps[0].line, 3);
  assert.equal(reps[1].file, '/w/B.java');
  assert.equal(reps[1].line, 7);
});

test('compilerFailureReports builds CLI-shaped compile model', () => {
  const d = normalizeDiagnostic({
    task: 'compile-java',
    code: 'javac',
    message:
      '/home/bsant/src/oss/jk/server/engine/src/main/java/cc/jumpkick/compile/AssemblyPackager.java:35: error: class, interface, enum, or record expected\n' +
      'public final classaa AssemblyPackager {\n' +
      '             ^\n',
    file: '/home/bsant/src/oss/jk/server/engine/src/main/java/cc/jumpkick/compile/AssemblyPackager.java',
    line: 35,
    col: 14,
  });
  assert.equal(d.col, 14);
  const reps = compilerFailureReports(d, { showHeader: true, module: 'cc.jumpkick:jk-engine' });
  assert.equal(reps.length, 1);
  const rep = reps[0];
  assert.equal(rep.kind, 'compile');
  assert.equal(rep.showHeader, true);
  assert.equal(rep.headerLabel, 'Compile failure');
  assert.equal(rep.module, 'cc.jumpkick:jk-engine');
  assert.equal(rep.kvs[0].key, 'error');
  assert.equal(rep.kvs[0].value, 'class, interface, enum, or record expected');
  assert.equal(rep.line, 35);
  assert.equal(rep.col, 14);
  assert.equal(rep.rows.length, 1);
  assert.equal(rep.rows[0].num, 35);
  assert.equal(rep.rows[0].error, true);
  assert.equal(rep.rows[0].code, 'public final classaa AssemblyPackager {');
});

test('snippetWindow paints two lines of context around the error', () => {
  const lines = ['a', 'b', 'c', 'd', 'e', 'f', 'g'];
  const rows = snippetWindow(lines, 4);
  assert.deepEqual(
    rows.map((r) => r.num),
    [2, 3, 4, 5, 6],
  );
  assert.equal(rows[2].error, true);
  assert.equal(rows[2].code, 'd');
  assert.equal(rows.filter((r) => r.error).length, 1);
});

test('snippetWindow numbers a lone compiler line with the real error line', () => {
  const rows = snippetWindow(['public final classaa AssemblyPackager {'], 35);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].num, 35);
  assert.equal(rows[0].error, true);
});

test('compilerFailureReports subsequent unit suppresses the shared header', () => {
  const d = normalizeDiagnostic({
    code: 'javac',
    message:
      '/w/Foo.java:2: error: compact source file should not have package declaration\n' +
      'package x;\n' +
      '^\n',
    file: '/w/Foo.java',
    line: 2,
    col: 1,
  });
  const hidden = compilerFailureReports(d, { showHeader: false, module: 'g:a' });
  assert.equal(hidden[0].showHeader, false);
});
