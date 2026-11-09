// SPDX-License-Identifier: Apache-2.0
// Telling the person about a decision once. The engine picks one channel per decision from what
// each dashboard tab reports over its event stream: in the page when a tab is visible, a browser
// notification from a hidden tab that may show one, the engine's native notification otherwise.
//
// The stream opens with a `presence` frame naming it and repeats it as a ping; the tab answers each
// with `POST /api/events/<stream>` and its visibility and `Notification.permission`, and answers
// again whenever either changes. The engine sends the chosen tab a `notify` frame; every tab closes
// a settled decision's notification by its tag.
//
// Showing and closing go through a `notifications` adapter ({ show, close }) and a click lands in
// `openDecision`, so a service worker can take over `showNotification` and `notificationclick`
// without changing the rule or the callers.

import { post } from './api.js';
import { decisionHash, frameDecision } from './decisions.js';
import { isOpen } from './needs.js';
import { SSE } from './wire.js';

/** The notification's title, as the engine's native notification reads. */
export const NOTIFY_TITLE = 'JumpKick needs you';

/** How long the bell pulses when a decision arrives while a tab is visible. */
export const PULSE_MS = 2400;

/** The browser's `Notification`, or null where there is none. */
function notificationApi() {
  return typeof Notification === 'undefined' ? null : Notification;
}

/** `default`, `granted`, `denied`, or `unsupported` where the browser has no notifications. */
export function permissionOf(api) {
  return api && typeof api.permission === 'string' ? api.permission : 'unsupported';
}

/** What a tab reports: whether it is visible, and whether it may notify. */
export function tabState(doc, api) {
  return {
    visible: !!doc && doc.visibilityState === 'visible',
    permission: permissionOf(api),
  };
}

/**
 * What this tab does with a decision the engine asked it to notify: `in-app` when it is visible
 * after all (the bell has it), `browser` when it may notify, else `none`. The engine chose from the
 * last report; this keeps a tab that changed since from notifying wrongly.
 */
export function pageChannel(state) {
  if (state.visible) return 'in-app';
  return state.permission === 'granted' ? 'browser' : 'none';
}

/** The notification for decision `d`: tagged with its id, so tabs replace rather than repeat it. */
export function notificationFor(d) {
  const who = d.label ? 'Asked by ' + d.label + '. ' : '';
  return {
    title: NOTIFY_TITLE,
    options: {
      body: d.question + '\n' + who + 'Answer it in the dashboard.',
      tag: d.id,
      icon: '/jk-logo.svg',
      data: { id: d.id, hash: decisionHash(d.id) },
    },
  };
}

/** The bell's one line asking for permission, or '' once the browser was answered (or cannot ask). */
export function askLine(permission) {
  return permission === 'default' ? 'Get a browser notification when a decision waits and this tab is hidden.' : '';
}

/**
 * Notifications shown from the page itself: `show(title, options)` and `close(tag)`, a click
 * focusing this tab and handing the decision id to `onOpen`. A service worker's adapter answers the
 * same two calls with `registration.showNotification` and `registration.getNotifications({tag})`,
 * and its `notificationclick` posts the id back to `onOpen`.
 */
export function pageNotifications(api, onOpen, win = typeof window === 'undefined' ? null : window) {
  const shown = new Map();
  return {
    show(title, options) {
      if (!api) return null;
      const n = new api(title, options);
      shown.set(options.tag, n);
      n.onclick = () => {
        if (win && typeof win.focus === 'function') win.focus();
        n.close();
        onOpen(options.data.id);
      };
      n.onclose = () => {
        if (shown.get(options.tag) === n) shown.delete(options.tag);
      };
      return n;
    },
    close(tag) {
      const n = shown.get(tag);
      if (!n) return;
      shown.delete(tag);
      n.close();
    },
  };
}

/** The root component's share. */
export function notifyData() {
  return {
    notifyPermission: permissionOf(notificationApi()), // what the bell's ask shows
    bellPulse: false,
    _stream: null, // the event stream's id, from its `presence` frame
  };
}

export const notifyMethods = {
  /** The adapter that shows and closes notifications; built once, on first use. */
  notifications() {
    if (!this._notifications) {
      this._notifications = pageNotifications(notificationApi(), (id) => this.openDecision(id));
    }
    return this._notifications;
  },
  /** A notification's click: the decision's dialog, over whatever page is showing. */
  openDecision(id) {
    location.hash = decisionHash(id);
  },
  /**
   * The presence ping and notify frames, handled here (true); a decision frame also pulses the bell
   * or closes its notification, and goes on to the console (false).
   */
  notifyEvent(event) {
    const { type, data } = event;
    if (type === SSE.presence) {
      if (data && data.stream) this._stream = data.stream;
      this.reportPresence();
      return true;
    }
    if (type === SSE.notify) {
      const d = frameDecision(data);
      if (!d) return true;
      const channel = pageChannel(this.tabState());
      if (channel === 'browser') {
        const n = notificationFor(d);
        this.notifications().show(n.title, n.options);
      } else if (channel === 'in-app') this.pulseBell();
      return true;
    }
    if (type === SSE.decision) {
      const d = frameDecision(data);
      if (!d) return false;
      if (data.change === 'resolved' || !isOpen(d)) this.notifications().close(d.id);
      else if (!this.decisions.some((x) => x.id === d.id) && this.tabState().visible) this.pulseBell();
    }
    return false;
  },
  tabState() {
    return tabState(typeof document === 'undefined' ? null : document, notificationApi());
  },
  /** Tells the engine how this tab stands; quiet when the stream has gone (the next one reports). */
  async reportPresence() {
    const stream = this._stream;
    if (!stream) return;
    const state = this.tabState();
    this.notifyPermission = state.permission;
    try {
      await post('/api/events/' + encodeURIComponent(stream), state);
    } catch {
      // The stream ended or the engine went away; its reconnect names a new stream.
    }
  },
  /** The bell's ask, on a click only: the browser's prompt, then the answer reported. */
  async askNotifications() {
    const api = notificationApi();
    if (!api || typeof api.requestPermission !== 'function') return;
    try {
      this.notifyPermission = await api.requestPermission();
    } catch {
      this.notifyPermission = permissionOf(api);
    }
    this.reportPresence();
  },
  /** The bell rings for a moment: a decision arrived while this tab is in front of someone. */
  pulseBell() {
    clearTimeout(this._pulseTimer);
    this.bellPulse = false;
    this.$nextTick(() => {
      this.bellPulse = true;
      this._pulseTimer = setTimeout(() => (this.bellPulse = false), PULSE_MS);
    });
  },
};
