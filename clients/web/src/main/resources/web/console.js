// SPDX-License-Identifier: Apache-2.0
// The console's view models: the sessions rail, the selected session's current episode as a
// timeline of actions, its earlier episodes, and the right rail's "This session changed" and "How
// jk served the agent". Pure functions over `/api/sessions` rows, a `/api/sessions/<id>` page,
// journal records (`/api/history`) and folded cards (fold.js) — no DOM, no fetch — so the suite
// runs them headlessly. Every number is one the journal, a record or an action already carries.

import { deltaChip } from './delta.js';
import { outcomeOf } from './outcome.js';

/** A session that ended more than this long ago leaves the rail (it stays on the project's Sessions tab). */
export const RAIL_ENDED_MILLIS = 60 * 60 * 1000;

/** The host a session card names: the run records' label, else the shell or the dashboard. */
export function hostLabel(s) {
  if (!s) return '—';
  if (s.label) return s.label;
  if (s.origin === 'cli') return 'shell';
  if (s.origin === 'web') return 'dashboard';
  return s.client || s.origin || 'session';
}

/** A command-line session's title source: a person's terminal or script, or an agent's shell. */
export function cliSource(s) {
  if (!s || s.origin !== 'cli') return null;
  return / \(shell\)$/.test(s.client || '') ? 'shell commands' : 'your own commands';
}

/** Where an episode's title came from, as the screen says it. */
export function titleSourceLabel(ep) {
  if (!ep) return '';
  if (ep.titleSource === 'goal') return 'goal from the agent';
  if (ep.titleSource === 'lastGoal') return 'last goal';
  return 'inferred';
}

