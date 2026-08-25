// SPDX-License-Identifier: Apache-2.0
// The shell contract between index.html and the SPA's modules.
//
// `clients/web` has no bundler, but it does have a module system: index.html loads exactly one
// entry, `<script src="/app.js" type="module">`, and every other file is reached from it through a
// static `import`. So the browser topologically orders the graph and a bad specifier or a missing
// export is a link error before a line of the app runs — a split costs an `import`, not a
// `<script>` tag and a load-order convention.
//
// Two failure modes survive that, and neither one throws:
//   - a module nothing imports is simply never loaded, so its code silently stops shipping;
//   - the in-DOM template calls a method by name, and Vue resolves a name that moved out from
//     under it to `undefined`, rendering an empty cell forever.
// Both are checked here, against the staged copy of the shipped assets.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const DIR = process.env.JK_APP_DIR;
const html = fs.readFileSync(path.join(DIR, 'index.html'), 'utf8');
const modules = fs.readdirSync(DIR).filter((f) => f.endsWith('.js')).sort();
const source = new Map(modules.map((f) => [f, fs.readFileSync(path.join(DIR, f), 'utf8')]));

/** `<script src="…">` tags that name a file this repo ships, in document order. */
function localScripts() {
  const out = [];
  for (const m of html.matchAll(/<script\b([^>]*)>/g)) {
    const attrs = m[1];
    const src = /\bsrc="([^"]+)"/.exec(attrs);
    if (!src || /^https?:/.test(src[1])) continue;
    out.push({ src: src[1], module: /\btype="module"/.test(attrs) });
  }
  return out;
}

/** Static `import … from './x.js'` specifiers of one module, with the names each one binds. */
function staticImports(text) {
  const out = [];
  for (const m of text.matchAll(/^import\s+([\s\S]*?)\s+from\s+'(\.\/[^']+)';/gm)) {
    const names = [];
    const braces = /\{([\s\S]*)\}/.exec(m[1]);
    if (braces) {
      for (const part of braces[1].split(',')) {
        const n = part.trim();
        if (n) names.push(n.split(/\s+as\s+/)[0].trim());
      }
    }
    out.push({ specifier: m[2], names });
  }
  return out;
}

/** Line and block comments removed, so prose in a comment cannot look like markup or code. */
function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

/** Names a module declares with `export`. */
function exportedNames(text) {
  const out = new Set();
  for (const m of text.matchAll(/^export\s+(?:async\s+)?(?:function\s*\*?|const|let|var|class)\s+([A-Za-z_$][\w$]*)/gm)) {
    out.add(m[1]);
  }
  return out;
}

test('index.html loads exactly one file of ours, as a module', () => {
  const scripts = localScripts();
  assert.deepEqual(
    scripts,
    [{ src: '/app.js', module: true }],
    'the SPA is one ES-module entry point; a second <script> would reintroduce a load order nothing checks',
  );
});

test('every static import names a file the module directory actually ships', () => {
  const missing = [];
  for (const [file, text] of source) {
    for (const { specifier } of staticImports(text)) {
      const target = specifier.replace(/^\.\//, '');
      if (!source.has(target)) missing.push(`${file} imports ${specifier}`);
    }
  }
  assert.deepEqual(missing, []);
});

test('every named import is a name its target module exports', () => {
  const unresolved = [];
  for (const [file, text] of source) {
    for (const { specifier, names } of staticImports(text)) {
      const target = specifier.replace(/^\.\//, '');
      const exports = exportedNames(source.get(target) ?? '');
      for (const n of names) {
        if (!exports.has(n)) unresolved.push(`${file}: ${n} is not exported by ${target}`);
      }
    }
  }
  assert.deepEqual(unresolved, []);
});

test('every shipped module is reachable from the entry point', () => {
  const seen = new Set();
  const walk = (file) => {
    if (seen.has(file)) return;
    seen.add(file);
    for (const { specifier } of staticImports(source.get(file) ?? '')) {
      walk(specifier.replace(/^\.\//, ''));
    }
  };
  walk('app.js');
  // Reached only through a lazy `await import()`, deliberately: it must not be on the shell's
  // critical path, and the browser fetches it on the first #project/<id>/files open.
  seen.add('api.js');
  const orphans = modules.filter((m) => !seen.has(m));
  assert.deepEqual(orphans, [], 'an unimported module never loads in the browser, and nothing says so');
});

test('importing the entry point initialises the app', async () => {
  const store = new Map();
  globalThis.sessionStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  };
  globalThis.localStorage = globalThis.sessionStorage;
  globalThis.location = { hash: '', hostname: '127.0.0.1', pathname: '/', search: '' };

  const { appOptions } = await import(pathToFileURL(path.join(DIR, 'app.js')));
  assert.ok(appOptions, 'app.js exports appOptions');
  const data = appOptions.data();
  assert.equal(data.view, 'activity');
  assert.ok(Object.keys(appOptions.methods).length > 100);
  assert.ok(Object.keys(appOptions.computed).length > 0);
});

test('every custom element the template uses is registered at mount', () => {
  const used = new Set();
  const tags = (text) => {
    for (const m of text.matchAll(/<([a-z][a-z0-9]*-[a-z0-9-]+)[\s/>]/g)) used.add(m[1]);
  };
  tags(html.replace(/<!--[\s\S]*?-->/g, ''));
  // A component's own template may mount another component rather than the shell doing it.
  for (const [, text] of source) tags(stripComments(text));
  const registered = new Set(
    [...source.get('app.js').matchAll(/\.component\('([^']+)'/g)].map((m) => m[1]),
  );
  const unregistered = [...used].filter((t) => !registered.has(t)).sort();
  assert.deepEqual(unregistered, [], `registered: ${[...registered].sort().join(', ')}`);
});

test('every name the in-DOM template calls exists on the root component', async () => {
  const { appOptions } = await import(pathToFileURL(path.join(DIR, 'app.js')));
  const known = new Set([
    ...Object.keys(appOptions.methods),
    ...Object.keys(appOptions.computed),
    ...Object.keys(appOptions.data()),
  ]);
  // Names the expression is allowed to reach that are not the component's: JS builtins, and the
  // aliases v-for / slot props bind inside the expression's own scope.
  const builtins = new Set(['Math', 'Object', 'Array', 'String', 'Number', 'Boolean', 'JSON', 'Date', 'undefined']);
  const scoped = new Set();
  for (const m of html.matchAll(/v-for="\(?([^)"]*)\)?\s+in\s/g)) {
    for (const part of m[1].split(',')) scoped.add(part.trim());
  }
  const missing = new Set();
  // Every Vue expression site: {{ … }}, :bound="…", @handler="…", v-if/v-show/v-for="…".
  const expressions = [
    ...[...html.matchAll(/\{\{([\s\S]*?)\}\}/g)].map((m) => m[1]),
    ...[...html.matchAll(/(?:[:@]|v-if=|v-else-if=|v-show=|v-model=)[a-zA-Z.-]*="([^"]*)"/g)].map((m) => m[1]),
  ];
  for (const raw of expressions) {
    // A quoted tooltip can carry any English word followed by a paren; only code counts.
    const expr = raw.replace(/'(?:[^'\\]|\\.)*'/g, "''");
    for (const m of expr.matchAll(/(?<![.\w$])([A-Za-z_$][\w$]*)\s*\(/g)) {
      const name = m[1];
      if (known.has(name) || builtins.has(name) || scoped.has(name)) continue;
      missing.add(name);
    }
  }
  assert.deepEqual([...missing].sort(), [], 'Vue resolves an unknown name to undefined and renders nothing');
});
