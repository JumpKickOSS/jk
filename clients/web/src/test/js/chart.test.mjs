// SPDX-License-Identifier: Apache-2.0
// Headless tests for the Dependencies panel's state rules and the build spark's tooltip. The harness copies the web modules
// into a type:module temp dir (JK_APP_DIR) and imports chart.js's exported component options.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const { BuildBars, ModuleDepGraph } = await import(pathToFileURL(path.join(process.env.JK_APP_DIR, 'chart.js')));

/** A detached component instance: data + methods, no Vue. */
function vm(over = {}) {
  const v = Object.assign(Object.create(ModuleDepGraph.methods), ModuleDepGraph.data(), over);
  v.scheduleLoad = () => {};
  return v;
}

test('clearing the last scope re-checks main in the DOM as well as in state', () => {
  const v = vm({ selectedScopes: { main: true } });
  const box = { checked: false };
  v.toggleScope('main', { target: box });
  assert.equal(v.selectedScopes.main, true);
  assert.equal(box.checked, true, 'the DOM property is patched when the bound value did not change');
});

test('clearing a non-last scope leaves the others alone', () => {
  const v = vm({ selectedScopes: { main: true, runtime: true } });
  const box = { checked: false };
  v.toggleScope('runtime', { target: box });
  assert.deepEqual(v.selectedScopes, { main: true, runtime: false });
  assert.equal(box.checked, false);
});

test('a failed reload drops the previous graph with the error', () => {
  const v = vm({ graph: { nodes: [{ id: 'a' }], edges: [] }, loading: true });
  v.failLoad({ status: 500 });
  assert.equal(v.graph, null, 'the stale chart and its node count must not describe the new selection');
  assert.equal(v.loading, false);
  assert.equal(v.error, 'Failed to load graph (HTTP 500)');
  v.failLoad({ error: 'malformed jk.toml' });
  assert.equal(v.error, 'malformed jk.toml');
  v.failLoad({ status: 401 });
  assert.match(v.error, /Authorization required/);
});

test('the spark tooltip escapes every value it interpolates', () => {
  globalThis.document ??= { documentElement: {} };
  globalThis.getComputedStyle ??= () => ({ getPropertyValue: () => '' });
  let option;
  const spark = Object.assign(Object.create(BuildBars.methods), {
    builds: [{ buildNumber: '<b>7</b>', outcome: '<img src=x onerror=alert(1)>', millis: 1200 }],
    chart: { setOption: (o) => (option = o) },
  });
  spark.render();
  const tip = option.tooltip.formatter([{ dataIndex: 0 }]);
  assert.ok(!tip.includes('<'), tip);
  assert.match(tip, /^#&lt;b&gt;7&lt;\/b&gt; · &lt;img src=x onerror=alert\(1\)&gt; · /);
});
