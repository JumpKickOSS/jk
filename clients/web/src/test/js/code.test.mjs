// SPDX-License-Identifier: Apache-2.0
// Headless tests for the dashboard #project/<id>/files helpers. Run by WebClientJsTest.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));

const {
  CONSOLE_BG_FALLBACK,
  HIGHLIGHT_MAX_BYTES,
  HIGHLIGHT_MAX_LINES,
  MONACO_THEME,
  canHighlight,
  columnSpan,
  consoleBackground,
  hoverMessage,
  lineDecorations,
  markedParse,
  monacoLang,
  themeDefinition,
  viewerOptions,
} = await spa('monaco.js');
const {
  baseFileName,
  codePathForFailure,
  extractMermaidFences,
  injectMermaidSvgs,
  isImagePath,
  isPreviewable,
  isRemoteHttpUrl,
  isTextWritableLang,
  loadErrorMessage,
  posixJoin,
  previewKind,
  relativizeUnder,
  resolveMarkdownImagePath,
  resolveMarkdownLinkPath,
  resolveWorkspaceRelPath,
  sanitizeDiagramSvg,
  saveErrorMessage,
} = await spa('paths.js');
const { buildProjectHash, clipHashMsg, locusLabel, parseHashQuery, routeFromHash } = await spa('route.js');
const { DEFAULT_FILE, ROW_HEIGHT, defaultFilePath, dropSubtree, entryToward, plainRows, treeRows, visibleWindow } =
  await spa('tree.js');

test('routeFromHash nests files under #project/<id>', () => {
  assert.deepEqual(routeFromHash('#project/ab12'), {
    view: 'project',
    projectId: 'ab12',
    dir: null,
    files: false,
    path: null,
    line: 0,
    col: 0,
    lineErr: false,
    msg: '',
    run: 0,
    session: null,
    tab: 'runs',
  });
  assert.deepEqual(routeFromHash('#project/ab12/files'), {
    view: 'project',
    projectId: 'ab12',
    dir: null,
    files: true,
    path: null,
    line: 0,
    col: 0,
    lineErr: false,
    msg: '',
    run: 0,
    session: null,
    tab: 'runs',
  });
  const r = routeFromHash('#project/ab12/files/src/Main.java?line=42');
  assert.equal(r.view, 'project');
  assert.equal(r.projectId, 'ab12');
  assert.equal(r.files, true);
  assert.equal(r.path, 'src/Main.java');
  assert.equal(r.line, 42);
  assert.equal(r.col, 0);
  assert.equal(r.lineErr, false);
  assert.equal(r.msg, '');
  const err = routeFromHash('#project/ab12/files/src/Main.java?line=84&col=9&err=true');
  assert.equal(err.line, 84);
  assert.equal(err.col, 9);
  assert.equal(err.lineErr, true);
  const noted = routeFromHash(
    '#project/ab12/files/src/Main.java?line=2&col=5&err=true&msg=error%3A%20cannot%20find%20symbol',
  );
  assert.equal(noted.msg, 'error: cannot find symbol');
  assert.equal(noted.col, 5);
  assert.equal(noted.lineErr, true);
  assert.equal(routeFromHash('#code').view, 'console');
  assert.equal(routeFromHash('#project/').view, 'console');
});

