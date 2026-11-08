// SPDX-License-Identifier: Apache-2.0
// Headless Chrome over the DevTools protocol, for the console harnesses (paint.mjs, live.mjs): a
// throwaway profile, one page, `send` for protocol calls and `evaluate` for page expressions.
// Needs Node 22+ (the global WebSocket).

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Start `chrome` headless on a blank page. Resolves to `{ send, evaluate, exceptions, close }`;
 * `exceptions` collects the page's uncaught exceptions once `Runtime.enable` has been sent.
 */
export async function launch(chrome) {
  // No back/forward cache: a page it keeps holds its event stream open, and after a few loads those
  // streams fill Chrome's six connections per host, so a later load waits seconds for a socket.
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'jk-console-'));
  const browser = spawn(chrome, ['--headless=new', '--remote-debugging-port=0', '--no-first-run', '--no-default-browser-check',
    '--disable-extensions', '--disable-features=BackForwardCache', '--user-data-dir=' + profile, 'about:blank'], { stdio: 'ignore' });
  const close = async (ws) => {
    if (ws) ws.close();
    browser.kill();
    await sleep(200);
    fs.rmSync(profile, { recursive: true, force: true });
  };
  try {
    const port = await devtoolsPort(profile);
    const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    const page = targets.find((t) => t.type === 'page');
    const cdp = await connect(page.webSocketDebuggerUrl);
    return { ...cdp, close: () => close(cdp.ws) };
  } catch (e) {
    await close();
    throw e;
  }
}

async function devtoolsPort(profile) {
  const file = path.join(profile, 'DevToolsActivePort');
  for (let i = 0; i < 200; i++) {
    if (fs.existsSync(file)) {
      const port = fs.readFileSync(file, 'utf8').split('\n')[0].trim();
      if (port) return port;
    }
    await sleep(50);
  }
  throw new Error('chrome never wrote DevToolsActivePort');
}

function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  const exceptions = [];
  let id = 0;
  const pending = new Map();
  ws.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    if (msg.method === 'Runtime.exceptionThrown') exceptions.push(JSON.stringify(msg.params.exceptionDetails).slice(0, 600));
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  };
  const send = (method, params = {}) =>
    new Promise((resolve) => {
      const n = ++id;
      pending.set(n, resolve);
      ws.send(JSON.stringify({ id: n, method, params }));
    });
  /** The value of `expression` in the page, by value. */
  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true });
    return r.result && r.result.result ? r.result.result.value : undefined;
  };
  return new Promise((resolve, reject) => {
    ws.onopen = () => resolve({ ws, send, evaluate, exceptions });
    ws.onerror = reject;
  });
}
