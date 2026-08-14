// SPDX-License-Identifier: Apache-2.0
// Headless tests for the dashboard #project/<id>/files helpers. Run by WebClientCodeTest.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const {
  parseHashQuery,
  routeFromHash,
  buildProjectHash,
  langFromPath,
  codePathForFailure,
  relativizeUnder,
  posixJoin,
  monacoLang,
  viewerOptions,
  lineDecorations,
  consoleBackground,
  themeDefinition,
  buildFileTree,
  visibleRows,
  ancestorDirs,
  defaultFilePath,
  DEFAULT_FILE,
  canHighlight,
  plainRows,
  MONACO_THEME,
  CONSOLE_BG_FALLBACK,
  HIGHLIGHT_MAX_BYTES,
  HIGHLIGHT_MAX_LINES,
} = await import(pathToFileURL(process.env.JK_CODE_MJS));

test('routeFromHash nests files under #project/<id>', () => {
  assert.deepEqual(routeFromHash('#project/ab12'), {
    view: 'project',
    projectId: 'ab12',
    files: false,
    path: null,
    line: 0,
  });
  assert.deepEqual(routeFromHash('#project/ab12/files'), {
    view: 'project',
    projectId: 'ab12',
    files: true,
    path: null,
    line: 0,
  });
  const r = routeFromHash('#project/ab12/files/src/Main.java?line=42');
  assert.equal(r.view, 'project');
  assert.equal(r.projectId, 'ab12');
  assert.equal(r.files, true);
  assert.equal(r.path, 'src/Main.java');
  assert.equal(r.line, 42);
  assert.equal(routeFromHash('#code').view, 'activity');
  assert.equal(routeFromHash('#project/').view, 'activity');
});

test('parseHashQuery drops malformed percent escapes', () => {
  assert.equal(Object.keys(parseHashQuery('#project/ab?')).length, 0);
  const q = parseHashQuery('#project/ab/files/src/Main.java?line=%&ok=1');
  assert.equal(q.ok, '1');
  assert.equal(q.line, undefined);
});

test('buildProjectHash keeps slashes in the file path', () => {
  assert.equal(buildProjectHash({ projectId: 'ab' }), '#project/ab');
  assert.equal(buildProjectHash({ projectId: 'ab', files: true }), '#project/ab/files');
  assert.equal(
    buildProjectHash({ projectId: 'ab', path: 'src/A+B.java', line: 3 }),
    '#project/ab/files/src/A%2BB.java?line=3',
  );
});

test('langFromPath is case-insensitive and closed', () => {
  assert.equal(langFromPath('Main.JAVA'), 'java');
  assert.equal(langFromPath('x.Kt'), 'kotlin');
  assert.equal(langFromPath('a.jsonl'), 'json');
  assert.equal(langFromPath('build.gradle'), null);
  assert.equal(langFromPath('pom.xml'), null);
});

test('codePathForFailure joins module-relative paths', () => {
  assert.equal(
    codePathForFailure({
      checkoutDir: '/ws',
      moduleDir: '/ws/lib',
      file: 'src/test/java/FooTest.java',
    }),
    'lib/src/test/java/FooTest.java',
  );
  assert.equal(
    codePathForFailure({
      checkoutDir: '/ws',
      moduleDir: '/ws',
      file: '/ws/src/Main.java',
    }),
    'src/Main.java',
  );
  assert.equal(
    codePathForFailure({ checkoutDir: '/ws', moduleDir: '/ws/lib', file: 'FooTest.java' }),
    null,
  );
  assert.equal(
    codePathForFailure({ checkoutDir: '/ws', moduleDir: '/ws/lib', file: '/etc/passwd' }),
    null,
  );
});

test('posixJoin and relativizeUnder', () => {
  assert.equal(posixJoin('lib', 'src/A.java'), 'lib/src/A.java');
  assert.equal(relativizeUnder('/ws', '/ws/lib/A.java'), 'lib/A.java');
  assert.equal(relativizeUnder('/ws', '/other/A.java'), null);
});

test('monacoLang maps server langs onto ids Monaco has', () => {
  assert.equal(monacoLang('java'), 'java');
  assert.equal(monacoLang('kotlin'), 'kotlin');
  // Monaco ships no Groovy or TOML grammar — closest registered grammar, never a bogus id.
  assert.equal(monacoLang('groovy'), 'java');
  assert.equal(monacoLang('toml'), 'ini');
  assert.equal(monacoLang('brainfuck'), 'plaintext');
  assert.equal(monacoLang(null), 'plaintext');
});

test('the theme is vs-dark with only the background overridden', () => {
  const t = themeDefinition('#0b1116');
  assert.equal(t.base, 'vs-dark');
  assert.equal(t.inherit, true); // every vs-dark token colour still applies
  assert.deepEqual(t.rules, []);
  assert.deepEqual(t.colors, { 'editor.background': '#0b1116' });
  assert.equal(themeDefinition().colors['editor.background'], CONSOLE_BG_FALLBACK);
  // No document here, and Monaco throws on a colour it cannot parse — so this must not pass through
  // an empty or exotic --console-bg.
  assert.equal(consoleBackground(), CONSOLE_BG_FALLBACK);
  assert.equal(consoleBackground({}), CONSOLE_BG_FALLBACK);
});

test('viewerOptions is a read-only viewer carrying content as a model value', () => {
  const o = viewerOptions({ content: '<script>alert(1)</script>', lang: 'java' });
  assert.equal(o.value, '<script>alert(1)</script>');
  assert.equal(o.language, 'java');
  assert.equal(o.theme, MONACO_THEME);
  assert.equal(o.readOnly, true);
  assert.equal(o.domReadOnly, true);
  assert.equal(o.renderLineHighlight, 'none');
  assert.equal(viewerOptions().value, '');
  assert.equal(viewerOptions({ content: 'x' }).language, 'plaintext');
});

