// SPDX-License-Identifier: Apache-2.0
// The console's view models: the sessions rail, the session log folded from SSE, one episode's
// timeline with its live builds and edits, the right rail, the run page's attempt, the project's
// Sessions tab, and the routes that reach them.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const storage = () => {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k) };
};
globalThis.sessionStorage = storage();
globalThis.localStorage = storage();
globalThis.location = { hash: '', pathname: '/', search: '' };

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const {
  attemptsArc,
  cardInSession,
  changedFiles,
  currentEpisode,
  earlierEpisodes,
  episodeHeader,
  episodeTimeline,
  foldEpisodeIntoRail,
  foldIntoDetail,
  foldSessionFrame,
  frameStamp,
  hostLabel,
  projectSessionRows,
  runAttempt,
  sessionChanges,
  sessionOfCard,
  sessionOfEpisode,
  sessionRail,
  sessionService,
  titleSourceLabel,
} = await spa('console.js');
const { historyCard } = await spa('fold.js');
const { buildConsoleHash, buildProjectHash, routeFromHash } = await spa('route.js');
const { consoleMethods } = await spa('consoleview.js');

const NOW = 1_800_000_000_000;
const ep = (over) => ({
  id: 'mcp-3f9a.1',
  session: 'mcp-3f9a',
  number: 1,
  project: '/w/hotel',
  projectId: 'p1',
  title: 'Fix weekend uplift rounding',
  titleSource: 'goal',
  outcome: 'working',
  startedAt: NOW - 600_000,
  lastAt: NOW - 60_000,
  runs: 0,
  actions: 0,
  ...over,
});
const claude = (over) => ({
  id: 'mcp-3f9a',
  origin: 'mcp',
  client: 'claude-code',
  label: 'claude-code 3f9a',
  project: '/w/hotel',
  projectId: 'p1',
  startedAt: NOW - 900_000,
  lastActiveAt: NOW - 60_000,
  state: 'live',
  episode: 'mcp-3f9a.1',
  current: ep(),
  ...over,
});
const shell = (over) => ({
  id: 'cli-shell-x',
  origin: 'cli',
  project: '/w/hotel',
  projectId: 'p1',
  startedAt: NOW - 1_500_000,
  lastActiveAt: NOW - 1_400_000,
  state: 'idle',
  episode: 'cli-shell-x.1',
  current: ep({ id: 'cli-shell-x.1', session: 'cli-shell-x', title: 'Working in core', titleSource: 'inferred', outcome: 'green' }),
  ...over,
});
const run = (over) => ({
  id: 'r' + over.buildNumber,
  kind: 'test',
  dir: '/w/hotel',
  projectId: 'p1',
  coord: 'dev:hotel',
  episode: 'mcp-3f9a.1',
  trigger: 'mcp',
  session: 'claude-code 3f9a',
  success: true,
  running: false,
  startedAt: NOW - 300_000,
  finishedAt: NOW - 290_000,
  millis: 10_000,
  modules: [],
  ...over,
});
const runAction = (seq, buildNumber, outcome, over = {}) => ({
  seq,
  at: NOW - 400_000 + seq * 10_000,
  kind: 'run',
  summary: 'test #' + buildNumber,
  outcome,
  millis: 10_000,
  episode: 'mcp-3f9a.1',
  project: '/w/hotel',
  buildNumber,
  ...over,
});
const liveCard = (over) => ({
  id: 77,
  kind: 'test',
  dir: '/w/hotel',
  projectId: 'p1',
  buildNumber: 1068,
  trigger: 'mcp',
  session: 'claude-code 3f9a',
  state: 'running',
  startedAt: NOW - 12_000,
  finishedAt: null,
  millis: null,
  cancelled: false,
  success: null,
  modules: [],
  output: [],
  ...over,
});

test('a session card names its host: the run label, the shell, the dashboard', () => {
  assert.equal(hostLabel(claude()), 'claude-code 3f9a');
  assert.equal(hostLabel(shell()), 'shell');
  assert.equal(hostLabel({ origin: 'web' }), 'dashboard');
  assert.equal(hostLabel({ origin: 'bsp', client: 'IntelliJ' }), 'IntelliJ');
});

