// SPDX-License-Identifier: Apache-2.0
// The dashboard as an installed app: the page registers the service worker (sw.js), which makes it
// installable and shows its notifications, and the Help dialog offers "Install JumpKick" once the
// browser says it may, never a prompt on load. The manifest is manifest.webmanifest.

/** The worker's script, at the root so its scope is the whole dashboard. */
export const WORKER_URL = '/sw.js';

/**
 * Registers the worker over the whole dashboard and hands each message it posts to `onMessage`.
 * `updateViaCache: 'none'` makes every load ask the engine for the worker, so an upgraded engine's
 * worker installs on the next load. Null where there are no service workers (an insecure origin, an
 * old browser) or registration failed: notifications then come from the page.
 */
export async function registerWorker(container, onMessage) {
  if (!container || typeof container.register !== 'function') return null;
  container.addEventListener('message', (event) => onMessage(event.data));
  try {
    return await container.register(WORKER_URL, { scope: '/', updateViaCache: 'none' });
  } catch {
    return null;
  }
}

/** Whether this page runs in the installed app's own window. */
export function runsInstalled(win) {
  return !!win && typeof win.matchMedia === 'function' && win.matchMedia('(display-mode: standalone)').matches;
}

/** The root component's share. */
export function installData() {
  return {
    installable: false, // the browser offered to install, and the Help dialog shows the entry
    installed: false,
  };
}

export const installMethods = {
  /**
   * Listens for the browser's offer to install, kept for the Help dialog's entry instead of the
   * browser's own banner, and for the install itself, which hides the entry.
   */
  watchInstall(win) {
    this.installed = runsInstalled(win);
    win.addEventListener('beforeinstallprompt', (event) => {
      event.preventDefault();
      this._installPrompt = event;
      this.installable = !this.installed;
    });
    win.addEventListener('appinstalled', () => {
      this._installPrompt = null;
      this.installable = false;
      this.installed = true;
    });
  },
  /** The entry's click: the browser's install dialog, which may be used once. */
  async installApp() {
    const offer = this._installPrompt;
    if (!offer) return;
    this._installPrompt = null;
    this.installable = false;
    await offer.prompt();
    const choice = await offer.userChoice;
    if (choice && choice.outcome === 'accepted') this.installed = true;
  },
};
