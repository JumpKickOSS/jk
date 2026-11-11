// SPDX-License-Identifier: Apache-2.0
// The console's root-component mixins: the reads behind the landing page (`/api/sessions`, one
// session's page, `/api/decisions`), the session log and decision frames folded as they arrive,
// and the computeds the template renders through console.js's pure models. Spread into the root
// component, so `this` is the root component.

import { get } from './api.js';
import {
  currentEpisode,
  hostLabel,
  earlierEpisodes,
  consolePaneState,
  episodeHeader,
  episodeTimeline,
  fetchSessionDetail,
  foldEpisodeIntoRail,
  foldIntoDetail,
  foldSessionFrame,
  frameStamp,
  projectSessionRows,
  runAttempt,
  sessionChanges,
  sessionOfCard,
  sessionOfEpisode,
  sessionRail,
  sessionService,
} from './console.js';
import { bannerRows, fetchDecisions, foldDecision, waitingSessions } from './needs.js';
import { buildConsoleHash, buildProjectHash } from './route.js';
import { doingLine, doingSegments, moduleCells, workerLanes } from './strip.js';
import { LOG, SSE } from './wire.js';

/** Sessions the rail asks for; the Sessions tab of a project asks for more. */
const RAIL_LIMIT = 50;
const PROJECT_LIMIT = 200;
/** Session pages the Sessions tab loads to list each one's episodes on the project. */
const PROJECT_PAGES = 20;
/** Event marks a page keeps before it clears them, so a tab left open for days does not grow. */
const EVENT_MARKS = 500;

/** The data the console adds to the root component. */
export function consoleData(route) {
  return {
    sessions: [], // `/api/sessions` rows, folded with `session` / `episode` frames
    sessionsLoaded: false,
    consoleSession: route.session || null, // the session the route selects (#console/<id>)
    consoleAutoSession: null, // with none routed, the rail's first card at load, held so it does not jump
    sessionDetail: null, // the selected session's `/api/sessions/<id>` page
    // The session whose page load last finished, answered or failed: until it is the selected one
    // the episode pane is loading; after, an empty page means no episode, not a load in flight.
    sessionDetailFor: null,
    decisions: [], // open decisions, oldest first
    decisionsAvailable: false, // false while the engine has no decision queue (404)
    runEpisodePage: null, // the session page holding the run page's episode
    projectSessions: null, // the Sessions tab: { projectId, sessions, details }
    consolePaintedMs: null, // first meaningful paint of the console, ms since navigation start
    fileView: null, // the edited file open read-only over the page: { projectId, dir, path, mark }
  };
}

