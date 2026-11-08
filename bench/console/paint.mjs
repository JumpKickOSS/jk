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

import { launch, sleep } from './cdp.mjs';

const [chrome, url, runsArg] = process.argv.slice(2);
if (!chrome || !url) {
  console.error('usage: node paint.mjs <chrome> <dashboard-url> [runs]');
  process.exit(2);
}
const runs = Number(runsArg || 10);

async function paintOnce(cdp) {
  await cdp.send('Page.navigate', { url: 'about:blank' });
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
  throw new Error('the console never marked its first paint: ' + JSON.stringify(why.result) + ' ' + cdp.exceptions.join(' | '));
}

const cdp = await launch(chrome);
try {
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await paintOnce(cdp); // warm-up: CDN scripts into the HTTP cache
  const samples = [];
  for (let i = 0; i < runs; i++) {
    samples.push(await paintOnce(cdp));
    process.stderr.write(samples.length + ' ');
  }
  console.log(JSON.stringify({ samples }));
} finally {
  await cdp.close();
}
