// SPDX-License-Identifier: Apache-2.0
// The Machine page: each distro's state, pairing and last operation as the table reads them, which
// button a row offers, and that Install and Remove post the one operation with the distro's name.
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
  requests.push({ method, url: String(url), body: opts.body });
  const r = reply(method, String(url));
  return {
    ok: r.status < 300,
    status: r.status,
    headers: { get: () => 'application/json' },
    json: async () => r.body,
    text: async () => JSON.stringify(r.body),
    clone() {
      return this;
    },
  };
};

const web = path.resolve(import.meta.dirname, '../../main/resources/web');
const m = await import(pathToFileURL(path.join(web, 'machine.js')).href);

const installed = { name: 'Ubuntu-26.10', state: 'installed', jumpKick: 'jk 0.51.1', engine: { paired: true } };
const bare = { name: 'Debian', state: 'not-installed', jumpKick: null, engine: null };
const stopped = { name: 'Fedora', state: 'unknown', engine: null };
const legacy = { name: 'Old', state: 'wsl1', engine: null };
const working = { ...bare, operation: { action: 'install', state: 'running', step: 'checking Debian' } };

test('each state reads as a person reads it', () => {
  assert.equal(m.stateLabel(installed), 'jk 0.51.1');
  assert.equal(m.stateLabel(bare), 'No JumpKick');
  assert.equal(m.stateLabel(stopped), 'Stopped (not started to look)');
  assert.equal(m.stateLabel(legacy), 'WSL 1 (JumpKick needs WSL 2)');
  assert.equal(m.pairingLabel(installed), 'Paired');
  assert.equal(m.pairingLabel({ ...installed, engine: { paired: false } }), 'Not paired');
  assert.equal(m.pairingLabel({ ...installed, engine: null }), 'Engine not seen');
  assert.equal(m.pairingLabel(bare), '');
  assert.equal(m.operationLabel(working), 'Install running: checking Debian');
});

test('a row offers Remove where JumpKick is, Install elsewhere, and nothing while busy or on WSL 1', () => {
  assert.equal(m.rowAction(installed), 'remove');
  assert.equal(m.rowAction(bare), 'install');
  assert.equal(m.rowAction(stopped), 'install');
  assert.equal(m.rowAction(legacy), null);
  assert.equal(m.rowAction(working), null);
  assert.equal(m.busy(working), true);
});

test('install posts the distro by name and a refusal shows', async () => {
  const self = { view: 'machine', ...m.machineData(), ...m.machineMethods };
  reply = (method, url) =>
    method === 'POST'
      ? { status: 409, body: { accepted: [], refused: { Debian: 'already running install' } } }
      : { status: 200, body: { host: { version: '0.51.2' }, environments: [bare] } };
  await self.machineAction('Debian', 'install');
  const post = requests.find((r) => r.method === 'POST');
  assert.match(post.url, /\/api\/machine\/environments\/install$/);
  assert.deepEqual(JSON.parse(post.body), { distros: ['Debian'] });
  assert.deepEqual(self.machineError, { name: 'Debian', text: 'already running install' });
  assert.equal(self.machineAvailable, true);
  assert.equal(self.machine.environments[0].name, 'Debian');
});

test('an engine without the API has no Machine page, and nothing but install or remove is posted', async () => {
  const self = { view: 'console', ...m.machineData(), ...m.machineMethods };
  reply = () => ({ status: 404, body: {} });
  await self.loadMachine();
  assert.equal(self.machineAvailable, false);
  const before = requests.length;
  await self.machineAction('Debian', 'format-c');
  assert.equal(requests.length, before);
});