export const consoleComputed = {
  /** The rail's cards, decisions marking who waits on you. */
  sessionRail() {
    return sessionRail(this.sessions, { cards: this.cards, waiting: waitingSessions(this.decisions), now: this.now });
  },
  /** The selected session: the route's, else the rail's first. */
  selectedSessionId() {
    if (this.consoleSession) return this.consoleSession;
    if (this.consoleAutoSession && this.sessions.some((s) => s.id === this.consoleAutoSession)) return this.consoleAutoSession;
    const first = this.sessionRail[0];
    return first ? first.id : null;
  },
  selectedSessionRow() {
    return this.sessions.find((s) => s.id === this.selectedSessionId) || null;
  },
  /** What the episode pane shows while it has no episode: see {@link consolePaneState}. */
  consolePane() {
    return consolePaneState({
      sessionsLoaded: this.sessionsLoaded,
      selectedId: this.selectedSessionId,
      detailFor: this.sessionDetailFor,
      detail: this.sessionDetail,
    });
  },
  /** The selected session's current episode: header, timeline, and the earlier ones folded. */
  consoleEpisode() {
    const detail = this.sessionDetail;
    if (!detail || !detail.session || detail.session.id !== this.selectedSessionId) return null;
    const ep = currentEpisode(detail);
    if (!ep) return null;
    const timeline = episodeTimeline(ep, {
      actions: detail.actions,
      records: this.projectHistory,
      cards: this.cards,
      session: detail.session,
      current: true,
    });
    return {
      header: episodeHeader(ep, {
        timeline,
        records: this.projectHistory,
        episodes: detail.episodes,
        session: this.selectedSessionRow || detail.session,
        now: this.now,
      }),
      timeline,
      earlier: earlierEpisodes(detail),
    };
  },
  sessionChanges() {
    return this.sessionDetail ? sessionChanges(this.sessionDetail, this.projectHistory) : null;
  },
  sessionService() {
    return this.sessionDetail ? sessionService(this.sessionDetail, this.projectHistory, this.cards) : null;
  },
  needsYou() {
    return bannerRows(this.decisions, this.sessions);
  },
  /** The episode the run page's run was placed in, while the run page shows. */
  runEpisodeId() {
    if (this.view !== 'project' || this.filesOpen || !this.projectRun) return null;
    const card = this.projectRun.card;
    if (card.episode) return card.episode;
    // A run still in flight joins its session's current episode before its record names it.
    const s = this.projectRun.live ? sessionOfCard(card, this.sessions) : null;
    return s ? s.episode || null : null;
  },
  /** The run page's episode and attempt (`attempt 2 of "…"`), once the episode's session page is in. */
  runAttempt() {
    const run = this.projectRun;
    return run ? runAttempt(run.card, this.runEpisodePage, this.runEpisodeId) : null;
  },
  projectSessionRows() {
    const ps = this.projectSessions;
    if (!ps || ps.projectId !== this.selectedProjectId) return null;
    return projectSessionRows(ps.sessions, ps.details, ps.projectId);
  },
};

