// SPDX-License-Identifier: Apache-2.0
// The console live, as a supervisor sees it: headless Chrome holds the console open on one MCP
// session of a private engine while this script plays the agent over the engine's MCP endpoint.
//
//   event      each `find` call is one SSE `action` frame; the page marks it once painted
//              (`jk-console-event`, consoleview.js) with the engine's publish time (the call's end),
//              so a sample is publish → painted on the selected session's timeline
//   decision   each confirmed `disk clean` from a client that declared form elicitation opens a
//              decision; a sample is the decision's `createdAt` → its Needs-you row painted. The
//              script then declines the elicitation, so nothing is cleaned and the next call asks
//              anew
//   checklist  with a decision open, fresh loads of `#console/<session>`, each timing when the
//              answer to every question of the supervisor checklist is on screen, in ms since
//              navigation start:
//                goal      what is the agent trying to do      the episode title, the agent's goal
//                progress  is it getting there                 the outcome badge and its run's chip
//                changed   what did it change                  "this session changed": files
//                needs     does it need me                     the Needs-you row with the question
//                last      what did it just do                 the timeline's last line, its last call
//
//   node live.mjs <chrome> <dashboard-url-with-#t=token> <project-dir> [events] [decisions] [passes]
//
// Prints `{ "event": [ms, …], "decision": [ms, …], "checklist": [{ goal, progress, … }, …] }`.

import fs from 'node:fs';
import path from 'node:path';
import { launch, sleep } from './cdp.mjs';

const [chrome, url, dir, eventsArg, decisionsArg, passesArg] = process.argv.slice(2);
if (!chrome || !url || !dir) {
  console.error('usage: node live.mjs <chrome> <dashboard-url> <project-dir> [events] [decisions] [passes]');
  process.exit(2);
}
const EVENTS = Number(eventsArg || 20);
const DECISIONS = Number(decisionsArg || 5);
const PASSES = Number(passesArg || 5);
const GOAL = 'Make the scoreboard console green';
const CLIENT = 'scoreboard';
const QUESTION = "Clean jk's cache (disk clean)?";

