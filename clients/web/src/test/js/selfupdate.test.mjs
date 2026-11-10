// SPDX-License-Identifier: Apache-2.0
// The header's Update pill: shown only for a downloaded release on offer, Update Now installs it,
// and the × says "not now".
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const storage = () => {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
  };
};
globalThis.sessionStorage = storage();
globalThis.localStorage = storage();
globalThis.location = { hash: '', pathname: '/', search: '', hostname: '127.0.0.1' };

const requests = [];
let reply = () => ({ status: 200, body: {} });
globalThis.fetch = async (url, opts = {}) => {
  const method = opts.method || 'GET';
  requests.push({ method, url: String(url) });
  const r = reply(method, String(url));
  return {
    ok: r.status < 300,
    status: r.status,
    headers: { get: () => 'application/json' },
    json: async () => r.body,
    text: async () => JSON.stringify(r.body),
  };
};

const web = path.resolve(import.meta.dirname, '../../main/resources/web');
const { updatePill, selfUpdateMethods } = await import(pathToFileURL(path.join(web, 'selfupdate.js')).href);

test('no offer, no pill', () => {
  assert.equal(updatePill(null), null);
  assert.equal(updatePill({ running: '0.51.0', offer: null, installing: false }), null);
});

test('a downloaded release shows Update Now, and Updating… while it installs', () => {
  assert.deepEqual(updatePill({ running: '0.51.0', offer: '0.52.0', installing: false }), {
    version: '0.52.0',
    busy: false,
    label: 'Update Now',
    tip: 'JumpKick 0.52.0 is downloaded and ready to install',
  });
  assert.equal(updatePill({ running: '0.51.0', offer: '0.52.0', installing: true }).label, 'Updating…');
});

test('Update Now posts the install once and shows it as under way', async () => {
  requests.length = 0;
  reply = (method, url) =>
    method === 'POST' && url.endsWith('/api/self-update/install')
      ? { status: 202, body: { running: '0.51.0', offer: '0.52.0', installing: true } }
      : { status: 200, body: {} };
  const vm = { selfUpdate: { running: '0.51.0', offer: '0.52.0', installing: false }, ...selfUpdateMethods };
  await vm.installUpdate();
  assert.equal(vm.selfUpdate.installing, true);
  await vm.installUpdate(); // a second click while installing sends nothing
  assert.deepEqual(
    requests.filter((r) => r.method === 'POST').map((r) => r.url.replace(/^.*\/api/, '/api')),
    ['/api/self-update/install'],
  );
});

test('the × snoozes and hides the pill at once', async () => {
  requests.length = 0;
  reply = () => ({ status: 200, body: { running: '0.51.0', offer: null, installing: false } });
  const vm = { selfUpdate: { running: '0.51.0', offer: '0.52.0', installing: false }, ...selfUpdateMethods };
  const pending = vm.snoozeUpdate();
  assert.equal(updatePill(vm.selfUpdate), null);
  await pending;
  assert.equal(updatePill(vm.selfUpdate), null);
  assert.ok(requests.some((r) => r.method === 'POST' && r.url.endsWith('/api/self-update/snooze')));
});