test('project routes carry the checkout as ?dir= — every worktree of a repository shares the id', () => {
  assert.equal(routeFromHash('#project/ab12?dir=%2Fws%2Fwt-b').dir, '/ws/wt-b');
  assert.equal(routeFromHash('#project/ab12?dir=%2Fws%2Fwt-b&t=tok').projectId, 'ab12');
  const run = routeFromHash('#project/ab12/run/7?dir=%2Fws%2Fwt-b');
  assert.equal(run.run, 7);
  assert.equal(run.dir, '/ws/wt-b');
  const file = routeFromHash('#project/ab12/files/src/Main.java?line=3&err=true&dir=%2Fws%2Fmy%20app');
  assert.equal(file.path, 'src/Main.java');
  assert.equal(file.line, 3);
  assert.equal(file.dir, '/ws/my app');
  assert.equal(routeFromHash('#project/ab12/files?dir=%2Fws').dir, '/ws');

  assert.equal(buildProjectHash({ projectId: 'ab', dir: '/ws/wt-b' }), '#project/ab?dir=%2Fws%2Fwt-b');
  assert.equal(buildProjectHash({ projectId: 'ab', dir: null }), '#project/ab');
  assert.equal(buildProjectHash({ projectId: 'ab', dir: '/ws/wt-b', run: 7 }), '#project/ab/run/7?dir=%2Fws%2Fwt-b');
  assert.equal(buildProjectHash({ projectId: 'ab', dir: '/ws', files: true }), '#project/ab/files?dir=%2Fws');
  assert.equal(
    buildProjectHash({ projectId: 'ab', dir: '/ws/my app', path: 'src/Main.java', line: 3, err: true }),
    '#project/ab/files/src/Main.java?line=3&err=true&dir=%2Fws%2Fmy%20app',
  );
  // Round trip: what the CLI's failure link and the MCP dashboard link spell is what the route reads.
  const back = routeFromHash(buildProjectHash({ projectId: 'ab', dir: '/ws/my app', path: 'a/b.java', line: 9 }));
  assert.equal(back.dir, '/ws/my app');
  assert.equal(back.path, 'a/b.java');
  assert.equal(back.line, 9);
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
  assert.equal(
    buildProjectHash({ projectId: 'ab', path: 'src/Main.java', line: 84, err: true }),
    '#project/ab/files/src/Main.java?line=84&err=true',
  );
  assert.equal(
    buildProjectHash({ projectId: 'ab', path: 'src/Main.java', line: 12, col: 7, err: true }),
    '#project/ab/files/src/Main.java?line=12&col=7&err=true',
  );
  assert.equal(
    buildProjectHash({
      projectId: 'ab',
      path: 'src/Main.java',
      line: 12,
      col: 7,
      err: true,
      msg: 'error: cannot find symbol',
    }),
    '#project/ab/files/src/Main.java?line=12&col=7&err=true&msg=error%3A%20cannot%20find%20symbol',
  );
});

test('locusLabel appends line and column for copy-paste', () => {
  assert.equal(locusLabel(''), '');
  assert.equal(locusLabel('src/Main.java'), 'src/Main.java');
  assert.equal(locusLabel('src/Main.java', 0), 'src/Main.java');
  assert.equal(locusLabel('src/Main.java', 12), 'src/Main.java:12');
  assert.equal(locusLabel('src/Main.java', 12, 0), 'src/Main.java:12');
  assert.equal(locusLabel('src/Main.java', 12, 7), 'src/Main.java:12:7');
});

test('clipHashMsg and hoverMessage wrap compiler notes', () => {
  assert.equal(clipHashMsg(null), '');
  assert.equal(clipHashMsg('  hi  '), 'hi');
  assert.equal(clipHashMsg('x'.repeat(801)).length, 800);
  assert.ok(clipHashMsg('x'.repeat(801)).endsWith('…'));
  assert.equal(hoverMessage(''), undefined);
  assert.equal(hoverMessage('  '), undefined);
  assert.equal(hoverMessage('cannot find symbol').value, '```text\ncannot find symbol\n```');
  assert.match(hoverMessage('List<T>').value, /List<T>/);
  assert.match(hoverMessage('```evil```').value, /'''evil'''/);
});

test('preview eligibility is extension-driven', () => {
  assert.equal(previewKind('README.md'), 'markdown');
  assert.equal(previewKind('docs/flow.mmd'), 'mermaid');
  assert.equal(previewKind('g.dot'), 'graphviz');
  assert.equal(previewKind('note.adoc'), 'asciidoc');
  assert.equal(previewKind('box.d2'), 'd2');
  assert.equal(previewKind('logo.webp'), 'image');
  assert.equal(isPreviewable('src/Main.java'), false);
  assert.equal(isImagePath('assets/a.png'), true);
  assert.equal(isTextWritableLang('java'), true);
  assert.equal(isTextWritableLang('text'), true);
  assert.equal(isTextWritableLang('image'), false);
  assert.equal(isTextWritableLang('binary'), false);
  assert.equal(isTextWritableLang(null), false);
});

