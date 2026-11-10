// SPDX-License-Identifier: Apache-2.0
// The "What's new" dialog: a highlight's Markdown becomes only code, bold and http(s) links, and
// #whats-new is a route that opens the dialog over the page.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const storage = () => {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
  };
};
globalThis.sessionStorage = storage();
globalThis.localStorage = storage();
globalThis.location = { hash: '', pathname: '/', search: '' };

const web = path.resolve(import.meta.dirname, '../../main/resources/web');
const { inlineMarkdown } = await import(pathToFileURL(path.join(web, 'whatsnew.js')).href);
const { routeFromHash } = await import(pathToFileURL(path.join(web, 'route.js')).href);

test('bold, code and links render, and nothing else does', () => {
  assert.equal(
    inlineMarkdown('**Faster builds.** Run `jk build -w 4` ([docs](https://jumpkick.build/docs)).'),
    '<strong>Faster builds.</strong> Run <code>jk build -w 4</code> (<a href="https://jumpkick.build/docs" target="_blank" rel="noopener">docs</a>).',
  );
});

test('markup in the text is escaped, never rendered', () => {
  assert.equal(inlineMarkdown('<img src=x onerror=alert(1)>'), '&lt;img src=x onerror=alert(1)&gt;');
  assert.equal(inlineMarkdown('`<b>`'), '<code>&lt;b&gt;</code>');
});

test('a link that is not http(s) stays text', () => {
  assert.equal(inlineMarkdown('[click](javascript:alert(1))'), '[click](javascript:alert(1))');
});

test('a bold hook may hold a code span', () => {
  assert.equal(
    inlineMarkdown('**`jk status` labels its row**, so it adds up.'),
    '<strong><code>jk status</code> labels its row</strong>, so it adds up.',
  );
});

test('bold inside a code span stays literal', () => {
  assert.equal(inlineMarkdown('`**x**`'), '<code>**x**</code>');
});

test('#whats-new is a route that opens the dialog', () => {
  assert.equal(routeFromHash('#whats-new').whatsNew, true);
  assert.equal(routeFromHash('#console').whatsNew, undefined);
});
