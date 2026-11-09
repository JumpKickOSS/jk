// SPDX-License-Identifier: Apache-2.0
// The dashboard as an installed app: the manifest names it JumpKick over the whole dashboard, the
// page registers the worker so every load re-checks it, and "Install JumpKick" shows only once the
// browser offers it and never once installed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const DIR = process.env.JK_APP_DIR;
const { WORKER_URL, installData, installMethods, registerWorker, runsInstalled } = await import(
  pathToFileURL(path.join(DIR, 'pwa.js'))
);
const manifest = JSON.parse(fs.readFileSync(path.join(DIR, 'manifest.webmanifest'), 'utf8'));
const html = fs.readFileSync(path.join(DIR, 'index.html'), 'utf8');

/** A window: its listeners, and whether it runs as the installed app. */
function win(standalone = false) {
  const listeners = new Map();
  return {
    listeners,
    matchMedia: (q) => ({ matches: standalone && q === '(display-mode: standalone)' }),
    addEventListener: (type, fn) => listeners.set(type, fn),
  };
}

/** The root component's share, its methods bound. */
function root() {
  const vm = { ...installData() };
  for (const [k, fn] of Object.entries(installMethods)) vm[k] = fn.bind(vm);
  return vm;
}

/** The browser's offer to install. */
function offer(outcome) {
  return {
    prevented: 0,
    prompted: 0,
    preventDefault() {
      this.prevented++;
    },
    async prompt() {
      this.prompted++;
    },
    userChoice: Promise.resolve({ outcome }),
  };
}

test('the manifest installs the dashboard root as JumpKick in its own window', () => {
  assert.equal(manifest.name, 'JumpKick');
  assert.equal(manifest.short_name, 'JumpKick');
  assert.equal(manifest.display, 'standalone');
  assert.equal(manifest.start_url, '/');
  assert.equal(manifest.scope, '/');
  assert.equal(manifest.id, '/');
  const sizes = (purpose) => manifest.icons.filter((i) => i.purpose === purpose).map((i) => i.sizes);
  assert.deepEqual(sizes('any').filter((s) => s !== 'any'), ['192x192', '512x512']);
  assert.deepEqual(sizes('maskable'), ['512x512']);
  assert.match(html, /<link rel="manifest" href="\/manifest\.webmanifest">/);
  const theme = /<meta name="theme-color" content="([^"]+)">/.exec(html);
  assert.equal(theme && theme[1], manifest.theme_color, 'the page and the installed window agree on the colour');
});

test('the worker registers over the whole dashboard and is re-checked on every load', async () => {
  const calls = [];
  const messages = [];
  let listener;
  const container = {
    addEventListener: (type, fn) => type === 'message' && (listener = fn),
    async register(url, options) {
      calls.push({ url, options });
      return { scope: '/' };
    },
  };
  assert.deepEqual(await registerWorker(container, (m) => messages.push(m)), { scope: '/' });
  assert.deepEqual(calls, [{ url: WORKER_URL, options: { scope: '/', updateViaCache: 'none' } }]);
  assert.equal(WORKER_URL, '/sw.js');
  listener({ data: { type: 'open-decision', id: 'd1' } });
  assert.deepEqual(messages, [{ type: 'open-decision', id: 'd1' }]);
});

test('without service workers, or when registering fails, the page goes on without one', async () => {
  assert.equal(await registerWorker(undefined, () => {}), null);
  const refused = {
    addEventListener() {},
    async register() {
      throw new Error('insecure origin');
    },
  };
  assert.equal(await registerWorker(refused, () => {}), null);
});

test('"Install JumpKick" shows once the browser offers it, in place of its own banner', async () => {
  const w = win();
  const vm = root();
  vm.watchInstall(w);
  assert.equal(vm.installable, false, 'nothing on load');
  const o = offer('accepted');
  w.listeners.get('beforeinstallprompt')(o);
  assert.equal(o.prevented, 1);
  assert.equal(vm.installable, true);

  await vm.installApp();
  assert.equal(o.prompted, 1);
  assert.equal(vm.installable, false);
  assert.equal(vm.installed, true);
  await vm.installApp();
  assert.equal(o.prompted, 1, 'an offer prompts once');
});

test('a dismissed install hides the entry until the browser offers again', async () => {
  const w = win();
  const vm = root();
  vm.watchInstall(w);
  w.listeners.get('beforeinstallprompt')(offer('dismissed'));
  await vm.installApp();
  assert.equal(vm.installable, false);
  assert.equal(vm.installed, false);
  w.listeners.get('beforeinstallprompt')(offer('accepted'));
  assert.equal(vm.installable, true);
});

test('the installed app never offers to install itself', () => {
  assert.equal(runsInstalled(win(true)), true);
  assert.equal(runsInstalled(win(false)), false);
  assert.equal(runsInstalled(null), false);
  const w = win(true);
  const vm = root();
  vm.watchInstall(w);
  w.listeners.get('beforeinstallprompt')(offer('accepted'));
  assert.equal(vm.installable, false);

  const tab = win();
  const other = root();
  other.watchInstall(tab);
  tab.listeners.get('beforeinstallprompt')(offer('accepted'));
  tab.listeners.get('appinstalled')();
  assert.equal(other.installable, false, 'installed from the browser menu: the entry goes');
  assert.equal(other.installed, true);
});