test('extractMermaidFences pulls fenced mermaid blocks out of markdown', () => {
  const md = '# Title\n\n```mermaid\ngraph TD\n  A-->B\n```\n\nMore text.\n\n```js\nconst x = 1;\n```\n';
  const { markdown, fences } = extractMermaidFences(md);
  assert.equal(fences.length, 1);
  assert.match(fences[0], /graph TD/);
  assert.match(markdown, /JKMERMAIDPLACEHOLDER0X/);
  assert.doesNotMatch(markdown, /```mermaid/);
  assert.match(markdown, /```js/);
  const html = injectMermaidSvgs('<p>JKMERMAIDPLACEHOLDER0X</p>', ['<svg></svg>']);
  assert.equal(html, '<p><svg></svg></p>');
});

test('monaco partial-failure retry does not re-inject loader.js', async () => {
  // loader.js loads and installs AMD require, but editor.main rejects once; the retry must
  // reuse the installed loader instead of appending a duplicate script tag. Fresh module
  // instance: a successful load memoizes, which would latch the shared instance's memo.
  const { ensureMonaco } = await import(pathToFileURL(path.join(process.env.JK_APP_DIR, 'monaco.js')).href + '?jk-monaco-retry');
  const tags = [];
  let mainLoads = 0;
  let failFirst = true;
  const fakeRequire = Object.assign(
    (deps, resolve, reject) => {
      mainLoads++;
      if (failFirst) {
        failFirst = false;
        reject(new Error('editor.main network hiccup'));
        return;
      }
      globalThis.window.monaco = { editor: { defineTheme() {} } };
      resolve();
    },
    { config() {} },
  );
  globalThis.document = {
    createElement: () => ({ remove() {} }),
    head: {
      appendChild(s) {
        tags.push(s);
        globalThis.window.require = fakeRequire;
        s.onload();
      },
    },
  };
  globalThis.window = {};
  try {
    await assert.rejects(ensureMonaco(), /hiccup/);
    const monaco = await ensureMonaco();
    assert.equal(monaco, globalThis.window.monaco);
    assert.equal(tags.length, 1, 'loader.js injected exactly once across the retry');
    assert.equal(mainLoads, 2, 'editor.main was retried');
  } finally {
    delete globalThis.document;
    delete globalThis.window;
  }
});

test('sanitizeDiagramSvg is the DOMPurify chokepoint with the SVG profiles', () => {
  const calls = [];
  const purify = {
    sanitize(html, opts) {
      calls.push({ html, opts });
      return '[clean]' + html;
    },
  };
  assert.equal(sanitizeDiagramSvg(purify, '<svg onload="x()"></svg>'), '[clean]<svg onload="x()"></svg>');
  assert.equal(sanitizeDiagramSvg(purify, null), '[clean]');
  assert.equal(calls.length, 2);
  for (const c of calls) {
    assert.deepEqual(c.opts, { USE_PROFILES: { svg: true, svgFilters: true } });
  }
});

test('loadErrorMessage says why a file cannot be shown', () => {
  assert.equal(loadErrorMessage({ status: 415, error: 'binary file' }), 'Binary file: there is no text to show');
  assert.equal(loadErrorMessage({ status: 413, error: 'file too large' }), 'Too large to show (over 1 MiB)');
  assert.equal(loadErrorMessage({ status: 404, error: 'not found' }), 'This file is not there any more');
  assert.equal(loadErrorMessage({ status: 400, error: 'illegal path' }), 'illegal path');
  assert.equal(loadErrorMessage({ status: 502 }), 'HTTP 502');
  assert.equal(loadErrorMessage(null), 'Failed to load file');
});

test('saveErrorMessage covers network and concurrency', () => {
  assert.match(saveErrorMessage({ status: 409, error: 'file changed on disk' }), /changed on disk/i);
  assert.match(saveErrorMessage({ name: 'TypeError' }), /engine/i);
  assert.match(saveErrorMessage({ status: 401 }), /authorized/i);
  assert.match(saveErrorMessage({ status: 413 }), /large/i);
});

test('markedParse accepts function or {parse} shapes', () => {
  assert.equal(markedParse((s) => 'X' + s, 'hi'), 'Xhi');
  assert.equal(markedParse({ parse: (s) => 'Y' + s }, 'hi'), 'Yhi');
  assert.throws(() => markedParse(null, 'x'), /not loaded/);
  assert.throws(() => markedParse({}, 'x'), /unavailable/);
});