export const consoleMethods = {
  /** The rail's rows; then the selected session's page. */
  async loadSessions() {
    if (this.authModal) return;
    return this.fetchOnce('sessions', async () => {
      try {
        const body = await get('/api/sessions?limit=' + RAIL_LIMIT);
        this.sessions = (body && body.sessions) || [];
        const first = this.sessionRail[0];
        if (!this.sessions.some((s) => s.id === this.consoleAutoSession)) this.consoleAutoSession = first ? first.id : null;
      } catch (e) {
        if (this.handleHttpError(e)) return;
      }
      this.sessionsLoaded = true;
      await this.loadSessionDetail();
    });
  },
  /** The selected session's episodes and timeline. A response for a session no longer selected is dropped. */
  async loadSessionDetail(id = this.selectedSessionId) {
    if (this.authModal) return;
    if (!id) {
      this.sessionDetail = null;
      this.sessionDetailFor = null;
      this.markConsolePainted();
      return;
    }
    try {
      const page = await this.fetchOnce('session:' + id, () => fetchSessionDetail(get, id));
      if (id !== this.selectedSessionId) return;
      this.sessionDetail = page;
    } catch (e) {
      if (id === this.selectedSessionId) {
        this.sessionDetail = null;
        this.sessionDetailFor = id;
      }
      if (this.handleHttpError(e)) return;
    }
    if (id === this.selectedSessionId) this.sessionDetailFor = id;
    this.markConsolePainted();
  },
  /** Open decisions; an engine with no queue (404) leaves the banner absent. */
  async loadDecisions() {
    if (this.authModal) return;
    try {
      const { available, decisions } = await this.fetchOnce('decisions', () => fetchDecisions(get));
      this.decisionsAvailable = available;
      this.decisions = decisions;
    } catch (e) {
      this.handleHttpError(e);
    }
  },
  /** Every console read the landing page paints from, in parallel. */
  loadConsole() {
    this.loadSessions();
    this.loadDecisions();
  },
  /**
   * One session-log or decision frame. Returns true when the frame was one of those (so the
   * caller stops), false for every other event.
   */
  applyConsoleEvent(event) {
    const { type, data } = event;
    if (type === SSE.decision) {
      this.decisions = foldDecision(this.decisions, data);
      this.decisionEvent = data; // the open decision dialog follows it too
      const stamp = frameStamp(type, data);
      if (stamp) this.markConsoleEvent(stamp, () => this.needsYou.some((d) => d.id === stamp.id));
      return true;
    }
    if (type !== LOG.session && type !== LOG.episode && type !== LOG.action) return false;
    if (type === LOG.session) this.sessions = foldSessionFrame(this.sessions, data);
    if (type === LOG.episode) this.sessions = foldEpisodeIntoRail(this.sessions, data);
    this.sessionDetail = foldIntoDetail(this.sessionDetail, type, data);
    const stamp = frameStamp(type, data);
    if (stamp) {
      const shown = () => {
        const detail = this.sessionDetail;
        return !!detail && !!detail.session && detail.session.id === data.session && (detail.actions || []).some((a) => a.seq === data.action.seq);
      };
      this.markConsoleEvent(stamp, shown);
    }
    return true;
  },
  /**
   * An action or decision frame on screen: once Vue has patched it in and the browser has painted
   * it, `performance.mark('jk-console-event', { detail })` with the frame's `kind`, `id` and
   * `publishedAt`, the wall clock it painted at (`paintedAt`), `latencyMs` between the two, and
   * `shown`: whether the console is up and the frame is on it (the selected session's timeline,
   * the Needs-you banner). The scoreboard's console dimension reads these. A hidden tab, which
   * gets no frames to paint, marks nothing.
   */
  markConsoleEvent(stamp, shown) {
    if (typeof performance === 'undefined' || typeof performance.mark !== 'function') return;
    if (typeof requestAnimationFrame !== 'function' || typeof document === 'undefined' || document.hidden) return;
    this.$nextTick(() =>
      requestAnimationFrame(() =>
        setTimeout(() => {
          this._eventMarks = (this._eventMarks || 0) + 1;
          if (this._eventMarks > EVENT_MARKS) {
            performance.clearMarks('jk-console-event');
            this._eventMarks = 1;
          }
          const paintedAt = Date.now();
          const detail = { ...stamp, paintedAt, latencyMs: paintedAt - stamp.publishedAt, shown: this.view === 'console' && shown() };
          performance.mark('jk-console-event', { detail });
        }, 0),
      ),
    );
  },
  /**
   * Runs are not action frames: a finished run reaches the selected session's page on its next
   * read, debounced so a burst of finishes is one read.
   */
  scheduleSessionReload() {
    clearTimeout(this._sessionReloadTimer);
    this._sessionReloadTimer = setTimeout(() => {
      this.loadSessionDetail();
      if (this.runEpisodePage) this.loadRunEpisode(true);
    }, 300);
  },
  selectSession(id) {
    location.hash = buildConsoleHash(id);
  },
  /** An `edited N files` path → the file read-only over the page; focus returns to the link on close. */
  openEditedFile(item, file, event) {
    if (!item || !item.projectId || !file || !file.viewable) return;
    this._fileViewOpener = event && event.currentTarget ? event.currentTarget : null;
    this.fileView = { projectId: item.projectId, dir: item.dir || null, path: file.path, mark: file.mark || '' };
  },
  closeFileView() {
    this.fileView = null;
    const opener = this._fileViewOpener;
    this._fileViewOpener = null;
    if (opener && opener.isConnected) this.$nextTick(() => opener.focus());
  },
  /** A timeline run → its run page; a run with no number yet opens the project, which follows it. */
  openRun(item) {
    const projectId = item.projectId || (item.card && item.card.projectId);
    if (!projectId) return;
    location.hash = buildProjectHash({ projectId, run: item.buildNumber || 0 });
  },
  /** The session page that holds the run page's episode, for its title and the attempt number. */
  async loadRunEpisode(force = false) {
    const sessionId = sessionOfEpisode(this.runEpisodeId);
    if (!sessionId) return;
    if (!force && this.runEpisodePage && this.runEpisodePage.session.id === sessionId) return;
    try {
      const page = await this.fetchOnce('run-session:' + sessionId, () =>
        get('/api/sessions/' + encodeURIComponent(sessionId) + '?limit=1000'),
      );
      this.runEpisodePage = page;
    } catch (e) {
      this.handleHttpError(e);
    }
  },
  /** The Sessions tab: this project's sessions, and the pages of the newest to list their episodes. */
  async loadProjectSessions(projectId = this.selectedProjectId) {
    if (this.authModal || !projectId) return;
    try {
      const body = await get('/api/sessions?limit=' + PROJECT_LIMIT);
      const all = (body && body.sessions) || [];
      const mine = all.filter((s) => s.projectId === projectId || (s.current && s.current.projectId === projectId));
      const details = {};
      await Promise.all(
        mine.slice(0, PROJECT_PAGES).map(async (s) => {
          try {
            details[s.id] = await get('/api/sessions/' + encodeURIComponent(s.id) + '?limit=1');
          } catch {
            // the row's current episode stands in for its page
          }
        }),
      );
      if (projectId !== this.selectedProjectId) return;
      this.projectSessions = { projectId, sessions: mine, details };
    } catch (e) {
      this.handleHttpError(e);
    }
  },
  projectTabHash(tab) {
    return buildProjectHash({ projectId: this.selectedProjectId, dir: this.selectedProjectDir, sessions: tab === 'sessions' });
  },
  consoleHash(id) {
    return buildConsoleHash(id);
  },
  /**
   * The console's first meaningful paint: the rail and the selected episode rendered (or the empty
   * state, with no sessions). Marked once per page load, after Vue has patched the DOM and the
   * browser has painted it, as `jk-console-painted` and on `<html data-console-painted-ms>` — what
   * the scoreboard's console dimension reads.
   */
  markConsolePainted() {
    if (this.consolePaintedMs != null || this.view !== 'console' || typeof performance === 'undefined') return;
    if (typeof requestAnimationFrame !== 'function' || typeof document === 'undefined') return;
    // A frame callback runs before its paint; the task after it runs once the frame is on screen.
    this.$nextTick(() =>
      requestAnimationFrame(() =>
        setTimeout(() => {
          if (this.consolePaintedMs != null) return;
          this.consolePaintedMs = Math.round(performance.now());
          performance.mark('jk-console-painted');
          document.documentElement.dataset.consolePaintedMs = String(this.consolePaintedMs);
        }, 0),
      ),
    );
  },
  /** An earlier episode's timeline, opened from its fold. */
  earlierTimeline(episodeId) {
    const detail = this.sessionDetail;
    const ep = detail && (detail.episodes || []).find((e) => e.id === episodeId);
    return ep ? episodeTimeline(ep, { actions: detail.actions, records: this.projectHistory, cards: this.cards }) : [];
  },
  hostOf(s) {
    return hostLabel(s);
  },
  doingLine(card) {
    return doingLine(card);
  },
  doingSegments(card) {
    return doingSegments(card);
  },
  moduleCells(card) {
    return moduleCells(card);
  },
  workerLanes(card) {
    return workerLanes(card);
  },
  /** The glyph of a timeline line, by its family and outcome. */
  timelineIcon(item) {
    if (item.family === 'run') return { run: 'play', ok: 'check', err: 'x', cancelled: 'ban' }[item.state] || 'dot';
    return { graph: 'search', rename: 'pencil', deps: 'box', edit: 'pencil' }[item.family] || 'dot';
  },
  /** A timeline stamp: local `14:02`. */
  fmtClock24(millis) {
    if (!millis) return '';
    const d = new Date(millis);
    return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  },
  railStateLabel(state) {
    return { waiting: 'waiting on you', working: 'working', idle: 'idle', ended: 'ended' }[state] || state;
  },
  /** The chip class of an episode outcome: working is blue, green ok, red failed, abandoned muted. */
  outcomeClass(outcome) {
    return { working: 'running', green: 'success', red: 'failed', abandoned: 'cancelled' }[outcome] || 'cancelled';
  },
};
