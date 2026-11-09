// SPDX-License-Identifier: Apache-2.0
// Telling the person about a decision once. The engine picks one channel per decision from what
// each dashboard tab reports over its event stream: in the page when a tab is active, a browser
// notification from a tab that may show one, the engine's native notification otherwise.
//
// Active means visible, focused, and used (a mouse or key input) within the last two minutes, the
// engine's IDLE_AFTER; the page keeps its own last-input time, so no browser permission is asked.
// A dashboard showing on a second monitor while the person types in their editor is visible but not
// active, and the operating system's notification still reaches them.
//
// The stream opens with a `presence` frame naming it and repeats it as a ping; the tab answers each
// with `POST /api/events/<stream>` and its state, and answers again whenever its visibility, focus
// or permission changes and when it turns idle or active. The engine sends the chosen tab a
// `notify` frame; every tab closes a settled decision's notification by its tag.
//
// In the page, a notification wiggles the bell once (a highlight under reduced motion) and shows a
// short preview card under it with one line and a link; the card never takes focus, and hovering it
// keeps it open.
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

/** How long the bell's wiggle (or its reduced-motion highlight) lasts. */
export const PULSE_MS = 1600;

/** How long a tab stays active after the person's last input; the engine's IDLE_AFTER. */
export const IDLE_MS = 120_000;

/** How long the preview card under the bell stays, unless hovered. */
export const PREVIEW_MS = 4000;

/** The input events that count as the person using the tab. */
export const INPUT_EVENTS = Object.freeze(['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart']);

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

/**
 * What a tab reports: whether it is visible, whether its window has focus, how long ago (ms) the
 * person last used it (-1 when never, `lastInputAt` 0), and whether it may notify.
 */
export function tabState(doc, api, lastInputAt = 0, now = Date.now()) {
  return {
    visible: !!doc && doc.visibilityState === 'visible',
    focused: !!doc && typeof doc.hasFocus === 'function' && !!doc.hasFocus(),
    lastInputAgoMs: lastInputAt > 0 ? Math.max(0, now - lastInputAt) : -1,
    permission: permissionOf(api),
  };
}

/** True when a person is using the tab: visible, focused, and used within IDLE_MS. */
export function isActive(state) {
  return !!state && state.visible && state.focused && state.lastInputAgoMs >= 0 && state.lastInputAgoMs <= IDLE_MS;
}

/**
 * What this tab does with a decision the engine asked it to notify: `in-app` when it is active
 * after all (the page shows it), `browser` when it may notify, else `none`. The engine chose from
 * the last report; this keeps a tab that changed since from notifying wrongly.
 */
export function pageChannel(state) {
  if (isActive(state)) return 'in-app';
  return state.permission === 'granted' ? 'browser' : 'none';
}

/** The preview card's line for a decision that arrived: who waits, and on what. */
export function decisionPreview(d) {
  const who = d.label || (d.kind === 'setup' ? 'JumpKick' : 'An agent');
  return { id: d.id, text: who + ' needs you: ' + (d.question || 'a decision waits'), href: decisionHash(d.id), action: 'View' };
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
    preview: null, // the card under the bell: { id, text, href, action }
    _stream: null, // the event stream's id, from its `presence` frame
    _lastInputAt: 0, // when the person last used this tab (ms), 0 for never
    _lastKeyAt: 0, // when they last pressed a key here (ms)
    _reportedActive: false, // whether the last report said active, so a change reports again
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
      } else if (channel === 'in-app') this.decisionArrived(d);
      return true;
    }
    if (type === SSE.decision) {
      const d = frameDecision(data);
      if (!d) return false;
      if (data.change === 'resolved' || !isOpen(d)) {
        this.notifications().close(d.id);
        if (this.preview && this.preview.id === d.id) this.dismissPreview();
      } else if (!this.decisions.some((x) => x.id === d.id) && isActive(this.tabState())) this.decisionArrived(d);
    }
    return false;
  },
  /** A decision arrived while someone uses this tab: the bell wiggles and the card previews it. */
  decisionArrived(d) {
    this.deliverInApp(decisionPreview(d));
  },
  /** In-app delivery: the bell wiggles once and the preview card shows `card` for a moment. */
  deliverInApp(card) {
    this.pulseBell();
    this.showPreview(card);
  },
  /** The card under the bell, for PREVIEW_MS unless hovered; it never takes focus. */
  showPreview(card) {
    clearTimeout(this._previewTimer);
    this.preview = card;
    this._previewTimer = setTimeout(() => this.dismissPreview(), PREVIEW_MS);
  },
  /** Hovering the card keeps it. */
  holdPreview() {
    clearTimeout(this._previewTimer);
  },
  /** Leaving it lets it go after the usual moment. */
  releasePreview() {
    if (!this.preview) return;
    clearTimeout(this._previewTimer);
    this._previewTimer = setTimeout(() => this.dismissPreview(), PREVIEW_MS);
  },
  dismissPreview() {
    clearTimeout(this._previewTimer);
    this.preview = null;
  },
  tabState() {
    return tabState(typeof document === 'undefined' ? null : document, notificationApi(), this._lastInputAt);
  },
  /**
   * Follows the person's use of this tab: input marks it used, focus and blur report, and the tab
   * reports again when it turns idle, so the engine never thinks an abandoned tab is in use.
   */
  watchPresence(win, doc) {
    const input = (e) => this.noteInput(e && e.type === 'keydown');
    for (const type of INPUT_EVENTS) doc.addEventListener(type, input, { capture: true, passive: true });
    win.addEventListener('focus', () => this.presenceChanged());
    win.addEventListener('blur', () => this.presenceChanged());
  },
  /** An input: the tab is in use now; a tab that was idle says so at once. */
  noteInput(key = false, now = Date.now()) {
    this._lastInputAt = now;
    if (key) this._lastKeyAt = now;
    if (!this._reportedActive && isActive(this.tabState())) this.reportPresence();
    this.armIdle();
  },
  /** One timer, re-armed for the remainder, so a stream of mouse moves costs nothing. */
  armIdle() {
    if (this._idleTimer) return;
    const wait = Math.max(0, IDLE_MS - (Date.now() - this._lastInputAt)) + 50;
    this._idleTimer = setTimeout(() => {
      this._idleTimer = null;
      if (Date.now() - this._lastInputAt <= IDLE_MS) this.armIdle();
      else this.presenceChanged();
    }, wait);
  },
  /** Focus, visibility or idleness changed: report when it changes what the engine should do. */
  presenceChanged() {
    this.reportPresence();
  },
  /** Tells the engine how this tab stands; quiet when the stream has gone (the next one reports). */
  async reportPresence() {
    const stream = this._stream;
    if (!stream) return;
    const state = this.tabState();
    this.notifyPermission = state.permission;
    this._reportedActive = isActive(state);
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
  /** The bell wiggles once: something arrived while someone uses this tab. */
  pulseBell() {
    clearTimeout(this._pulseTimer);
    this.bellPulse = false;
    this.$nextTick(() => {
      this.bellPulse = true;
      this._pulseTimer = setTimeout(() => (this.bellPulse = false), PULSE_MS);
    });
  },
};
