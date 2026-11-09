// SPDX-License-Identifier: Apache-2.0
// The dashboard's service worker: it makes the dashboard installable and shows decision
// notifications, which may then carry the decision's choices as buttons. A click brings a dashboard
// window forward, or opens one at #decision/<id>, and hands the page the decision (notify.js).
//
// It has no fetch handler: every request goes to the engine as if no worker were installed, so it
// caches nothing and can never serve a previous engine's dashboard. No push either. A classic
// script, not a module, so every browser that has service workers can run it.
'use strict';

/** The messages a page takes from this worker; notify.js names the same two. */
const OPEN = 'open-decision';
const ANSWER = 'answer-decision';

// A changed worker takes over at once: it holds no state an open page relies on.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(routeClick(self.clients, self.registration.scope, event.notification.data, event.action));
});

/**
 * A click on decision notification `data` ({id}): the most recently focused dashboard window comes
 * forward and is told to open the decision, or to answer it with `action`, a choice id, when a
 * button was clicked. With no window open, one opens on the decision's dialog; a button's answer is
 * then left to the dialog, so no link can answer a decision by being opened.
 */
async function routeClick(clients, scope, data, action) {
  const id = data && data.id;
  if (!id) return;
  const windows = await clients.matchAll({ type: 'window', includeUncontrolled: true });
  const target = windows[0];
  if (!target) {
    await clients.openWindow(new URL('#decision/' + encodeURIComponent(id), scope).href);
    return;
  }
  try {
    await target.focus();
  } catch {
    // Focus needs the click's activation, which a slow matchAll can outlive; the message still lands.
  }
  target.postMessage(action ? { type: ANSWER, id, choice: action } : { type: OPEN, id });
}