test('baseFileName is the last path segment', () => {
  assert.equal(baseFileName('AGENTS.md'), 'AGENTS.md');
  assert.equal(baseFileName('docs/guide.md'), 'guide.md');
  assert.equal(baseFileName('src/main/java/Main.java'), 'Main.java');
  assert.equal(baseFileName(''), '');
});

test('resolveMarkdownImagePath joins relative to the open file', () => {
  assert.equal(resolveMarkdownImagePath('README.md', 'docs/logo.png'), 'docs/logo.png');
  assert.equal(resolveMarkdownImagePath('docs/guide.md', './img/a.png'), 'docs/img/a.png');
  assert.equal(resolveMarkdownImagePath('docs/guide.md', '../logo.png'), 'logo.png');
  assert.equal(resolveMarkdownImagePath('docs/guide.md', '/assets/icon.svg'), 'assets/icon.svg');
  assert.equal(resolveMarkdownImagePath('docs/guide.md', '../../escape.png'), null);
  assert.equal(resolveMarkdownImagePath('README.md', 'https://example.com/a.png'), null);
  assert.equal(resolveMarkdownImagePath('README.md', 'notes.txt'), null);
  assert.equal(isRemoteHttpUrl('https://github.com/user-attachments/assets/abc'), true);
  assert.equal(isRemoteHttpUrl('//img.shields.io/badge/x-y.svg'), true);
  assert.equal(isRemoteHttpUrl('docs/logo.png'), false);
});

