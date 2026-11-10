// SPDX-License-Identifier: Apache-2.0
// The header's Update pill: shown once the engine has downloaded a newer JumpKick and nobody has
// said "not now" to it (on any surface). Update Now installs it at once: the new engine displaces
// this one, running builds finish on the old engine, and the epoch gate reloads the page onto the
// new one. The × is "not now" until the next check. The state is read on load, every five minutes
// and whenever the tab comes back; an update is downloaded at most twice a day, so that is soon
// enough, and a new engine reloads the page anyway.
import { get, post } from './api.js';

export const SELF_UPDATE_POLL_MS = 5 * 60 * 1000;

export function selfUpdateData() {
  return {
    // GET /api/self-update: { running, offer, installing }; null until read or when unavailable.
    selfUpdate: null,
  };
}

/** What the pill shows, or null when there is nothing on offer. */
export function updatePill(state) {
  if (!state || !state.offer) return null;
  return {
    version: state.offer,
    busy: !!state.installing,
    label: state.installing ? 'Updating…' : 'Update Now',
    tip: state.installing
      ? `Installing JumpKick ${state.offer}; this page reloads when it is done`
      : `JumpKick ${state.offer} is downloaded and ready to install`,
  };
}

export const selfUpdateMethods = {
  async loadSelfUpdate() {
    try {
      this.selfUpdate = await get('/api/self-update');
    } catch {
      // An engine without the API, or one going away: no pill.
    }
  },
  watchSelfUpdate(win, doc) {
    this.loadSelfUpdate();
    win.setInterval(() => {
      if (!doc.hidden) this.loadSelfUpdate();
    }, SELF_UPDATE_POLL_MS);
    doc.addEventListener('visibilitychange', () => {
      if (!doc.hidden) this.loadSelfUpdate();
    });
  },
  async installUpdate() {
    const state = this.selfUpdate;
    if (!state || !state.offer || state.installing) return;
    this.selfUpdate = { ...state, installing: true };
    try {
      this.selfUpdate = await post('/api/self-update/install', {});
    } catch {
      // The engine may already be handing over; if not, the next read puts the pill right.
      this.loadSelfUpdate();
    }
  },
  async snoozeUpdate() {
    const state = this.selfUpdate;
    if (!state || !state.offer) return;
    this.selfUpdate = { ...state, offer: null };
    try {
      this.selfUpdate = await post('/api/self-update/snooze', {});
    } catch {
      this.selfUpdate = state;
    }
  },
};
