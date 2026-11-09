// SPDX-License-Identifier: Apache-2.0
// Dialogs own the mouse wheel: the page behind never scrolls, an inner list that can still move
// scrolls itself, and a wheel anywhere else over the dialog scrolls its .modal-scroll region.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const DIR = process.env.JK_APP_DIR;
const { canScroll, routeWheel, wheelPixels } = await import(pathToFileURL(path.join(DIR, 'modal.js')));
const css = fs.readFileSync(path.join(DIR, 'style.css'), 'utf8');

/** A fake element: a class list, a parent, and a scroll box. */
function el(cls, { parent = null, sh = 100, ch = 100, top = 0, oy = 'visible' } = {}) {
  const node = {
    nodeType: 1,
    cls: new Set(cls.split(' ').filter(Boolean)),
    parentElement: parent,
    scrollHeight: sh,
    clientHeight: ch,
    scrollTop: top,
    oy,
    kids: [],
    closest(sel) {
      for (let n = this; n; n = n.parentElement) if (n.cls.has(sel.slice(1))) return n;
      return null;
    },
    querySelector(sel) {
      return this.querySelectorAll(sel)[0] || null;
    },
    querySelectorAll(sel) {
      const out = [];
      const walk = (n) => n.kids.forEach((k) => { if (k.cls.has(sel.slice(1))) out.push(k); walk(k); });
      walk(this);
      return out;
    },
  };
  if (parent) parent.kids.push(node);
  return node;
}
const style = (e) => ({ overflowY: e.oy });

/** backdrop > modal > (head, scroll region > inner list). */
function dialog({ regionTop = 0, listTop = 0, listScrolls = true } = {}) {
  const bg = el('modal-bg');
  const modal = el('modal', { parent: bg });
  const head = el('modal-head', { parent: modal });
  const region = el('modal-scroll', { parent: modal, sh: 900, ch: 400, top: regionTop, oy: 'auto' });
  const list = el('inner', { parent: region, sh: listScrolls ? 300 : 100, ch: 100, top: listTop, oy: 'auto' });
  return { bg, modal, head, region, list };
}
function wheel(target, deltaY, deltaMode = 0) {
  return { target, deltaY, deltaMode, prevented: false, preventDefault() { this.prevented = true; } };
}

test('a wheel outside any dialog is left alone', () => {
  const page = el('page');
  const e = wheel(page, 120);
  routeWheel(e, style);
  assert.equal(e.prevented, false);
});

test('a wheel over the dialog head scrolls the dialog content, never the page', () => {
  const d = dialog();
  const e = wheel(d.head, 120);
  routeWheel(e, style);
  assert.equal(e.prevented, true);
  assert.equal(d.region.scrollTop, 120);
});

test('a wheel over the blurred backdrop scrolls the dialog content too', () => {
  const d = dialog();
  const e = wheel(d.bg, 50);
  routeWheel(e, style);
  assert.equal(e.prevented, true);
  assert.equal(d.region.scrollTop, 50);
});

test('an inner list that can still move takes the wheel natively', () => {
  const d = dialog();
  const e = wheel(d.list, 40);
  routeWheel(e, style);
  assert.equal(e.prevented, false);
  assert.equal(d.region.scrollTop, 0);
});

test('an inner list at its end hands the wheel to the region scroller and the page stays put', () => {
  const d = dialog({ listTop: 200 });
  const e = wheel(d.list, 40);
  routeWheel(e, style);
  assert.equal(e.prevented, false, 'the region itself can still move, so it scrolls natively');
  const atEnd = dialog({ listTop: 200, regionTop: 500 });
  const e2 = wheel(atEnd.list, 40);
  routeWheel(e2, style);
  assert.equal(e2.prevented, true, 'nothing in the dialog can move: the page must not scroll either');
  assert.equal(atEnd.region.scrollTop, 500);
});

test('a dialog whose content fits swallows the wheel without scrolling anything', () => {
  const d = dialog();
  d.region.scrollHeight = 300;
  const e = wheel(d.head, 120);
  routeWheel(e, style);
  assert.equal(e.prevented, true);
  assert.equal(d.region.scrollTop, 0);
});

test('canScroll needs overflow auto or scroll and room in the wheel direction', () => {
  assert.equal(canScroll(el('x', { sh: 500, ch: 100, oy: 'visible' }), 10, style), false);
  assert.equal(canScroll(el('x', { sh: 500, ch: 100, oy: 'auto' }), -10, style), false);
  assert.equal(canScroll(el('x', { sh: 500, ch: 100, oy: 'scroll', top: 400 }), 10, style), false);
  assert.equal(canScroll(el('x', { sh: 500, ch: 100, oy: 'auto', top: 10 }), -10, style), true);
});

test('line and page wheel units become pixels', () => {
  assert.equal(wheelPixels({ deltaY: 3, deltaMode: 1 }), 48);
  assert.equal(wheelPixels({ deltaY: 1, deltaMode: 2 }, 500), 500);
  assert.equal(wheelPixels({ deltaY: 7, deltaMode: 0 }), 7);
});

test('dialogs size to their content between a floor and a ceiling, and lock the page behind', () => {
  assert.match(css, /\.modal \{[^}]*min-height: var\(--modal-min-h\)/);
  assert.match(css, /\.modal \{[^}]*max-height: min\(calc\(100vh - 110px\), var\(--modal-max-h\)\)/);
  assert.match(css, /\.modal > \.modal-scroll \{[^}]*overflow-y: auto;[^}]*overscroll-behavior: contain/);
  assert.match(css, /html:has\(\.modal-bg\) \{ overflow: hidden; \}/);
  assert.doesNotMatch(css, /\.modal\.decision-modal \{[^}]*\bheight: 440px/);
});