test("an episode's title says where it came from: the agent's goal, the session's last goal, or inferred", () => {
  assert.equal(titleSourceLabel(ep()), 'goal from the agent');
  assert.equal(titleSourceLabel(ep({ titleSource: 'lastGoal' })), 'last goal');
  assert.equal(titleSourceLabel(ep({ titleSource: 'inferred' })), 'inferred');
});

test('the rail puts who waits on you first, then who is working, then the idle', () => {
  const codex = claude({ id: 'mcp-91be', label: 'codex 91be', lastActiveAt: NOW - 1000, current: ep({ id: 'mcp-91be.1', session: 'mcp-91be', outcome: 'green' }) });
  const gone = shell({ id: 'cli-old', state: 'ended', endedAt: NOW - 2 * 3600_000 });
  const rail = sessionRail([shell(), claude(), codex, gone], { waiting: new Set(['mcp-91be']), now: NOW });
  assert.deepEqual(
    rail.map((r) => [r.id, r.state]),
    [
      ['mcp-91be', 'waiting'],
      ['mcp-3f9a', 'working'],
      ['cli-shell-x', 'idle'],
    ],
    'an ended session older than an hour leaves the rail',
  );
  assert.equal(rail[1].title, 'Fix weekend uplift rounding');
  assert.equal(rail[1].source, 'goal from the agent');
  assert.equal(rail[2].source, 'your own commands', 'your shell is a session like any other');
  assert.equal(rail[2].project, 'hotel');
});

test('a live run makes its session working, matched by origin and label', () => {
  const settled = claude({ current: ep({ outcome: 'green' }) });
  assert.equal(sessionRail([settled], { now: NOW })[0].state, 'idle');
  assert.equal(sessionRail([settled], { cards: [liveCard()], now: NOW })[0].state, 'working');
  assert.equal(cardInSession(liveCard({ trigger: 'cli', session: null }), shell()), true);
  assert.equal(cardInSession(liveCard(), shell()), false);
  assert.equal(sessionOfCard(liveCard(), [shell(), claude()]).id, 'mcp-3f9a');
  assert.equal(sessionOfCard(liveCard(), [claude({ state: 'ended' })]), null);
});

test('session, episode and action frames fold into the rail and the selected page', () => {
  let sessions = [claude()];
  sessions = foldSessionFrame(sessions, { change: 'started', session: shell() });
  assert.deepEqual(sessions.map((s) => s.id), ['cli-shell-x', 'mcp-3f9a']);
  sessions = foldSessionFrame(sessions, { change: 'idle', session: claude({ state: 'idle' }) });
  assert.equal(sessions.find((s) => s.id === 'mcp-3f9a').state, 'idle');
  const next = ep({ id: 'mcp-3f9a.2', number: 2, title: 'Add a cancellation fee' });
  sessions = foldEpisodeIntoRail(sessions, { change: 'started', episode: next });
  assert.equal(sessions.find((s) => s.id === 'mcp-3f9a').current.title, 'Add a cancellation fee');

  let detail = { session: claude(), episodes: [ep()], actions: [] };
  detail = foldIntoDetail(detail, 'episode', { change: 'started', episode: next });
  assert.deepEqual(detail.episodes.map((e) => e.number), [1, 2]);
  assert.equal(detail.session.episode, 'mcp-3f9a.2');
  assert.equal(currentEpisode(detail).id, 'mcp-3f9a.2');
  const action = { seq: 4, at: NOW, kind: 'callers', summary: 'callers PricingRules.weekendUplift', headline: '3 callers', outcome: 'ok', millis: 40, episode: 'mcp-3f9a.2' };
  detail = foldIntoDetail(detail, 'action', { session: 'mcp-3f9a', action });
  detail = foldIntoDetail(detail, 'action', { session: 'mcp-3f9a', action });
  assert.equal(detail.actions.length, 1, 'an action folds once by its seq');
  const other = foldIntoDetail(detail, 'action', { session: 'someone-else', action: { ...action, seq: 9 } });
  assert.equal(other, detail, "another session's frame leaves the page as it was");
});