const origin = new URL(url).origin;
const token = (/[#&?]t=([^&]+)/.exec(new URL(url).hash) || [])[1] || '';
const auth = { Authorization: 'Bearer ' + token };

// ---- the agent: a Streamable HTTP MCP client ----

const REVISION = '2025-11-25';
let mcpSession = null;
let rpcId = 0;

/** POST one JSON-RPC message to /mcp; the response as `{ messages }`, a JSON body or an event stream read to its end. */
async function post(message, { onMessage } = {}) {
  const headers = { ...auth, 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' };
  if (mcpSession) Object.assign(headers, { 'Mcp-Session-Id': mcpSession, 'MCP-Protocol-Version': REVISION });
  const resp = await fetch(origin + '/mcp', { method: 'POST', headers, body: JSON.stringify(message) });
  if (!mcpSession && resp.headers.get('mcp-session-id')) mcpSession = resp.headers.get('mcp-session-id');
  if (!resp.ok && resp.status !== 202) throw new Error(`POST /mcp ${message.method || 'response'}: HTTP ${resp.status} ${await resp.text()}`);
  const type = resp.headers.get('content-type') || '';
  if (!type.includes('text/event-stream')) {
    const text = await resp.text();
    return text ? [JSON.parse(text)] : [];
  }
  const messages = [];
  const decoder = new TextDecoder();
  let buf = '';
  for await (const chunk of resp.body) {
    buf += decoder.decode(chunk, { stream: true });
    let cut;
    while ((cut = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, cut);
      buf = buf.slice(cut + 2);
      const data = frame.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim()).join('\n');
      if (!data) continue;
      const msg = JSON.parse(data);
      messages.push(msg);
      if (onMessage) await onMessage(msg);
    }
  }
  return messages;
}

/** Call tool `name`; resolves to its result. `onRequest(msg)` answers what the engine asks meanwhile. */
async function call(name, args, onRequest) {
  const id = ++rpcId;
  const messages = await post(
    { jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: args } },
    { onMessage: async (msg) => msg.method && msg.id != null && onRequest && onRequest(msg) },
  );
  const answer = messages.find((m) => m.id === id && !m.method);
  if (!answer) throw new Error(`${name}: no answer in ${JSON.stringify(messages).slice(0, 400)}`);
  return answer;
}

async function initialize() {
  const [init] = await post({
    jsonrpc: '2.0',
    id: ++rpcId,
    method: 'initialize',
    params: { protocolVersion: REVISION, capabilities: { elicitation: { form: {} } }, clientInfo: { name: CLIENT, version: '1' } },
  });
  if (!init || init.error) throw new Error('initialize: ' + JSON.stringify(init));
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' });
}

/** GET an /api path, with the engine generation every read but `/api/status` must carry. */
async function api(p) {
  const { engineEpoch } = await (await fetch(origin + '/api/status', { headers: auth })).json();
  const resp = await fetch(origin + p, { headers: { ...auth, 'X-Jk-Engine-Epoch': engineEpoch } });
  if (!resp.ok) throw new Error(`${p}: HTTP ${resp.status}`);
  return resp.json();
}

/** The engine's open requests: a closed page's event stream counts until the engine next writes to it. */
async function activeRequests() {
  try {
    return (await (await fetch(origin + '/api/status', { headers: auth })).json()).activeRequests ?? 0;
  } catch {
    return Infinity;
  }
}

/** Wait (at most 10 s) until the previous page's streams are gone, as paint.mjs does between loads. */
async function engineSettled(baseline) {
  for (let i = 0; i < 100 && (await activeRequests()) > baseline; i++) await sleep(100);
}

// ---- the page ----

const cdp = await launch(chrome);

/** The page's `jk-console-event` marks so far. */
const marks = () => cdp.evaluate(`performance.getEntriesByName('jk-console-event').map((e) => e.detail)`);

/** The first mark after `seen` marks that `match`es and is on screen; its latency in ms. */
async function nextMark(seen, match, what) {
  for (let i = 0; i < 1000; i++) {
    const all = (await marks()) || [];
    const hit = all.slice(seen).find((m) => match(m));
    if (hit) {
      if (!hit.shown) throw new Error(`${what} was painted but is not on the console: ${JSON.stringify(hit)}`);
      return hit.latencyMs;
    }
    await sleep(10);
  }
  throw new Error(`${what} never reached the console; marks: ${JSON.stringify(await marks()).slice(-600)} ${cdp.exceptions.join(' | ')}`);
}

/**
 * A confirmed `disk clean`, which opens a decision. Resolves once the decision is open, with
 * `decline()`, which answers the elicitation no and resolves when the call has answered.
 */
async function openDecision() {
  let answer;
  const asked = new Promise((resolve) => (answer = resolve));
  const done = call('disk', { action: 'clean', confirm: true }, (msg) => {
    if (msg.method !== 'elicitation/create') return;
    return new Promise((declined) =>
      answer(async () => {
        await post({ jsonrpc: '2.0', id: msg.id, result: { action: 'decline' } });
        declined();
      }),
    );
  });
  const decline = await Promise.race([asked, done.then((r) => Promise.reject(new Error('disk answered without asking: ' + JSON.stringify(r).slice(0, 400))))]);
  return async () => {
    await decline();
    const r = await done;
    const text = JSON.stringify(r);
    if (!text.includes('DECLINED')) throw new Error('the declined decision did not stop the call: ' + text.slice(0, 400));
  };
}

const CHECKS = {
  goal: `(() => { const t = document.querySelector('.episode .ep-title'); return !!t && t.textContent.trim() === ${JSON.stringify(GOAL)} && !!t.getClientRects().length; })()`,
  progress: `(() => { const b = document.querySelector('.episode .ep-head .badge'); const c = document.querySelector('.episode .tl .run-chip'); return !!b && !!b.textContent.trim() && !!c && !!c.getClientRects().length; })()`,
  changed: `(() => { const p = [...document.querySelectorAll('.rail-right .panel')].find((x) => /this session changed/.test(x.textContent)); const dd = p && p.querySelector('dd'); return !!dd && /^\\d+$/.test(dd.textContent.trim()) && !!dd.getClientRects().length; })()`,
  needs: `(() => [...document.querySelectorAll('.needs-you .needs-q')].some((q) => q.textContent.includes(${JSON.stringify(QUESTION)}) && q.getClientRects().length))()`,
  last: `(() => { const li = [...document.querySelectorAll('.episode > .tl > li')].pop(); return !!li && /find ${'C'}\\d+/.test(li.textContent) && !!li.getClientRects().length; })()`,
};

/** Runs on every new document: each frame, the first time (ms since navigation start) each answer is on screen. */
const CHECKER = `(() => {
  const checks = { ${Object.entries(CHECKS).map(([k, v]) => `${k}: () => ${v}`).join(', ')} };
  const found = (window.__jkChecklist = {});
  const tick = () => {
    for (const [k, f] of Object.entries(checks)) {
      if (found[k] == null) { try { if (f()) found[k] = Math.round(performance.now()); } catch {} }
    }
    if (Object.keys(found).length < Object.keys(checks).length && performance.now() < 15000) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
})();`;

let closing = null;
try {
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');

  // The agent's session: an edit, a goal and a green build, then a graph call.
  await initialize();
  const src = path.join(dir, 'src', 'main', 'java', 'demo', 'C0.java');
  fs.appendFileSync(src, '// edited by the console bench\n');
  const built = await call('run', { kind: 'build', dir, goal: GOAL });
  if (JSON.stringify(built).includes('"isError":true')) throw new Error('run: ' + JSON.stringify(built).slice(0, 600));
  await call('find', { name: 'C0', dir }); // warm: the first graph call may build the graph
  const { sessions } = await api('/api/sessions?limit=50');
  const session = sessions.find((s) => s.client === CLIENT && s.state !== 'ended');
  if (!session) throw new Error('no session for the MCP client: ' + JSON.stringify(sessions).slice(0, 600));

  // The console on that session.
  const consoleUrl = `${origin}/#console/${encodeURIComponent(session.id)}?t=${token}`;
  await cdp.send('Page.navigate', { url: consoleUrl });
  for (let i = 0; !(await cdp.evaluate(CHECKS.goal)); i++) {
    if (i > 500) throw new Error('the console never showed the session: ' + cdp.exceptions.join(' | '));
    await sleep(20);
  }
  await sleep(500);

  const event = [];
  for (let i = 0; i < EVENTS + 1; i++) {
    const seen = ((await marks()) || []).length;
    await call('find', { name: 'C' + (i % 40), dir });
    const ms = await nextMark(seen, (m) => m.kind === 'action', 'action ' + i);
    if (i > 0) event.push(ms); // the first is a warm-up
    await sleep(100);
  }

  const decision = [];
  for (let i = 0; i < DECISIONS + 1; i++) {
    const seen = ((await marks()) || []).length;
    const decline = await openDecision();
    const ms = await nextMark(seen, (m) => m.kind === 'decision', 'decision ' + i);
    if (i > 0) decision.push(ms);
    await decline();
    await sleep(200);
  }

  // The checklist, with one decision open throughout.
  closing = await openDecision();
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: CHECKER });
  const checklist = [];
  await cdp.send('Page.navigate', { url: 'about:blank' });
  await sleep(300);
  const baseline = await activeRequests();
  for (let i = 0; i < PASSES + 1; i++) {
    await cdp.send('Page.navigate', { url: 'about:blank' });
    await engineSettled(baseline);
    await cdp.send('Page.navigate', { url: consoleUrl });
    let found = {};
    for (let n = 0; n < 640; n++) {
      found = (await cdp.evaluate('window.__jkChecklist || {}')) || {};
      if (Object.keys(found).length === Object.keys(CHECKS).length) break;
      await sleep(25);
    }
    const missing = Object.keys(CHECKS).filter((k) => found[k] == null);
    if (missing.length) throw new Error(`checklist pass ${i}: never on screen: ${missing.join(', ')} ${cdp.exceptions.join(' | ')}`);
    if (i > 0) checklist.push(found);
  }
  await closing();
  closing = null;
  console.log(JSON.stringify({ event, decision, checklist }));
} finally {
  if (closing) await closing().catch(() => {});
  if (mcpSession) await fetch(origin + '/mcp', { method: 'DELETE', headers: { ...auth, 'Mcp-Session-Id': mcpSession } }).catch(() => {});
  await cdp.close();
}
