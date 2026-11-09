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
// Showing and closing go through a `notifications` adapter ({ show, close }). While the dashboard's
// service worker (sw.js) controls the page, it shows them, and they may carry the decision's
// choices as buttons; the page's own `Notification` is the fallback. A click lands in
// `openDecision`, a button in `answerDecision`, which answers as the dialog does.

import { get, post } from './api.js';
import { choiceBody, decisionChoices, decisionHash, frameDecision } from './decisions.js';
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

/** How many buttons a notification may carry here: 0 where the browser shows none. */
export function maxActions(api) {
  return api && Number.isInteger(api.maxActions) ? api.maxActions : 0;
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

/** The messages the service worker posts to a page; sw.js names the same two. */
export const WORKER_MESSAGE = Object.freeze({ open: 'open-decision', answer: 'answer-decision' });

/** The browser's `navigator.serviceWorker`, or null where there is none. */
function workerContainer() {
  return typeof navigator !== 'undefined' && navigator.serviceWorker ? navigator.serviceWorker : null;
}

/**
 * The notification for decision `d`: tagged with its id, so tabs replace rather than repeat it.
 * With room for `maxActions` buttons, a decision answered by one choice carries every choice as a
 * button, in the dialog's order; with too little room it carries none rather than some.
 */
export function notificationFor(d, maxActions = 0) {
  const who = d.label ? 'Asked by ' + d.label + '. ' : '';
  const options = {
    body: d.question + '\n' + who + 'Answer it in the dashboard.',
    tag: d.id,
    icon: '/icon-192.png',
    data: { id: d.id, hash: decisionHash(d.id) },
  };
  const choices = decisionChoices(d);
  if (choices && choices.length <= maxActions) options.actions = choices.map((c) => ({ action: c.id, title: c.label }));
  return { title: NOTIFY_TITLE, options };
}

/** The bell's one line asking for permission, or '' once the browser was answered (or cannot ask). */
export function askLine(permission) {
  return permission === 'default' ? 'Get a browser notification when a decision waits and this tab is hidden.' : '';
}

/**
 * Notifications shown from the page itself: `show(title, options)` and `close(tag)`, a click
 * focusing this tab and handing the decision id to `onOpen`. Only a service worker's notifications
 * may carry buttons, so these drop them.
 */
export function pageNotifications(api, onOpen, win = typeof window === 'undefined' ? null : window) {
  const shown = new Map();
  return {
    show(title, { actions, ...options }) {
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

/**
 * Notifications the service worker shows, through its registration once `container.ready`: the same
 * `show` and `close`, buttons kept. sw.js routes their clicks.
 */
export function workerNotifications(container) {
  return {
    async show(title, options) {
      const registration = await container.ready;
      return registration.showNotification(title, options);
    },
    async close(tag) {
      const registration = await container.ready;
      for (const n of await registration.getNotifications({ tag })) n.close();
    },
  };
}

/**
 * The adapter the page uses: the worker's while a service worker controls the page (`via` says
 * which), else the page's own. Closing asks both, so a notification shown before the worker took
 * over still closes.
 */
export function notificationsFor(api, container, onOpen, win) {
  const page = pageNotifications(api, onOpen, win);
  const worker = container ? workerNotifications(container) : null;
  const controlled = () => !!(worker && container.controller);
  return {
    get via() {
      return controlled() ? 'worker' : 'page';
    },
    show(title, options) {
      return (controlled() ? worker : page).show(title, options);
    },
    close(tag) {
      page.close(tag);
      if (controlled()) worker.close(tag).catch(() => {});
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
      this._notifications = notificationsFor(notificationApi(), workerContainer(), (id) => this.openDecision(id));
    }
    return this._notifications;
  },
  /** A notification's click: the decision's dialog, over whatever page is showing. */
  openDecision(id) {
    location.hash = decisionHash(id);
  },
  /**
   * A notification button: the answer `choice` posts as the dialog's button would, with this page's
   * token. A decision that is no longer open, a choice it does not offer, or a refused answer opens
   * the dialog instead, which says how it stands.
   */
  async answerDecision(id, choice) {
    const path = '/api/decisions/' + encodeURIComponent(id);
    try {
      const d = await get(path);
      const c = d && d.state === 'open' && (decisionChoices(d) || []).find((x) => x.id === choice);
      if (c) {
        await post(path, choiceBody(c));
        return;
      }
    } catch {
      // Fall through to the dialog.
    }
    this.openDecision(id);
  },
  /** A message from the service worker: a notification's click or button. */
  workerMessage(message) {
    if (!message || typeof message.id !== 'string') return;
    if (message.type === WORKER_MESSAGE.open) this.openDecision(message.id);
    else if (message.type === WORKER_MESSAGE.answer && typeof message.choice === 'string') {
      this.answerDecision(message.id, message.choice);
    }
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
        const n = notificationFor(d, maxActions(notificationApi()));
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
