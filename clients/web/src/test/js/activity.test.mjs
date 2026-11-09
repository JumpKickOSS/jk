// SPDX-License-Identifier: Apache-2.0
// The Activity page: one history, newest first, paged behind the engine's cursor; its filters ride
// the route so Back undoes them; each row links to its decision, its project or itself; opening the
// page clears the bell's unseen count.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const storage = () => {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k) };
};
globalThis.sessionStorage = storage();
globalThis.localStorage = storage();
globalThis.location = { hash: '', hostname: '127.0.0.1', pathname: '/', search: '' };
globalThis.history = { replaceState: (_s, _t, url) => (globalThis.location.hash = url) };
globalThis.document = { visibilityState: 'visible', hidden: false, addEventListener() {}, removeEventListener() {} };

const calls = [];
let pages = {};
globalThis.fetch = async (url, opts = {}) => {
  calls.push({ url, method: opts.method || 'GET' });
  const key = Object.keys(pages).find((k) => url.includes(k));
  const body = key ? pages[key] : { entries: [], next: null };
  return { ok: true, status: 200, json: async () => body, clone() { return this; } };
};

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const {
  activityData,
  activityFilters,
  activityMethods,
  activityRow,
  activityUrl,
  buildActivityHash,
  channelLabel,
  diffLines,
  mergePage,
  projectFor,
} = await spa('activity.js');
const { routeFromHash } = await spa('route.js');

beforeEach(() => {
  calls.length = 0;
  pages = {};
  globalThis.location.hash = '';
});

const decision = {
  kind: 'decision',
  id: 'decision:d1',
  at: 3000,
  title: "Clean JumpKick's cache?",
  who: 'claude-code 3f9a',
  surface: 'cli',
  channel: 'native',
  project: '/w/app/server',
  session: 's1',
  href: '#decision/d1',
  decision: 'd1',
  outcome: 'accepted',
  detail: ['frees 2 GiB'],
};
const change = {
  kind: 'change',
  id: 'change:c1',
  at: 2000,
  title: 'Added repository corp',
  who: 'import of /w/app/pom.xml by jk import',
  surface: 'import',
  project: '/w/app/pom.xml',
  href: '#activity/change:c1',
  diff: '--- config.toml\n+++ config.toml\n@@ -1 +1,2 @@\n a\n+[repositories.corp]\n-b\n',
};

test('the route carries the filters and the opened entry, and only known filters count', () => {
  const r = routeFromHash('#activity/change%3Ac1?kind=change&project=%2Fw%2Fapp&junk=1');
  assert.equal(r.view, 'activity');
  assert.equal(r.activityEntry, 'change:c1');
  assert.deepEqual(activityFilters(r.activityFilter), { kind: 'change', project: '/w/app' });
  assert.equal(routeFromHash('#activity').activityEntry, null);
  assert.equal(buildActivityHash({ kind: 'setup', session: '' }, 'decision:d1'), '#activity/decision:d1?kind=setup');
  assert.equal(buildActivityHash(), '#activity');
});

test('a page asks with the filters and the cursor, and pages merge newest first without repeats', () => {
  assert.equal(activityUrl({ kind: 'decision', surface: 'cli' }, '2000:change:c1', 50), '/api/activity?kind=decision&surface=cli&before=2000%3Achange%3Ac1&limit=50');
  const merged = mergePage([decision], { entries: [change, decision] });
  assert.deepEqual(merged.map((e) => e.id), ['decision:d1', 'change:c1']);
});

test('a row says who, where it was answered and how it reached the person, and links to the decision and the project', () => {
  const rows = [
    { dir: '/w/app', projectId: 'app' },
    { dir: '/w/app/server', projectId: 'server' },
  ];
  const r = activityRow(decision, rows);
  assert.equal(r.meta, 'claude-code 3f9a · answered in Terminal · desktop notification');
  assert.equal(r.outcome, 'accepted');
  assert.equal(r.href, '#decision/d1');
  assert.equal(r.project, '#project/server', 'the deepest project holding the path');
  const c = activityRow(change, rows);
  assert.equal(c.meta, 'import of /w/app/pom.xml by jk import · from Import');
  assert.equal(c.project, '#project/app');
  assert.equal(c.href, c.self, 'a change links to itself, which opens its diff');
  assert.equal(projectFor('C:\\w\\app\\pom.xml', [{ dir: 'C:\\w\\app', projectId: 'win' }]).projectId, 'win');
  assert.equal(projectFor('/elsewhere', rows), null);
  assert.equal(channelLabel('in-app'), 'in the dashboard');
});

test('a diff renders as classed lines', () => {
  assert.deepEqual(diffLines(change.diff).map((l) => l.cls), ['diff-file', 'diff-file', 'diff-hunk', '', 'diff-add', 'diff-del']);
});

function page(over = {}) {
  const vm = { ...activityData(over), projectHistory: [], seenCalls: 0, markSeen() { this.seenCalls++; } };
  for (const [k, f] of Object.entries(activityMethods)) vm[k] = f.bind(vm);
  return vm;
}

test('opening the page clears the unseen count and loads the first page with its facets', async () => {
  pages = { '/api/activity?limit': { entries: [decision], next: '3000:decision:d1', facets: { kinds: ['decision'], projects: [], sessions: [], surfaces: ['cli'] } } };
  const vm = page();
  await vm.loadActivity();
  assert.equal(vm.seenCalls, 1);
  assert.deepEqual(vm.activity.map((e) => e.id), ['decision:d1']);
  assert.equal(vm.activityNext, '3000:decision:d1');
  assert.deepEqual(vm.activityFacets.surfaces, ['cli']);

  pages['before=3000'] = { entries: [change], next: null };
  await vm.moreActivity();
  assert.deepEqual(vm.activity.map((e) => e.id), ['decision:d1', 'change:c1']);
  assert.equal(vm.activityNext, null);
  await vm.moreActivity();
  assert.equal(calls.length, 2, 'no request past the last page');
});

test('a deep link to an older entry pages until it shows', async () => {
  pages = {
    '/api/activity?limit': { entries: [decision], next: '3000:decision:d1' },
    'before=3000': { entries: [change], next: '2000:change:c1' },
  };
  const vm = page({ activityEntry: 'change:c1' });
  await vm.loadActivity();
  assert.ok(vm.activity.some((e) => e.id === 'change:c1'));
  assert.equal(calls.length, 2, 'stops once the entry is loaded');
});

test('a filter goes through the route, and toggling an entry remembers it without a reload', () => {
  const vm = page({ activityFilter: { kind: 'change' } });
  vm.setActivityFilter('surface', 'import');
  assert.equal(location.hash, '#activity?kind=change&surface=import');
  vm.setActivityFilter('kind', '');
  assert.equal(location.hash, '#activity');
  vm.toggleActivity('change:c1');
  assert.equal(vm.activityOpen, 'change:c1');
  assert.equal(location.hash, '#activity/change:c1?kind=change');
  vm.toggleActivity('change:c1');
  assert.equal(vm.activityOpen, null);
});
