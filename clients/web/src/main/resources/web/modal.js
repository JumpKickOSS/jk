// SPDX-License-Identifier: Apache-2.0
// Dialogs own the mouse wheel. The page behind a dialog is locked (style.css: html:has(.modal-bg)),
// and a wheel anywhere over the dialog or its backdrop scrolls the dialog's content: an inner list
// that can still move in that direction scrolls itself, anything else scrolls the dialog's
// .modal-scroll region, and nothing ever reaches the page.

const LINE_PX = 16;

/** Whether `el` scrolls vertically and can still move by `dy` (positive is down). */
export function canScroll(el, dy, style = (e) => getComputedStyle(e)) {
  if (!el || el.nodeType !== 1 || el.scrollHeight <= el.clientHeight + 1) return false;
  const oy = style(el).overflowY;
  if (oy !== 'auto' && oy !== 'scroll') return false;
  return dy > 0 ? el.scrollTop + el.clientHeight < el.scrollHeight - 1 : el.scrollTop > 0;
}

/** The wheel's vertical travel in pixels, whatever unit the browser reported it in. */
export function wheelPixels(e, pagePx = 600) {
  if (e.deltaMode === 1) return e.deltaY * LINE_PX;
  if (e.deltaMode === 2) return e.deltaY * pagePx;
  return e.deltaY;
}

/** Route one wheel event: a no-op outside dialogs; inside, never lets it scroll the page. */
export function routeWheel(e, style) {
  const target = e.target && e.target.nodeType === 1 ? e.target : e.target && e.target.parentElement;
  const bg = target && target.closest ? target.closest('.modal-bg') : null;
  if (!bg) return;
  const dy = wheelPixels(e);
  for (let el = target; el && el !== bg; el = el.parentElement) {
    if (canScroll(el, dy, style)) return; // an inner scroller takes it natively
  }
  e.preventDefault();
  const modals = bg.querySelectorAll('.modal');
  const modal = modals[modals.length - 1];
  const region = modal && modal.querySelector('.modal-scroll');
  if (region && canScroll(region, dy, style)) region.scrollTop += dy;
}

/** Listen once on the document; dialogs come and go without registering anything. */
export function installModalScroll(doc) {
  doc.addEventListener('wheel', (e) => routeWheel(e), { passive: false });
}