test("an episode's timeline joins runs to records, places edits before the run that saw them, and ends with the live build", () => {
  const delta = {
    previousBuildNumber: 1066,
    previousSuccess: false,
    previousMillis: 23_000,
    files: { count: 3, shown: ['core/PricingRules.java', 'db/migration/V7__round_half_even.sql (new)'] },
    appeared: { count: 1, shown: ['error · nullaway · Money.java:88'] },
    gone: { count: 0, shown: [] },
    fixed: { count: 1, shown: ['QuoteTest#a'] },
    broke: { count: 0, shown: [] },
    added: { count: 0, shown: [] },
    dropped: { count: 0, shown: [] },
  };
  const records = [
    run({ buildNumber: 1066, success: false, tests: { total: 9, succeeded: 7, failed: 2, skipped: 0 } }),
    run({ buildNumber: 1067, success: false, delta }),
  ];
  const actions = [
    { seq: 1, at: NOW - 500_000, kind: 'query', summary: 'query where is the weekend uplift applied', headline: 'PricingRules.weekendUplift', outcome: 'ok', millis: 40, episode: 'mcp-3f9a.1' },
    runAction(2, 1066, 'red', { headline: 'QuoteTest#threeNightWeekendStay expected 41250 but was 41249' }),
    runAction(3, 1067, 'red'),
    { seq: 4, at: NOW - 100, kind: 'deps', summary: 'deps add org.postgresql:postgresql', outcome: 'ok', millis: 900, episode: 'mcp-3f9a.2' },
  ];
  const items = episodeTimeline(ep(), { actions, records, cards: [liveCard()], session: claude(), current: true });
  assert.deepEqual(
    items.map((i) => [i.family, i.state, i.chip || i.title]),
    [
      ['graph', 'ok', 'query where is the weekend uplift applied'],
      ['run', 'err', '2 failed'],
      ['edit', '', 'edited 3 files'],
      ['run', 'err', 'failed'],
      ['run', 'run', 'running'],
    ],
    "another episode's action stays out",
  );
  assert.equal(items[1].sub, 'QuoteTest#threeNightWeekendStay expected 41250 but was 41249');
  assert.equal(items[2].sub, 'core/PricingRules.java · db/migration/V7__round_half_even.sql · +1 more');
  assert.equal(items[3].delta.cls, 'bad', 'the run carries its one-chip delta');
  assert.equal(items[4].card.id, 77, 'the live build carries its card for the strip');
  assert.equal(items[4].projectId, 'p1');
  assert.deepEqual(attemptsArc(items).map((a) => a.state), ['err', 'err', 'run']);

  const header = episodeHeader(ep(), { timeline: items, records, episodes: [ep()], now: NOW });
  assert.equal(header.wallMillis, 600_000, 'a working episode runs to now');
  assert.equal(header.jkMillis, 40 + 10_000 + 10_000 + 12_000);
  assert.equal(header.files, 3);
  assert.equal(header.source, 'goal from the agent');
});

test('a finished run not yet written as an action still shows, once', () => {
  const done = historyCard(run({ buildNumber: 1068, success: true }));
  const items = episodeTimeline(ep(), { actions: [], records: [], cards: [{ ...done, startedAt: NOW - 100_000 }], session: claude(), current: true });
  assert.deepEqual(items.map((i) => [i.state, i.chip]), [['ok', 'ok']]);
  const written = episodeTimeline(ep(), { actions: [runAction(1, 1068, 'green')], records: [], cards: [done], session: claude(), current: true });
  assert.equal(written.length, 1, 'the action and its card are one line');
});

test('earlier episodes fold newest first; the page names its current one', () => {
  const detail = {
    session: claude({ episode: 'mcp-3f9a.3' }),
    episodes: [
      ep({ id: 'mcp-3f9a.1', number: 1, outcome: 'green', title: 'Explore the code', titleSource: 'inferred', startedAt: 0, lastAt: 180_000 }),
      ep({ id: 'mcp-3f9a.2', number: 2, outcome: 'green', title: 'Add a cancellation fee', runs: 4 }),
      ep({ id: 'mcp-3f9a.3', number: 3 }),
    ],
    actions: [],
  };
  assert.equal(currentEpisode(detail).number, 3);
  const earlier = earlierEpisodes(detail);
  assert.deepEqual(earlier.map((e) => e.number), [2, 1]);
  assert.equal(earlier[1].wallMillis, 180_000);
  assert.equal(earlier[1].source, 'inferred');
});

