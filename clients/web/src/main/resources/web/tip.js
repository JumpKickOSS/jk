// SPDX-License-Identifier: Apache-2.0
// Themed tooltips: native HTML title= is OS chrome (harsh white-on-black) and cannot be styled.
// This module owns all hover help: data-tip preferred; bare title= is migrated on first hover.

const ATTR = 'data-tip';
const TITLE = 'title';

let layer = null;
let activeEl = null;
let hideTimer = null;

function ensureLayer() {
  if (layer) return layer;
  layer = document.createElement('div');
  layer.className = 'jk-tip jk-tip-float';
  layer.setAttribute('role', 'tooltip');
  layer.hidden = true;
  document.body.appendChild(layer);
  return layer;
}

function tipText(el) {
  if (!el || el.nodeType !== 1) return '';
  const fromData = el.getAttribute(ATTR);
  if (fromData != null && fromData !== '') return fromData;
  const fromTitle = el.getAttribute(TITLE);
  if (fromTitle != null && fromTitle !== '') return fromTitle;
  return '';
}

/** Steal title= into data-tip so the browser never draws its native brick. */
function migrateTitle(el) {
  if (!el || !el.hasAttribute(TITLE)) return;
  if (!el.hasAttribute(ATTR)) el.setAttribute(ATTR, el.getAttribute(TITLE));
  el.removeAttribute(TITLE);
}

function position(el) {
  const tip = ensureLayer();
  const r = el.getBoundingClientRect();
  // Measure after content set and unhidden.
  tip.hidden = false;
  tip.style.left = '0';
  tip.style.top = '0';
  const tw = tip.offsetWidth;
  const th = tip.offsetHeight;
  const gap = 8;
  const vw = window.innerWidth;
  const vh = window.innerHeight;

  // Prefer below the target; flip above if needed.
  let top = r.bottom + gap;
  if (top + th > vh - 4) top = Math.max(4, r.top - gap - th);

  // Center on target, clamp into the viewport.
  let left = r.left + r.width / 2 - tw / 2;
  left = Math.max(4, Math.min(left, vw - tw - 4));

  tip.style.left = Math.round(left) + 'px';
  tip.style.top = Math.round(top) + 'px';
}

function show(el) {
  const text = tipText(el);
  if (!text) return;
  migrateTitle(el);
  // Nested targets: prefer the deepest tip-bearing element under the pointer.
  activeEl = el;
  const tip = ensureLayer();
  tip.textContent = text;
  tip.hidden = false;
  tip.classList.add('jk-tip-visible');
  position(el);
}

function hide() {
  activeEl = null;
  if (!layer) return;
  layer.classList.remove('jk-tip-visible');
  layer.hidden = true;
  layer.textContent = '';
}

function scheduleHide() {
  clearTimeout(hideTimer);
  hideTimer = setTimeout(hide, 40);
}

function cancelHide() {
  clearTimeout(hideTimer);
  hideTimer = null;
}

function tipSource(from) {
  if (!from || !from.closest) return null;
  // Prefer data-tip, then title (before migration).
  return from.closest('[' + ATTR + '], [' + TITLE + ']');
}

/**
 * Install capture listeners on the document. Safe to call once after the SPA mounts.
 * Works for Vue re-renders that re-set title= — we migrate again on each hover.
 */
export function installTips(root = document) {
  ensureLayer();

  root.addEventListener(
    'pointerover',
    (e) => {
      const el = tipSource(e.target);
      if (!el) return;
      // Moving within the same tip host — keep showing.
      if (el === activeEl) {
        cancelHide();
        return;
      }
      cancelHide();
      show(el);
    },
    true,
  );

  root.addEventListener(
    'pointerout',
    (e) => {
      if (!activeEl) return;
      const to = e.relatedTarget;
      // Still inside the active host (or into the tip layer itself).
      if (to && (activeEl.contains(to) || (layer && layer.contains(to)))) return;
      if (to && tipSource(to) === activeEl) return;
      scheduleHide();
    },
    true,
  );

  root.addEventListener(
    'focusin',
    (e) => {
      const el = tipSource(e.target);
      if (!el) return;
      cancelHide();
      show(el);
    },
    true,
  );

  root.addEventListener(
    'focusout',
    (e) => {
      if (!activeEl) return;
      const to = e.relatedTarget;
      if (to && (activeEl.contains(to) || tipSource(to) === activeEl)) return;
      scheduleHide();
    },
    true,
  );

  // Scroll/resize: reposition or hide so the tip doesn't float mid-void.
  window.addEventListener(
    'scroll',
    () => {
      if (activeEl) position(activeEl);
    },
    true,
  );
  window.addEventListener('resize', () => {
    if (activeEl) position(activeEl);
  });
}