test('resolveMarkdownLinkPath opens workspace paths (not only images)', () => {
  assert.equal(resolveMarkdownLinkPath('README.md', 'docs/architecture.md'), 'docs/architecture.md');
  assert.equal(resolveMarkdownLinkPath('README.md', 'docs/architecture.md#sec'), 'docs/architecture.md');
  assert.equal(resolveMarkdownLinkPath('README.md', 'LICENSE'), 'LICENSE');
  assert.equal(resolveWorkspaceRelPath('docs/a.md', '../b.md'), 'b.md');
  assert.equal(resolveMarkdownLinkPath('README.md', 'https://openjdk.org/'), null);
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
  // Single-plan live key is empty SINGLE_PLAN_DIR — file is already checkout-relative.
  assert.equal(
    codePathForFailure({
      checkoutDir: '/ws',
      moduleDir: '',
      file: 'src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java',
    }),
    'src/test/java/cc/jumpkick/runtime/DogfoodFailureSnippetTest.java',
  );
  assert.equal(
    codePathForFailure({
      checkoutDir: '/ws',
      moduleDir: '/ws',
      file: 'src/test/java/FooTest.java',
    }),
    'src/test/java/FooTest.java',
  );
  assert.equal(
    codePathForFailure({ checkoutDir: '/ws', moduleDir: '/ws/lib', file: 'FooTest.java' }),
    null,
  );
  assert.equal(
    codePathForFailure({ checkoutDir: '/ws', moduleDir: '/ws/lib', file: '/etc/passwd' }),
    null,
  );
  assert.equal(
    codePathForFailure({
      checkoutDir: '/ws',
      moduleDir: '',
      file: 'src/../secret/Foo.java',
    }),
    null,
  );
  // Fail-report deep link shape: path + error line → Monaco ?line= highlight.
  assert.equal(
    buildProjectHash({
      projectId: 'ab12',
      path: codePathForFailure({
        checkoutDir: '/ws',
        moduleDir: '',
        file: 'src/test/java/FooTest.java',
      }),
      line: 23,
    }),
    '#project/ab12/files/src/test/java/FooTest.java?line=23',
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
  assert.equal(monacoLang('typescript'), 'typescript');
  assert.equal(monacoLang('protobuf'), 'proto');
  assert.equal(monacoLang('dockerfile'), 'dockerfile');
  assert.equal(monacoLang('text'), 'plaintext');
  assert.equal(monacoLang('csv'), 'plaintext');
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

test('viewerOptions is an editable light editor carrying content as a model value', () => {
  const o = viewerOptions({ content: '<script>alert(1)</script>', lang: 'java' });
  assert.equal(o.value, '<script>alert(1)</script>');
  assert.equal(o.language, 'java');
  assert.equal(o.theme, MONACO_THEME);
  assert.equal(o.readOnly, false);
  assert.equal(o.domReadOnly, false);
  assert.equal(o.renderLineHighlight, 'none');
  assert.equal(viewerOptions().value, '');
  assert.equal(viewerOptions({ content: 'x' }).language, 'plaintext');
  assert.equal(viewerOptions({ content: 'x', readOnly: true }).readOnly, true);
});

test('lineDecorations: neutral vs error styles', () => {
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
  assert.equal(d.options.linesDecorationsClassName, 'code-line-hl-gutter');
  assert.equal(d.options.overviewRuler, undefined);
  const [e] = lineDecorations(84, true);
  assert.equal(e.options.className, 'code-line-err');
  assert.equal(e.options.linesDecorationsClassName, 'code-line-err-gutter');
  assert.ok(e.options.overviewRuler);
  assert.ok(e.options.minimap);
  const marked = lineDecorations(2, true, 7, '    b.key(x);');
  assert.equal(marked.length, 2);
  assert.deepEqual(marked[1].range, {
    startLineNumber: 2,
    startColumn: 7,
    endLineNumber: 2,
    endColumn: 10,
  });
  assert.equal(marked[1].options.inlineClassName, 'code-col-err');
  assert.equal(marked[0].options.hoverMessage, undefined);
  const jump = lineDecorations(2, false, 1, 'abc');
  assert.equal(jump[1].options.inlineClassName, 'code-col-hl');
  const noted = lineDecorations(2, true, 7, '    b.key(x);', 'error: cannot find symbol');
  assert.match(noted[0].options.hoverMessage.value, /cannot find symbol/);
  assert.equal(noted[1].options.hoverMessage, undefined);
});

test('columnSpan covers the identifier at col', () => {
  assert.equal(columnSpan('', 0), null);
  assert.deepEqual(columnSpan('    b.key(x);', 7), { start: 7, end: 10 });
  assert.deepEqual(columnSpan('    @Test', 6), { start: 6, end: 10 });
  assert.deepEqual(columnSpan('foo + bar', 5), { start: 5, end: 6 });
});

// Directory listings as GET /api/project/dir answers them: folders first, folded chains as one row.
const ROOT = {
  entries: [
    { name: 'clients/web', path: 'clients/web', dir: true },
    { name: 'shared/core/src', path: 'shared/core/src', dir: true },
    { name: 'target', path: 'target', dir: true, output: true },
    { name: 'empty', path: 'empty', dir: true, empty: true },
    { name: 'build.gradle.kts', path: 'build.gradle.kts', dir: false, lang: 'kotlin' },
    { name: 'README.md', path: 'README.md', dir: false, lang: 'markdown' },
  ],
  next: null,
  total: 6,
};
const SRC = {
  entries: [
    { name: 'main/java/cc/jumpkick/config', path: 'shared/core/src/main/java/cc/jumpkick/config', dir: true },
    { name: 'test/java/cc/jumpkick', path: 'shared/core/src/test/java/cc/jumpkick', dir: true },
  ],
  next: null,
  total: 2,
};
const CONFIG = {
  entries: [
    { name: 'Ansi.java', path: 'shared/core/src/main/java/cc/jumpkick/config/Ansi.java', dir: false, lang: 'java' },
    { name: 'Parser.java', path: 'shared/core/src/main/java/cc/jumpkick/config/Parser.java', dir: false, lang: 'java' },
  ],
  next: '2',
  total: 700,
};

test('treeRows walks only the open folders, in the order the engine listed them', () => {
  const collapsed = treeRows({ '': ROOT }, {});
  assert.deepEqual(collapsed.map((r) => [r.kind, r.name, r.depth]), [
    ['dir', 'clients/web', 0],
    ['dir', 'shared/core/src', 0],
    ['dir', 'target', 0],
    ['dir', 'empty', 0],
    ['file', 'build.gradle.kts', 0],
    ['file', 'README.md', 0],
  ]);
  assert.equal(collapsed[2].output, true);
  assert.equal(collapsed[3].empty, true);

  const config = 'shared/core/src/main/java/cc/jumpkick/config';
  const open = treeRows(
    { '': ROOT, 'shared/core/src': SRC, [config]: CONFIG },
    { 'shared/core/src': true, [config]: true, 'clients/web': true, empty: true },
  );
  assert.deepEqual(open.map((r) => [r.kind, r.name || '', r.depth]), [
    ['dir', 'clients/web', 0],
    ['loading', '', 1], // open, not loaded yet
    ['dir', 'shared/core/src', 0],
    ['dir', 'main/java/cc/jumpkick/config', 1],
    ['file', 'Ansi.java', 2],
    ['file', 'Parser.java', 2],
    ['more', '', 2], // 698 more behind the cursor
    ['dir', 'test/java/cc/jumpkick', 1],
    ['dir', 'target', 0],
    ['dir', 'empty', 0], // open but empty: nothing below it
    ['file', 'build.gradle.kts', 0],
    ['file', 'README.md', 0],
  ]);
  const more = open.find((r) => r.kind === 'more');
  assert.equal(more.path, config);
  assert.equal(more.remaining, 698);
  assert.equal(new Set(open.map((r) => r.key)).size, open.length, 'every row has its own key');
  assert.deepEqual(treeRows({}, {}), []);
  assert.deepEqual(treeRows({ '': { entries: [], error: 'HTTP 500' } }, {}).map((r) => r.kind), ['error']);
});

test('visibleWindow draws the rows in view plus overscan, and sizes the scroller for all of them', () => {
  assert.equal(ROW_HEIGHT, 24);
  const top = visibleWindow(20_000, 0, 480, 24, 10);
  assert.deepEqual(top, { start: 0, end: 31, offset: 0, total: 480_000 });
  const mid = visibleWindow(20_000, 24_000, 480, 24, 10);
  assert.deepEqual(mid, { start: 990, end: 1031, offset: 23_760, total: 480_000 });
  const end = visibleWindow(20_000, 480_000, 480, 24, 10);
  assert.equal(end.end, 20_000);
  assert.ok(end.end - end.start <= 31);
  assert.deepEqual(visibleWindow(0, 0, 480), { start: 0, end: 0, offset: 0, total: 0 });
});

test('entryToward finds the file, or the folder (a folded chain by its deepest path) that holds it', () => {
  const target = 'shared/core/src/main/java/cc/jumpkick/config/Parser.java';
  assert.equal(entryToward(ROOT.entries, target).path, 'shared/core/src');
  assert.equal(entryToward(SRC.entries, target).path, 'shared/core/src/main/java/cc/jumpkick/config');
  assert.equal(entryToward(CONFIG.entries, target).path, target);
  assert.equal(entryToward(ROOT.entries, 'shared/corex/A.java'), null, 'a prefix of a name is not a folder');
  assert.equal(entryToward(ROOT.entries, ''), null);
});

test('dropSubtree forgets a folder and everything loaded below it', () => {
  const config = 'shared/core/src/main/java/cc/jumpkick/config';
  const dirs = { '': ROOT, 'shared/core/src': SRC, [config]: CONFIG, 'shared/core/srcx': SRC };
  const expanded = { 'shared/core/src': true, [config]: true, 'clients/web': true };
  const next = dropSubtree(dirs, expanded, 'shared/core/src');
  assert.deepEqual(Object.keys(next.dirs).sort(), ['', 'shared/core/srcx']);
  assert.deepEqual(next.expanded, { 'clients/web': true });
  assert.ok(dirs[config], 'the inputs are left alone');
});

test('defaultFilePath opens the workspace jk.toml, and only that one', () => {
  assert.equal(DEFAULT_FILE, 'jk.toml');
  assert.equal(defaultFilePath([{ path: 'README.md' }, { path: 'jk.toml' }]), 'jk.toml');
  assert.equal(defaultFilePath([{ path: 'jk.toml', dir: true }]), null, 'a folder named jk.toml is not the manifest');
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
  // the memoized promise previously cached its own rejection, so one CDN hiccup
  // meant plain text for the tab's life. Headless node has no document, so every attempt
  // rejects — which is exactly the shape that must not latch.
  const { ensureMonaco } = await spa('monaco.js');
  const p1 = ensureMonaco();
  await assert.rejects(p1);
  const p2 = ensureMonaco();
  assert.notStrictEqual(p1, p2, 'second open must start a fresh load, not replay the rejection');
  await assert.rejects(p2);
});