test('the right rail: what the session changed and how jk served it', () => {
  const records = [
    run({ buildNumber: 1, delta: { files: { count: 2, shown: ['a.java', 'db/migration/V7__x.sql (new)'] }, fixed: { count: 1, shown: [] }, broke: { count: 1, shown: [] } } }),
    run({ buildNumber: 2, kind: 'guard', success: true, finishedAt: NOW, delta: { files: { count: 1, shown: ['a.java'] } } }),
    run({ buildNumber: 3, episode: 'other.1' }),
    run({
      buildNumber: 4,
      modules: [{ tasks: [{ status: 'SKIPPED' }, { status: 'SUCCESS', millis: 0 }, { status: 'SUCCESS', millis: 900 }, { status: 'FAIL', millis: 9 }] }],
    }),
  ];
  const detail = {
    session: claude(),
    episodes: [ep()],
    actions: [
      { seq: 1, kind: 'deps', summary: 'deps add org.postgresql:postgresql', outcome: 'ok', millis: 1_100, episode: 'mcp-3f9a.1' },
      { seq: 2, kind: 'update', summary: 'update', outcome: 'error', millis: 300, episode: 'mcp-3f9a.1' },
      runAction(3, 1, 'green'),
    ],
  };
  const changed = sessionChanges(detail, records);
  assert.deepEqual(changed.files, { count: 2, paths: ['a.java', 'db/migration/V7__x.sql'] });
  assert.deepEqual(changed.migrations, [{ path: 'db/migration/V7__x.sql', name: 'V7', change: 'new' }]);
  assert.deepEqual(changed.deps, ['deps add org.postgresql:postgresql'], 'a failed change changed nothing');
  assert.equal(changed.guard, 'clean');
  assert.deepEqual(changed.tests, { fixed: 1, broke: 1 });

  const served = sessionService(detail, records, [liveCard()]);
  assert.equal(served.calls, 4, 'three actions and the run in flight');
  assert.equal(served.runs, 2);
  assert.equal(served.wallMillis, 1_100 + 300 + 10_000);
  assert.equal(served.cachedPct, 50);
  assert.deepEqual(changedFiles([]), { count: 0, paths: [] });
});

test('the run page names its episode and attempt', () => {
  const detail = {
    session: claude(),
    episodes: [ep()],
    actions: [runAction(1, 1066, 'red'), { seq: 2, kind: 'read', summary: 'read Money', outcome: 'ok', episode: 'mcp-3f9a.1' }, runAction(3, 1067, 'red')],
  };
  const card = historyCard(run({ buildNumber: 1067 }));
  assert.equal(card.episode, 'mcp-3f9a.1', 'the record folds its episode onto the card');
  assert.deepEqual(runAttempt(card, detail), {
    episodeId: 'mcp-3f9a.1',
    sessionId: 'mcp-3f9a',
    title: 'Fix weekend uplift rounding',
    source: 'goal from the agent',
    attempt: 2,
    of: 2,
  });
  const running = { ...liveCard(), episode: null };
  assert.equal(runAttempt(running, detail, 'mcp-3f9a.1').attempt, 3, 'a run in flight is the next attempt');
  assert.equal(runAttempt(historyCard(run({ buildNumber: 5, episode: null })), detail), null);
  assert.equal(sessionOfEpisode('mcp-3f9a.12'), 'mcp-3f9a');
  assert.equal(sessionOfEpisode(null), null);
});

test("the project's Sessions tab lists each session's episodes on that project", () => {
  const details = {
    'mcp-3f9a': { episodes: [ep({ id: 'mcp-3f9a.1', number: 1 }), ep({ id: 'mcp-3f9a.2', number: 2, projectId: 'p2' }), ep({ id: 'mcp-3f9a.3', number: 3 })] },
  };
  const rows = projectSessionRows([claude(), shell(), claude({ id: 'mcp-zz', projectId: 'p2', current: ep({ projectId: 'p2' }) })], details, 'p1');
  assert.deepEqual(rows.map((r) => r.id), ['mcp-3f9a', 'cli-shell-x']);
  assert.deepEqual(rows[0].episodes.map((e) => e.id), ['mcp-3f9a.3', 'mcp-3f9a.1']);
  assert.equal(rows[1].host, 'shell');
});