test('lineDecorations marks only the ?line= row', () => {
  assert.deepEqual(lineDecorations(0), []);
  assert.deepEqual(lineDecorations('nope'), []);
  const [d] = lineDecorations(9);
  assert.deepEqual(d.range, {
    startLineNumber: 9,
    startColumn: 1,
    endLineNumber: 9,
    endColumn: 1,
  });
  assert.equal(d.options.isWholeLine, true);
  assert.equal(d.options.className, 'code-line-hl');
});

const TREE_PATHS = [
  'README.md',
  'build.gradle.kts',
  'shared/core/src/main/java/cc/jumpkick/config/JkBuildParser.java',
  'shared/core/src/main/java/cc/jumpkick/config/Ansi.java',
  'shared/core/src/test/java/cc/jumpkick/ConfigTest.java',
  'clients/web/src/main/resources/web/code.js',
];

test('buildFileTree puts dirs before files and compacts single-child chains', () => {
  const tree = buildFileTree(TREE_PATHS);
  assert.deepEqual(
    tree.children.map((n) => [n.name, n.dir]),
    [
      ['clients/web/src/main/resources/web', true],
      ['shared/core/src', true],
      ['build.gradle.kts', false], // names sort case-insensitively: build… before README…
      ['README.md', false],
    ],
  );
  // The compacted node keys off its DEEPEST path, which is what ancestorDirs yields for its files.
  const shared = tree.children[1];
  assert.equal(shared.path, 'shared/core/src');
  assert.ok(ancestorDirs(TREE_PATHS[2]).includes(shared.path));
  // Two children (main, test) below it, so the chain stops here and resumes under each.
  assert.deepEqual(shared.children.map((n) => n.name), [
    'main/java/cc/jumpkick/config',
    'test/java/cc/jumpkick',
  ]);
  assert.deepEqual(shared.children[0].children.map((n) => n.name), ['Ansi.java', 'JkBuildParser.java']);
  // A lone top-level dir stays its own row — the root is never a rendered node, so it never folds.
  assert.deepEqual(buildFileTree(['a/b.java']).children.map((n) => [n.name, n.path]), [['a', 'a']]);
});

test('visibleRows walks only the open dirs and carries depth', () => {
  const tree = buildFileTree(TREE_PATHS);
  const collapsed = visibleRows(tree, {});
  assert.deepEqual(collapsed.map((r) => [r.name, r.depth, r.dir]), [
    ['clients/web/src/main/resources/web', 0, true],
    ['shared/core/src', 0, true],
    ['build.gradle.kts', 0, false],
    ['README.md', 0, false],
  ]);
  assert.equal(collapsed[0].open, false);
  const open = visibleRows(tree, { 'shared/core/src': true, 'shared/core/src/main/java/cc/jumpkick/config': true });
  assert.deepEqual(open.map((r) => [r.name, r.depth]), [
    ['clients/web/src/main/resources/web', 0],
    ['shared/core/src', 0],
    ['main/java/cc/jumpkick/config', 1],
    ['Ansi.java', 2],
    ['JkBuildParser.java', 2],
    ['test/java/cc/jumpkick', 1],
    ['build.gradle.kts', 0],
    ['README.md', 0],
  ]);
  assert.deepEqual(visibleRows(null, {}), []);
});

test('ancestorDirs is every dir prefix, never the file', () => {
  assert.deepEqual(ancestorDirs('a/b/c/D.java'), ['a', 'a/b', 'a/b/c']);
  assert.deepEqual(ancestorDirs('README.md'), []);
  assert.deepEqual(ancestorDirs(null), []);
});

test('defaultFilePath opens the workspace jk.toml, and only that one', () => {
  assert.equal(DEFAULT_FILE, 'jk.toml');
  assert.equal(defaultFilePath([{ path: 'README.md' }, { path: 'jk.toml' }]), 'jk.toml');
  // A member's jk.toml is not the workspace's, and a tree without one falls through to the
  // pane's empty state rather than opening something arbitrary.
  assert.equal(defaultFilePath([{ path: 'sub/jk.toml' }]), null);
  assert.equal(defaultFilePath([{ path: 'pom.xml' }]), null);
  assert.equal(defaultFilePath([]), null);
  assert.equal(defaultFilePath(null), null);
});

test('highlight budget and plain rows', () => {
  assert.equal(canHighlight({ bytes: HIGHLIGHT_MAX_BYTES, lines: HIGHLIGHT_MAX_LINES, content: 'x' }), true);
  assert.equal(canHighlight({ bytes: HIGHLIGHT_MAX_BYTES + 1, lines: 1, content: 'x' }), false);
  assert.deepEqual(plainRows('a\nb\n'), ['a', 'b']);
});

test('a failed monaco load does not latch — the next open retries', async () => {
  // JK-1953: the memoized promise previously cached its own rejection, so one CDN hiccup
  // meant plain text for the tab's life. Headless node has no document, so every attempt
  // rejects — which is exactly the shape that must not latch.
  const { ensureMonaco } = await import(pathToFileURL(process.env.JK_CODE_MJS));
  const p1 = ensureMonaco();
  await assert.rejects(p1);
  const p2 = ensureMonaco();
  assert.notStrictEqual(p1, p2, 'second open must start a fresh load, not replay the rejection');
  await assert.rejects(p2);
});