/** The last path segment of a checkout, the way the rail names a project. */
export function projectName(dir) {
  const parts = String(dir || '').split(/[\\/]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : '';
}

/** A folded card is this session's run: same trigger and the same label (none, for the shell). */
export function cardInSession(card, s) {
  if (!card || !s) return false;
  return (card.trigger || null) === (s.origin || null) && (card.session || null) === (s.label || null);
}

/**
 * The session a live card belongs to: the one with its origin and label that has not ended, the
 * most recently active when several match. Null when none does (a run from before the log).
 */
export function sessionOfCard(card, sessions) {
  let best = null;
  for (const s of sessions || []) {
    if (s.state === 'ended' || !cardInSession(card, s)) continue;
    if (!best || (s.lastActiveAt || 0) > (best.lastActiveAt || 0)) best = s;
  }
  return best;
}

/**
 * A rail card's state: `waiting` while a decision blocks it, `working` while it is live and a run
 * is in flight or its current episode is open, `idle` when quiet or settled, `ended` when gone.
 */
export function railState(s, { waiting, running } = {}) {
  if (s.state === 'ended') return 'ended';
  if (waiting && (waiting.has(s.id) || (s.label && waiting.has(s.label)))) return 'waiting';
  if (running) return 'working';
  if (s.state === 'live' && s.current && s.current.outcome === 'working') return 'working';
  return 'idle';
}

const RAIL_ORDER = { waiting: 0, working: 1, idle: 2, ended: 3 };

/**
 * The sessions rail: one card per live or idle session, and those that ended within
 * {@link RAIL_ENDED_MILLIS}, waiting first, then working, then by last activity. Each card is
 * `{ id, host, origin, state, title, source, project, projectId, startedAt, lastActiveAt, episodeNumber }`.
 */
export function sessionRail(sessions, { cards = [], waiting = new Set(), now = Date.now() } = {}) {
  const live = (cards || []).filter((c) => outcomeOf(c) === 'running');
  const rows = [];
  for (const s of sessions || []) {
    if (!s || !s.id) continue;
    if (s.state === 'ended' && now - (s.endedAt || s.lastActiveAt || 0) > RAIL_ENDED_MILLIS) continue;
    const running = live.some((c) => sessionOfCard(c, sessions) === s);
    const ep = s.current || null;
    rows.push({
      id: s.id,
      host: hostLabel(s),
      origin: s.origin || null,
      state: railState(s, { waiting, running }),
      title: ep ? ep.title : 'No episode yet',
      source: cliSource(s) || titleSourceLabel(ep),
      project: projectName((ep && ep.project) || s.project),
      projectId: (ep && ep.projectId) || s.projectId || null,
      startedAt: s.startedAt || null,
      lastActiveAt: s.lastActiveAt || null,
      episodeNumber: ep ? ep.number : 0,
    });
  }
  rows.sort((a, b) => RAIL_ORDER[a.state] - RAIL_ORDER[b.state] || (b.lastActiveAt || 0) - (a.lastActiveAt || 0));
  return rows;
}

// ---- the session log's SSE frames, folded into the rail rows and the selected session's page ----

/** A `session` frame (`{ change, session }`): the row replaced, or added when new. Returns a new array. */
export function foldSessionFrame(sessions, frame) {
  const row = frame && frame.session;
  if (!row || !row.id) return sessions;
  const rest = (sessions || []).filter((s) => s.id !== row.id);
  return [row, ...rest];
}

/**
 * An `episode` frame (`{ change, episode }`) on the rail: the session's current episode when it is
 * that one, or when the frame starts a new one. Returns a new array.
 */
export function foldEpisodeIntoRail(sessions, frame) {
  const ep = frame && frame.episode;
  if (!ep || !ep.session) return sessions;
  return (sessions || []).map((s) =>
    s.id === ep.session && (s.episode === ep.id || frame.change === 'started') ? { ...s, episode: ep.id, current: ep } : s,
  );
}

/**
 * The selected session's page (`{ session, episodes, actions }`) after one log frame: an episode
 * replaced or appended in number order, an action appended once by `seq`, the session row
 * replaced. A frame for another session leaves the page as it was. Returns a new object.
 */
/** Episodes a session page holds, newest first: as many as the engine keeps and serves. */
export const EPISODES_KEPT = 50;

export function foldIntoDetail(detail, type, frame) {
  if (!detail || !detail.session || !frame) return detail;
  const id = detail.session.id;
  if (type === 'session' && frame.session && frame.session.id === id) {
    return { ...detail, session: frame.session };
  }
  if (type === 'episode' && frame.episode && frame.episode.session === id) {
    const ep = frame.episode;
    const episodes = [...(detail.episodes || []).filter((e) => e.id !== ep.id), ep]
      .sort((a, b) => b.number - a.number)
      .slice(0, EPISODES_KEPT);
    const session = frame.change === 'started' ? { ...detail.session, episode: ep.id } : detail.session;
    return { ...detail, session, episodes };
  }
  if (type === 'action' && frame.session === id && frame.action) {
    const a = frame.action;
    if ((detail.actions || []).some((x) => x.seq === a.seq)) return detail;
    return { ...detail, actions: [...(detail.actions || []), a].sort((x, y) => x.seq - y.seq) };
  }
  return detail;
}

/**
 * When the engine published a console frame, and what the frame names, for the event-latency
 * mark: an `action` frame goes out as its call ends (`at + millis`), a `decision` frame as the
 * decision opens (`createdAt`). `{ kind, id, publishedAt }`, or null for any other frame and for a
 * decision frame that settles one.
 */
export function frameStamp(type, frame) {
  if (!frame) return null;
  if (type === 'action') {
    const a = frame.action;
    if (!a || !frame.session || !Number.isFinite(a.at)) return null;
    return { kind: 'action', id: frame.session + '#' + a.seq, publishedAt: a.at + (a.millis || 0) };
  }
  if (type === 'decision') {
    const d = frame.decision || frame;
    if (!d || !d.id || d.resolution || (d.state && d.state !== 'open') || !Number.isFinite(d.createdAt)) return null;
    return { kind: 'decision', id: d.id, publishedAt: d.createdAt };
  }
  return null;
}

// ---- one episode's timeline ----

const GRAPH_TOOLS = new Set(['query', 'callers', 'read', 'find']);
const DEP_TOOLS = new Set(['deps', 'update', 'workspace']);

/** The timeline family of an action kind: run, graph, rename, deps or other. */
export function actionFamily(kind) {
  if (kind === 'run') return 'run';
  if (GRAPH_TOOLS.has(kind)) return 'graph';
  if (kind === 'rename') return 'rename';
  if (DEP_TOOLS.has(kind)) return 'deps';
  return 'other';
}

/** A run outcome in the colour vocabulary: ok, err, run, cancelled. */
function runState(outcome) {
  if (outcome === 'green' || outcome === 'success') return 'ok';
  if (outcome === 'red' || outcome === 'failed') return 'err';
  if (outcome === 'running' || outcome === 'queued') return 'run';
  if (outcome === 'cancelled') return 'cancelled';
  return '';
}

/** The record of a run in `records` (journal rows): by episode and build number, else by dir and number. */
function recordOf(records, episodeId, buildNumber, dir) {
  if (!buildNumber) return null;
  return (
    (records || []).find((r) => r.buildNumber === buildNumber && r.episode === episodeId) ||
    (records || []).find((r) => r.buildNumber === buildNumber && dir && r.dir === dir) ||
    null
  );
}

/** A run's outcome chip text: `running`, `2 failed`, `ok`, `failed`, `cancelled`. */
function runChip(state, rec) {
  if (state === 'run') return 'running';
  if (state === 'cancelled') return 'cancelled';
  const failed = rec && rec.tests && rec.tests.failed;
  if (state === 'err') return failed ? failed + ' failed' : 'failed';
  return 'ok';
}

/** Distinct file paths a delta's shown rows name, without the `(new)` / `(gone)` marks. */
function deltaPaths(delta) {
  const rows = (delta && delta.files && delta.files.shown) || [];
  return rows.map((r) => String(r).replace(/\s+\((new|gone)\)$/, ''));
}

/** The `edited N files` line jk places before the run that saw the edits. */
function editItem(rec, at) {
  const files = rec.delta.files;
  const shown = deltaPaths(rec.delta);
  const more = files.count - shown.length;
  return {
    key: 'edit-' + rec.id,
    family: 'edit',
    at,
    state: '',
    title: 'edited ' + files.count + (files.count === 1 ? ' file' : ' files'),
    sub: shown.join(' · ') + (more > 0 ? ' · +' + more + ' more' : ''),
    tip: 'JumpKick sees edits at the next run, through its file delta; they are placed just before it',
  };
}

/**
 * One episode's timeline, oldest first: its actions (`/api/sessions/<id>` rows of that episode),
 * each run joined to its journal record for the outcome chip, the failing headline and the delta
 * chip; an `edited N files` line before every run whose delta saw changed files; and, for the
 * session's current episode, every run of the session still in flight (or finished but not yet
 * written as an action), carrying its live card for the one-line build strip.
 */
export function episodeTimeline(episode, { actions = [], records = [], cards = [], session = null, current = false } = {}) {
  if (!episode) return [];
  const items = [];
  const seen = new Set();
  const pushRun = (item, rec) => {
    if (rec && rec.delta && rec.delta.files && rec.delta.files.count > 0) items.push(editItem(rec, item.at));
    items.push(item);
  };
  for (const a of actions || []) {
    if (a.episode !== episode.id) continue;
    const family = actionFamily(a.kind);
    if (family !== 'run') {
      items.push({
        key: 'a' + a.seq,
        family,
        kind: a.kind,
        at: a.at,
        state: a.outcome === 'error' ? 'err' : 'ok',
        title: a.summary,
        sub: a.headline || '',
        millis: a.millis ?? null,
      });
      continue;
    }
    const rec = recordOf(records, episode.id, a.buildNumber, a.project);
    const card = (cards || []).find((c) => a.buildNumber && c.buildNumber === a.buildNumber && (!a.project || c.dir === a.project));
    const state = card && outcomeOf(card) === 'running' ? 'run' : runState(a.outcome);
    if (a.buildNumber) seen.add((a.project || '') + '#' + a.buildNumber);
    pushRun(
      {
        key: 'a' + a.seq,
        family: 'run',
        kind: (rec && rec.kind) || a.summary.split(' ')[0] || 'run',
        at: a.at,
        state,
        title: a.summary,
        chip: runChip(state, rec),
        sub: a.headline || '',
        delta: rec ? deltaChip(rec.delta, rec.millis ?? null) : null,
        millis: a.millis ?? null,
        buildNumber: a.buildNumber || null,
        projectId: (rec && rec.projectId) || (card && card.projectId) || episode.projectId || null,
        card: card || null,
      },
      rec,
    );
  }
  if (current && session) {
    const pending = (cards || [])
      .filter((c) => cardInSession(c, session))
      .filter((c) => (c.startedAt ?? 0) >= (episode.startedAt ?? 0) - 1000)
      .filter((c) => !c.buildNumber || !seen.has((c.dir || '') + '#' + c.buildNumber))
      .filter((c) => !c.episode || c.episode === episode.id)
      .sort((a, b) => (a.startedAt ?? 0) - (b.startedAt ?? 0));
    for (const card of pending) {
      const outcome = outcomeOf(card);
      const state = runState(outcome === 'success' ? 'success' : outcome);
      const rec = card.buildNumber ? recordOf(records, episode.id, card.buildNumber, card.dir) : null;
      pushRun(
        {
          key: 'c' + card.id,
          family: 'run',
          kind: card.kind || 'build',
          at: card.startedAt,
          state,
          title: (card.kind || 'build') + (card.buildNumber ? ' #' + card.buildNumber : ''),
          chip: outcome === 'queued' ? 'queued for memory' : runChip(state, rec || card),
          sub: '',
          delta: state === 'run' ? null : deltaChip(card.delta, card.millis ?? null),
          millis: state === 'run' ? null : card.millis ?? null,
          buildNumber: card.buildNumber || null,
          projectId: card.projectId || episode.projectId || null,
          card,
        },
        rec,
      );
    }
  }
  return items;
}

/** The attempts arc: one segment per run of the episode, `ok` / `err` / `run` / `cancelled`. */
export function attemptsArc(timeline) {
  return (timeline || []).filter((i) => i.family === 'run').map((i) => ({ key: i.key, state: i.state || 'cancelled' }));
}

/** Distinct files a set of journal records' deltas changed (the shown rows plus the uncounted tail). */
export function changedFiles(records) {
  const paths = new Set();
  let hidden = 0;
  for (const r of records || []) {
    const files = r && r.delta && r.delta.files;
    if (!files) continue;
    const shown = deltaPaths(r.delta);
    for (const p of shown) paths.add(p);
    hidden += Math.max(0, (files.count || 0) - shown.length);
  }
  return { count: paths.size + hidden, paths: [...paths].sort() };
}

/**
 * The selected episode's header: title and its source, outcome, number of how many, the attempts
 * arc, wall (to now while working), time in jk (the actions' and runs' walls), files changed.
 * An open episode is working only while its session is live or one of its runs is in flight, the
 * rule the rail's {@link railState} keeps; otherwise it reads idle and its wall stops at its last
 * fact, so a page that missed the engine settling it never counts up forever.
 */
export function episodeHeader(episode, { timeline = [], records = [], episodes = [], session = null, now = Date.now() } = {}) {
  if (!episode) return null;
  const running = (timeline || []).some((i) => i.state === 'run' && i.card);
  const open = episode.outcome === 'working';
  const working = open && (running || !session || session.state === 'live');
  const end = working ? now : episode.lastAt || now;
  let inJk = 0;
  for (const i of timeline) {
    if (typeof i.millis === 'number') inJk += i.millis;
    else if (i.state === 'run' && i.card && i.card.startedAt) inJk += Math.max(0, now - i.card.startedAt);
  }
  const runs = (records || []).filter((r) => r.episode === episode.id);
  return {
    id: episode.id,
    title: episode.title,
    source: titleSourceLabel(episode),
    outcome: open && !working ? 'idle' : episode.outcome,
    number: episode.number,
    // The page holds only the newest episodes: the session has had as many as its newest's number.
    of: Math.max(episode.number || 0, ...(episodes || []).map((e) => e.number || 0)),
    project: projectName(episode.project),
    projectId: episode.projectId || null,
    arc: attemptsArc(timeline),
    wallMillis: Math.max(0, end - (episode.startedAt || end)),
    jkMillis: inJk,
    files: changedFiles(runs).count,
  };
}

/** Earlier episodes, newest first, folded: `{ id, title, source, outcome, runs, actions, wallMillis, number }`. */
export function earlierEpisodes(detail) {
  if (!detail || !detail.session) return [];
  const currentId = detail.session.episode;
  return (detail.episodes || [])
    .filter((e) => e.id !== currentId)
    .sort((a, b) => b.number - a.number)
    .map((e) => ({
      id: e.id,
      title: e.title,
      source: titleSourceLabel(e),
      outcome: e.outcome,
      runs: e.runs || 0,
      actions: e.actions || 0,
      wallMillis: Math.max(0, (e.lastAt || 0) - (e.startedAt || 0)),
      number: e.number,
    }));
}

/**
 * The episode pane's state when it has no episode to show: `loading` until the session list and
 * the selected session's page have answered, `failed` when that page did not load, `no-episode`
 * when it loaded with none, `empty` when there is no session at all. A page that answered is never
 * shown as loading.
 */
export function consolePaneState({ sessionsLoaded, selectedId, detailFor, detail }) {
  if (!sessionsLoaded) return 'loading';
  if (!selectedId) return 'empty';
  if (detailFor !== selectedId) return 'loading';
  if (!detail) return 'failed';
  return 'no-episode';
}

/** The episode a page names as current: the session's `episode`, else its newest. */
export function currentEpisode(detail) {
  if (!detail || !detail.session) return null;
  const eps = detail.episodes || [];
  const found = eps.find((e) => e.id === detail.session.episode);
  if (found) return found;
  return eps.reduce((best, e) => (!best || e.number > best.number ? e : best), null);
}

// ---- the right rail ----

/** Journal records of one session: their episode id starts with the session's. */
export function sessionRecords(records, sessionId) {
  if (!sessionId) return [];
  return (records || []).filter((r) => r.episode && r.episode.startsWith(sessionId + '.'));
}

const MIGRATION = /(^|\/)V(\d+)__[^/]*\.sql$/;

/**
 * "This session changed": files (distinct paths), migrations (a `V<n>__*.sql` the deltas name,
 * `new` when added), dependency changes (its `deps` / `update` / `workspace` actions), guard
 * runs (the newest one's outcome) and the tests its runs flipped.
 */
export function sessionChanges(detail, records) {
  const id = detail && detail.session && detail.session.id;
  const runs = sessionRecords(records, id);
  const files = changedFiles(runs);
  const migrations = [];
  for (const r of runs) {
    for (const row of (r.delta && r.delta.files && r.delta.files.shown) || []) {
      const path = String(row).replace(/\s+\((new|gone)\)$/, '');
      const m = MIGRATION.exec(path);
      if (m && !migrations.some((x) => x.path === path)) {
        migrations.push({ path, name: 'V' + m[2], change: /\(new\)$/.test(row) ? 'new' : /\(gone\)$/.test(row) ? 'gone' : 'edited' });
      }
    }
  }
  const actions = (detail && detail.actions) || [];
  const deps = actions.filter((a) => DEP_TOOLS.has(a.kind) && a.outcome !== 'error').map((a) => a.summary);
  const guard = runs.filter((r) => r.kind === 'guard').sort((a, b) => (b.finishedAt || 0) - (a.finishedAt || 0))[0];
  let fixed = 0;
  let broke = 0;
  for (const r of runs) {
    fixed += (r.delta && r.delta.fixed && r.delta.fixed.count) || 0;
    broke += (r.delta && r.delta.broke && r.delta.broke.count) || 0;
  }
  return {
    files,
    migrations,
    deps,
    guard: guard ? (guard.success ? 'clean' : 'failed') : null,
    tests: { fixed, broke },
  };
}

/**
 * "How jk served the agent": actions taken (every MCP call and run of the session), jk's wall
 * time across them, and the share of tasks the session's runs did not have to run (the cache or an
 * up-to-date check skipped them), from the records' module tasks.
 */
export function sessionService(detail, records, cards) {
  const actions = (detail && detail.actions) || [];
  let wall = 0;
  for (const a of actions) if (typeof a.millis === 'number') wall += a.millis;
  let skipped = 0;
  let tasks = 0;
  const id = detail && detail.session && detail.session.id;
  for (const r of sessionRecords(records, id)) {
    for (const m of r.modules || []) {
      for (const t of m.tasks || []) {
        tasks++;
        if (t.status === 'SKIPPED' || (t.status === 'SUCCESS' && t.millis === 0)) skipped++;
      }
    }
  }
  const running = detail && detail.session ? (cards || []).filter((c) => outcomeOf(c) === 'running' && cardInSession(c, detail.session)).length : 0;
  return {
    calls: actions.length + running,
    runs: actions.filter((a) => a.kind === 'run').length + running,
    wallMillis: wall,
    cachedPct: tasks ? Math.round((100 * skipped) / tasks) : null,
    tasks,
  };
}

/**
 * The run page's place in its episode: `{ episodeId, title, attempt, of }`, where `attempt` is the
 * run's position among the episode's runs (from the session page's run actions). Null when the
 * run names no episode or the page does not hold it.
 */
export function runAttempt(card, detail, episodeId = card && card.episode) {
  if (!card || !episodeId || !detail || !detail.episodes) return null;
  const ep = detail.episodes.find((e) => e.id === episodeId);
  if (!ep) return null;
  const runs = (detail.actions || []).filter((a) => a.kind === 'run' && a.episode === ep.id && a.buildNumber);
  const i = runs.findIndex((a) => a.buildNumber === card.buildNumber);
  return {
    episodeId: ep.id,
    sessionId: ep.session,
    title: ep.title,
    source: titleSourceLabel(ep),
    attempt: i >= 0 ? i + 1 : runs.length + 1,
    of: i >= 0 ? runs.length : runs.length + 1,
  };
}

/** The session id an episode id names: everything before its last `.`. */
export function sessionOfEpisode(episodeId) {
  const s = String(episodeId || '');
  const i = s.lastIndexOf('.');
  return i > 0 ? s.slice(0, i) : null;
}

/**
 * The project page's Sessions tab: every session whose project is this one, newest first, each
 * with the episodes it spent on the project (from its page when loaded, else its current one).
 */
export function projectSessionRows(sessions, details, projectId) {
  const rows = [];
  for (const s of sessions || []) {
    const page = details && details[s.id];
    const eps = ((page && page.episodes) || (s.current ? [s.current] : [])).filter((e) => e.projectId === projectId);
    if (!eps.length && s.projectId !== projectId) continue;
    rows.push({
      id: s.id,
      host: hostLabel(s),
      state: s.state,
      startedAt: s.startedAt || null,
      lastActiveAt: s.lastActiveAt || null,
      episodes: eps
        .slice()
        .sort((a, b) => b.number - a.number)
        .map((e) => ({
          id: e.id,
          title: e.title,
          source: titleSourceLabel(e),
          outcome: e.outcome,
          runs: e.runs || 0,
          actions: e.actions || 0,
          startedAt: e.startedAt || null,
          wallMillis: Math.max(0, (e.lastAt || 0) - (e.startedAt || 0)),
        })),
    });
  }
  rows.sort((a, b) => (b.lastActiveAt || 0) - (a.lastActiveAt || 0));
  return rows;
}
