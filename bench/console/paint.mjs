// SPDX-License-Identifier: Apache-2.0
// The console's first meaningful paint, as a browser sees it: headless Chrome over the DevTools
// protocol loads the dashboard and reads the mark the page sets once the sessions rail and the
// selected episode are on screen (`<html data-console-painted-ms>`, milliseconds since navigation
// start). One warm-up load fills the browser's HTTP cache (the pinned CDN scripts); the timed loads
// follow, each from a blank page.
//
//   node paint.mjs <chrome> <dashboard-url-with-#t=token> [runs]
//
// Prints `{ "samples": [ms, …] }`. Needs Node 22+ (the global WebSocket).

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const [chrome, url, runsArg] = process.argv.slice(2);
if (!chrome || !url) {
  console.error('usage: node paint.mjs <chrome> <dashboard-url> [runs]');
  process.exit(2);
}
const runs = Number(runsArg || 10);
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'jk-paint-'));
const browser = spawn(chrome, ['--headless=new', '--remote-debugging-port=0', '--no-first-run', '--no-default-browser-check',
  '--disable-extensions', '--user-data-dir=' + profile, 'about:blank'], { stdio: 'ignore' });

const exceptions = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function devtoolsPort() {
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
  return new Promise((resolve, reject) => {
    ws.onopen = () => resolve({ send, close: () => ws.close() });
    ws.onerror = reject;
  });
}

const origin = new URL(url).origin;
const token = (/[#&?]t=([^&]+)/.exec(new URL(url).hash) || [])[1] || '';

/**
 * Wait until the engine has let go of the previous page's event stream: a stream outlives its page
 * until the engine next writes to it, and a load timed while it lingers measures the engine's
 * connection pool rather than the console.
 */
async function activeRequests() {
  try {
    const r = await fetch(origin + '/api/status', { headers: { Authorization: 'Bearer ' + token } });
    return (await r.json()).activeRequests ?? 0;
  } catch {
    return Infinity;
  }
}

/** The engine's open requests with no page of ours loaded: other tabs' streams count in it. */
let baseline = 0;

async function engineSettled() {
  for (let i = 0; i < 100 && (await activeRequests()) > baseline; i++) await sleep(100);
}

async function paintOnce(cdp) {
  await cdp.send('Page.navigate', { url: 'about:blank' });
  await engineSettled();
  await cdp.send('Page.navigate', { url });
  for (let i = 0; i < 300; i++) {
    const r = await cdp.send('Runtime.evaluate', { expression: 'document.documentElement.dataset.consolePaintedMs || ""', returnByValue: true });
    const v = r.result && r.result.result && r.result.result.value;
    if (v) return Number(v);
    await sleep(50);
  }
  const why = await cdp.send('Runtime.evaluate', {
    expression: 'JSON.stringify((() => { const p = document.querySelector("#app").__vue_app__._container._vnode.component.proxy; return { sel: p.selectedSessionId, auto: p.consoleAutoSession, detail: p.sessionDetail && p.sessionDetail.session.id, view: p.view, conn: p.connection, loaded: p.sessionsLoaded, inflight: Object.keys(p._inflight), n: p.sessions.length, res: performance.getEntriesByType("resource").filter((e) => e.name.includes("/api/")).map((e) => e.name.split("/api/")[1].slice(0, 40) + "@" + Math.round(e.responseEnd)) }; })())',
    returnByValue: true,
  });
  throw new Error('the console never marked its first paint: ' + JSON.stringify(why.result) + ' ' + exceptions.join(' | '));
}

try {
  const port = await devtoolsPort();
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const page = targets.find((t) => t.type === 'page');
  const cdp = await connect(page.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  baseline = await activeRequests();
  await paintOnce(cdp); // warm-up: CDN scripts into the HTTP cache
  const samples = [];
  for (let i = 0; i < runs; i++) {
    samples.push(await paintOnce(cdp));
    process.stderr.write(samples.length + ' ');
  }
  cdp.close();
  console.log(JSON.stringify({ samples }));
} finally {
  browser.kill();
  await sleep(200);
  fs.rmSync(profile, { recursive: true, force: true });
}
