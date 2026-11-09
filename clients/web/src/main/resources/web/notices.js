// SPDX-License-Identifier: Apache-2.0
// The bell's number: what you haven't seen. The engine keeps the machine's recent notices and one
// "seen up to" marker (GET /api/notices, the SSE `notice` frame), so every tab, the installed app and
// a notification's click agree, and clearing the count in one tab clears it in all of them.
//
// Opening the bell, showing a decision's dialog, or clicking a notification (which shows the dialog)
// moves the marker to the newest notice. Seen is not resolved: a decision seen but not answered no
// longer counts, but the bell stays amber while any decision waits, listed under "Waiting on you".
// The menu's "Just now" is the recent notices; older history is the activity log's.

import { get, post } from './api.js';
import { isOpen } from './needs.js';
import { SSE } from './wire.js';

/** How many recent notices "Just now" lists. */
export const JUST_NOW = 8;

/** The newest notice's time, 0 when there is none. */
export function newestAt(notices) {
  return (notices || []).reduce((at, n) => Math.max(at, Number(n.at) || 0), 0);
}

/** How many of `notices` are newer than `seenUpTo`. */
export function unseenOf(notices, seenUpTo) {
  return (notices || []).filter((n) => Number(n.at) > seenUpTo).length;
}

/**
 * Folds one SSE `notice` frame (`{ change: 'added' | 'seen', notice?, seenUpTo, unseen }`) into
 * `{ notices, seenUpTo, unseen }`, returning the new state: an added notice goes first (once), and
 * the marker and count are the engine's.
 */
export function foldNotice(state, frame) {
  if (!frame || typeof frame.seenUpTo !== 'number') return state;
  let notices = state.notices || [];
  if (frame.change === 'added' && frame.notice && frame.notice.id) {
    notices = [frame.notice, ...notices.filter((n) => n.id !== frame.notice.id)];
  }
  const unseen = typeof frame.unseen === 'number' ? frame.unseen : unseenOf(notices, frame.seenUpTo);
  return { notices, seenUpTo: Math.max(state.seenUpTo || 0, frame.seenUpTo), unseen };
}

/**
 * The menu's "Just now": recent notices, newest first, leaving out decisions still waiting (they are
 * under "Waiting on you") and capped at `limit`.
 */
export function justNow(notices, decisions, limit = JUST_NOW) {
  const waiting = new Set((decisions || []).filter(isOpen).map((d) => d.id));
  return (notices || [])
    .filter((n) => !(n.decision && waiting.has(n.decision)))
    .slice()
    .sort((a, b) => b.at - a.at)
    .slice(0, limit);
}

/**
 * How the bell looks: `count` is the unseen number ('' for none), `amber` while a decision waits,
 * and `label` what a screen reader hears.
 */
export function bellLook(unseen, waiting) {
  const parts = [];
  if (unseen) parts.push(unseen + ' new');
  if (waiting) parts.push(waiting + (waiting === 1 ? ' decision waits' : ' decisions wait') + ' on you');
  return { count: unseen > 0 ? String(unseen) : '', amber: waiting > 0, label: parts.length ? 'Notifications: ' + parts.join(', ') : 'Notifications' };
}

/**
 * Loads the notices with `get`, tolerating an engine without them (404): an empty bell.
 */
export async function fetchNotices(getter) {
  try {
    const body = await getter('/api/notices');
    const notices = (body && body.notices) || [];
    const seenUpTo = Number(body && body.seenUpTo) || 0;
    return { notices, seenUpTo, unseen: typeof body.unseen === 'number' ? body.unseen : unseenOf(notices, seenUpTo) };
  } catch (e) {
    if (e && (e.status === 404 || e.status === 405)) return { notices: [], seenUpTo: 0, unseen: 0 };
    throw e;
  }
}

/** The root component's share. */
export function noticeData() {
  return { notices: [], seenUpTo: 0, unseen: 0 };
}

export const noticeMethods = {
  async loadNotices() {
    if (this.authModal) return;
    try {
      Object.assign(this, await fetchNotices(get));
      // A tab opened on a decision's dialog (a notification's click) has seen it.
      if (this.decisionId) this.markSeen();
    } catch (e) {
      if (this.handleHttpError) this.handleHttpError(e);
    }
  },
  /**
   * The `notice` frame, handled here (true); an added notice previews in a tab someone uses. A
   * decision this tab opens by itself, or is showing already, is seen as it arrives: the count
   * never rises only to fall again a moment later.
   */
  noticeEvent(event) {
    if (event.type !== SSE.notice) return false;
    const frame = event.data;
    const before = this.unseen;
    Object.assign(this, foldNotice(this, frame));
    if (frame && frame.change === 'added' && frame.notice) {
      const id = frame.notice.decision;
      const shownHere = id && (id === this.decisionId || (this.willAutoOpen && this.willAutoOpen()));
      if (shownHere) {
        this.unseen = before;
        this.markSeen();
      } else this.noticeArrived(frame.notice);
    }
    return true;
  },
  /**
   * A notice arrived. Decisions preview from their own frame; anything else previews here when
   * someone uses this tab.
   */
  noticeArrived(notice) {
    if (notice.kind === 'decision') return;
    if (this.isActiveTab && this.isActiveTab()) {
      this.deliverInApp({ id: notice.id, text: notice.text, href: notice.href, action: 'View' });
    }
  },
  /**
   * Everything up to the newest notice is seen: the count clears here at once and, through the
   * engine's `notice` frame, in every other tab.
   */
  async markSeen() {
    const upTo = newestAt(this.notices);
    if (!upTo || upTo <= this.seenUpTo) {
      this.unseen = unseenOf(this.notices, this.seenUpTo);
      return;
    }
    this.seenUpTo = upTo;
    this.unseen = 0;
    try {
      const after = await post('/api/notices/seen', { upTo });
      if (after && typeof after.unseen === 'number') this.unseen = after.unseen;
    } catch {
      // The engine went away; its next frame or load puts the count right.
    }
  },
};