test('routes: the console is the landing page and selects a session; the project has a Sessions tab', () => {
  assert.equal(routeFromHash('').view, 'console');
  assert.equal(routeFromHash('#activity').view, 'console', 'a retired hash lands on the console');
  assert.deepEqual([routeFromHash('#console').view, routeFromHash('#console').session], ['console', null]);
  assert.equal(routeFromHash('#console/mcp-3f9a').session, 'mcp-3f9a');
  assert.equal(buildConsoleHash('mcp-3f9a'), '#console/mcp-3f9a');
  assert.equal(buildConsoleHash(null), '#console');
  const tab = routeFromHash('#project/p1/sessions?dir=%2Fw');
  assert.deepEqual([tab.view, tab.projectId, tab.tab, tab.dir], ['project', 'p1', 'sessions', '/w']);
  assert.equal(routeFromHash('#project/p1').tab, 'runs');
  assert.equal(buildProjectHash({ projectId: 'p1', sessions: true }), '#project/p1/sessions');
  assert.equal(buildProjectHash({ projectId: 'p1', run: 4 }), '#project/p1/run/4');
});

test('an action or decision frame names when the engine published it; nothing else does', () => {
  const action = { session: 'mcp-3f9a', action: { seq: 7, at: NOW, millis: 40, kind: 'find' } };
  assert.deepEqual(frameStamp('action', action), { kind: 'action', id: 'mcp-3f9a#7', publishedAt: NOW + 40 });
  assert.equal(frameStamp('action', { session: 'mcp-3f9a', action: { seq: 1 } }), null, 'no start time, no stamp');
  const opened = { id: 'd1', question: 'Clean the cache?', createdAt: NOW, state: 'open' };
  assert.deepEqual(frameStamp('decision', opened), { kind: 'decision', id: 'd1', publishedAt: NOW });
  assert.deepEqual(frameStamp('decision', { change: 'opened', decision: opened }).id, 'd1');
  assert.equal(frameStamp('decision', { ...opened, state: 'declined' }), null, 'a settled decision is not a new one');
  assert.equal(frameStamp('episode', { episode: ep() }), null);
  assert.equal(frameStamp('action', null), null);
});

test('a painted action or decision frame leaves a jk-console-event mark saying whether it is on screen', async () => {
  const marks = [];
  const saved = { performance: globalThis.performance, raf: globalThis.requestAnimationFrame, document: globalThis.document };
  globalThis.performance = { mark: (name, opts) => marks.push({ name, detail: opts.detail }), clearMarks: () => {} };
  globalThis.requestAnimationFrame = (f) => setTimeout(f, 0);
  globalThis.document = { hidden: false };
  try {
    const vm = {
      ...consoleMethods,
      view: 'console',
      decisions: [],
      sessions: [],
      sessionDetail: { session: { id: 'mcp-3f9a' }, episodes: [], actions: [] },
      get needsYou() {
        return this.decisions.map((d) => ({ id: d.id }));
      },
      $nextTick: (f) => f(),
    };
    vm.applyConsoleEvent({ type: 'action', data: { session: 'mcp-3f9a', action: { seq: 1, at: Date.now() - 30, millis: 10 } } });
    vm.applyConsoleEvent({ type: 'action', data: { session: 'other', action: { seq: 2, at: Date.now(), millis: 0 } } });
    vm.applyConsoleEvent({ type: 'decision', data: { id: 'd1', createdAt: Date.now(), state: 'open' } });
    // Each mark lands two timer turns after its frame; wait for the marks, not a fixed time, so a
    // stalled event loop cannot run the assertion before they land.
    for (let turns = 0; marks.length < 3 && turns < 1000; turns++) await new Promise((r) => setTimeout(r, 1));
    assert.deepEqual(marks.map((m) => m.name), ['jk-console-event', 'jk-console-event', 'jk-console-event']);
    const [mine, other, decision] = marks.map((m) => m.detail);
    assert.deepEqual([mine.kind, mine.id, mine.shown], ['action', 'mcp-3f9a#1', true]);
    assert.ok(mine.latencyMs >= 20 && mine.paintedAt - mine.publishedAt === mine.latencyMs, JSON.stringify(mine));
    assert.equal(other.shown, false, "another session's action is not on the selected timeline");
    assert.deepEqual([decision.kind, decision.id, decision.shown], ['decision', 'd1', true]);
    globalThis.document = { hidden: true };
    vm.applyConsoleEvent({ type: 'decision', data: { id: 'd2', createdAt: Date.now(), state: 'open' } });
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    assert.equal(marks.length, 3, 'a hidden tab marks nothing');
  } finally {
    globalThis.performance = saved.performance;
    globalThis.requestAnimationFrame = saved.raf;
    globalThis.document = saved.document;
  }
});
