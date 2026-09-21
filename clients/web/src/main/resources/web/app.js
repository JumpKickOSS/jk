// SPDX-License-Identifier: Apache-2.0
// The dashboard root: one component over four views (Activity, Projects, Project, Status), mounted
// by Vue 3 from the CDN. The in-DOM template lives in index.html and Vue's runtime compiler turns
// it into render functions at load. This file owns the shell — auth, the SSE connection, the hash
// route and the cross-view fetches; each view's own methods live beside it.

import {
  bootstrapToken,
  token,
  clearToken,
  get,
  getText,
  post,
  del,
  events,
  noteEngineEpoch,
  hardRefreshForEpoch,
} from './api.js';
import { cardMethods } from './cards.js';
import { BuildBars, ModuleDepGraph } from './chart.js';
import { CodeView } from './code.js';
import { foldEvent, seedFromHistory } from './fold.js';
import {
  ago as agoOf,
  agoBreakdown,
  count,
  fmtBytes,
  fmtClockSeconds,
  fmtDateTime,
  fmtDuration,
  gib,
  mib,
} from './format.js';
import { JkIcon } from './icons.js';
import { PhaseChain } from './phase.js';
import { RunCoverage } from './coverage.js';
import { RunDelta } from './delta.js';
import { projectComputed, projectMethods } from './projects.js';
import { FailReport } from './report.js';
import { buildProjectHash, routeFromHash } from './route.js';
import { sessionComputed, sessionMethods } from './sessions.js';
import { statusMethods } from './status.js';
import { installTips } from './tip.js';
import { EVENT, SSE } from './wire.js';
import { wizardComputed, wizardMethods } from './wizard.js';

// Guarded so the module can be imported headlessly under `node --test`.
if (typeof document !== 'undefined') bootstrapToken();

// Exported for the headless harness; the browser block below mounts it.
export const appOptions = {
  data: () => ({
    view: routeFromHash().view, // 'activity' | 'projects' | 'project' | 'status'
    selectedProjectId: routeFromHash().projectId, // durable id (#project/<id>)
    filesOpen: !!routeFromHash().files, // #project/<id>/files[/<rel>]
    codePath: routeFromHash().path,
    codeLine: routeFromHash().line,
    codeCol: routeFromHash().col || 0,
    codeLineErr: !!routeFromHash().lineErr,
    codeMsg: routeFromHash().msg || '',
    pinnedRun: routeFromHash().run || 0, // #project/<id>/run/<n>: 0 follows the newest run
    groupBySession: false, // Activity feed: one card per run, or runs grouped under the session that asked
    // The checkout the route names (#project/<id>?dir=…) — picked on the page, or carried by an
    // MCP / CLI link. Null while the id implies its one checkout; projectDir() folds in the
    // engine's answer.
    selectedProjectDir: routeFromHash().dir || null,
    projectMeta: null, // live /api/project payload (coord + description + dir + checkouts) for the open project
    // Dependencies panel on the Project page — closed by default; graph fetch + echarts
    // only when opened (ModuleDepGraph mounts lazily).
    projectGraphOpen: false,
    templatesUnavailable: false, // /api/templates failed — picker shows a notice, manual refs still work
    connection: 'connecting', // 'connecting' | 'live' | 'offline' | 'unauthorized'
    status: null, // the /api/status payload
    metrics: null, // the /api/metrics payload (running build aggregates), shown on the Status view
    cache: null, // /api/cache + live `cache` SSE: cache tier + artifact store breakdown
    engineLog: '', // the /api/log tail, shown on the Status view
    configPath: null, // absolute path of the machine config.toml
    configRows: [], // EffectiveUserConfig rows: {key, default, value, overridden}
    cards: [], // folded activity, newest first
    projectHistory: [], // raw /api/history records (up to 200), grouped into the Projects tab
    buildDir: '',
    buildError: null,
    browser: null, // the /api/fs payload while the workspace picker is open, else null
    browserMode: 'workspace', // 'workspace' | 'parent' (new-project parent dir)
    help: false, // the header Help/About modal
    // Blocking gate when a required token is missing/invalid — no partial dashboard (docs/webclient.md).
    authModal: false,
    authCopied: false, // brief "Copied" feedback on the Access Denied console copy button
    _authCopiedTimer: null,
    newProjectOpen: false,
    newProjectBusy: false,
    newProjectError: null,
    newProject: {
      name: '',
      group: '',
      lang: 'java',
      layout: 'traditional',
      framework: '',
      frameworkQuery: '',
      template: '',
      templateQuery: '',
      parentDir: '',
      executable: true,
    },
    frameworkOpen: false,
    templateOpen: false,
    templates: [],
    now: Date.now(), // 1s tick driving elapsed counters and "ago" stamps
    // single-flight keys → in-flight Promise; offline status poll backoff (ms).
    _inflight: Object.create(null),
    _offlineStatusBackoffMs: 5_000,
    _offlineStatusTimer: null,
    // projectId a meta load is running or has loaded for — the applyRoute reload guard keys on
    // this, not on projectMeta, which is null for the whole in-flight window.
    _projectMetaFor: null,
  }),

  async mounted() {
    // Gate before hydrating: a missing/invalid token must not leave a half-working UI.
    const authed = await this.checkAuth();
    if (authed) {
      this.connectEvents();
      this.refresh();
      this.loadHistory(); // backfill past builds so a reload/restart doesn't start from an empty feed
      this.loadProjectHistory(); // so the Projects tab is populated the moment it's opened
      if (this.view === 'project' && this.selectedProjectId) this.loadProjectMeta(this.selectedProjectId);
    }
    // Back/forward and any hash change re-derive the route (openProject sets the hash, which lands here).
    window.addEventListener('hashchange', () => this.applyRoute());
    // pause offline REST polling while the tab is hidden; keep SSE open (orphan engine).
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) this.clearOfflineStatusFallback();
      else if (this.connection !== 'live' && this.connection !== 'unauthorized') {
        this.scheduleOfflineStatusFallback();
      }
    });
    // Local clock only — no network (relative "ago" labels).
    setInterval(() => (this.now = Date.now()), 1_000);
  },

  computed: {
    ...wizardComputed,
    ...projectComputed,
    ...sessionComputed,
  },
  methods: {
    ...projectMethods,
    ...cardMethods,
    ...statusMethods,
    ...wizardMethods,
    ...sessionMethods,
    /**
     * Open the blocking auth dialog and freeze live updates. When {@code clear} is true, drop a
     * stored token that the engine just rejected.
     */
    markUnauthorized({ clear = false } = {}) {
      if (clear) clearToken();
      this.connection = 'unauthorized';
      this.authModal = true;
      this.clearOfflineStatusFallback();
      if (this._eventSource) {
        try {
          this._eventSource.close();
        } catch {
          // already closed
        }
        this._eventSource = null;
      }
      // Drop partial chrome so the gated shell does not look "live" under the dialog.
      this.status = null;
      this.cache = null;
      this.metrics = null;
      this.engineLog = '';
      this.configRows = [];
      this.configPath = null;
      this.cards = [];
      this.projectHistory = [];
      this.browser = null;
      this.help = false;
      this.newProjectOpen = false;
    },
    /** True when {@code e} is a 401 — marks unauthorized and returns true so callers can stop. */
    handleHttpError(e) {
      if (e && e.status === 401) {
        // Fail closed: any 401 (missing or rejected token) opens the blocking auth dialog.
        this.markUnauthorized({ clear: !!token() });
        return true;
      }
      return false;
    },
    /**
     * Session gate on load. Fails closed when there is no token, when {@code GET /api/status} is
     * 401, or when a stored token is rejected by a gated probe. Loopback is not a free pass —
     * without a bearer the SPA must not paint Activity (recover via {@code jk web} / status URL).
     */
    async checkAuth() {
      if (!token()) {
        this.markUnauthorized({ clear: false });
        return false;
      }
      try {
        const status = await get('/api/status', { bootstrap: true });
        // Latch the engine epoch from the bootstrap payload BEFORE any gated call.
        // Without this the next probe carries no X-Jk-Engine-Epoch, the server 409s, and every
        // fresh tab pays a full reload. A mismatch here means a stale epoch from a previous
        // engine generation survived in sessionStorage — reload once now, before painting.
        if (noteEngineEpoch(status) === 'mismatch') {
          hardRefreshForEpoch();
          return false;
        }
      } catch (e) {
        if (e.status === 401) {
          this.markUnauthorized({ clear: true });
          return false;
        }
        // engine down / network — not an auth failure (token is present; reconnect later)
        return true;
      }
      // Prove a stored token still works (stale localStorage after rotate-token is the common case).
      try {
        await getText('/api/log?lines=1');
      } catch (e) {
        if (e.status === 401) {
          this.markUnauthorized({ clear: true });
          return false;
        }
      }
      return true;
    },
    /** (Re)open the SSE stream — closed while unauthorized so a half-authed tab cannot look live. */
    connectEvents() {
      if (this._eventSource) {
        try {
          this._eventSource.close();
        } catch {
          // already closed
        }
        this._eventSource = null;
      }
      this._eventSource = events(
        (event) => {
          if (this.authModal || this.connection === 'unauthorized') return;
          // Live chrome vitals: change-gated on the server; apply without folding cards.
          if (event.type === EVENT.status) {
            this.applyStatusEvent(event.data);
            return;
          }
          if (event.type === SSE.cache) {
            this.applyCacheEvent(event.data);
            return;
          }
          foldEvent(this.cards, { ...event, at: Date.now() });
          // Keep footer Builds Running in lockstep with activity. Prefer the post-
          // transition count on the event when present; otherwise derive from running cards.
          if (event.type === SSE.requestStart || event.type === SSE.requestFinish) {
            this.applyActiveBuildPlans(event.data);
          }
          // The build number + journal record are written just after request-finish (writeJournal),
          // so re-pull history a beat later: it reconciles the live card (tagging its #number) and
          // refreshes the Projects tab. Debounced so a burst of finishes triggers one reload.
          if (event.type === SSE.requestFinish) {
            clearTimeout(this._reconcileTimer);
            this._reconcileTimer = setTimeout(() => {
              this.loadHistory();
              this.loadProjectHistory();
              // Metrics are view-scoped; refresh them only where they paint.
              if (this.view === 'status' || this.view === 'projects' || this.view === 'project') {
                this.refreshMetrics();
              }
              // A build can change the open project's coord/description/dir (manifest edits,
              // branch switches) — re-pull the header meta in place.
              this.refreshProjectMeta();
            }, 500);
          }
        },
        (state) => {
          // Unauthorized is sticky until a token is accepted — open loopback reads / SSE must not
          // clear the gate and leave a half-working UI.
          if (this.authModal || this.connection === 'unauthorized') return;
          const wasOffline = this.connection === 'offline';
          this.connection = state;
          if (state === 'live') {
            this._offlineStatusBackoffMs = 5_000;
            this.clearOfflineStatusFallback();
            // Always re-seed history on (re)connect: mid-flight enrichment + finished rows.
            // run-snapshot SSE covers the same instant for running jobs; history remains the
            // durable path and fills any race where the stream opened before the first GET.
            if (wasOffline) this.refresh(); // full chrome resync after an engine restart
            this.loadHistory();
            this.loadProjectHistory();
          } else if (state === 'offline') {
            // EventSource's own retry cadence (~3s on refused connections) is shorter than the
            // poll delay — re-arming on every onerror would perpetually reset the pending timer
            // and the fallback poll would never actually run.
            if (this._offlineStatusTimer == null) this.scheduleOfflineStatusFallback();
          }
        },
      );
    },
    setView(view) {
      if (this.authModal) return;
      this.view = view;
      history.replaceState(null, '', '#' + view);
      if (view === 'status') this.refresh(); // full cache + metrics + log
      if (view === 'projects') {
        this.loadProjectHistory();
        this.refreshMetrics(); // view-scoped, not a global timer
      }
    },
    // The Projects tab groups the same journal payload the feed seeds from — one GET serves
    // both (each /api/history hit re-enriches up to 200 rows engine-side).
    async loadProjectHistory() {
      return this.loadHistory();
    },
    // Open a project's page by durable id — hash creates a history entry so Back returns to the list.
    // The dir only resolves the id from a history row; the route names a checkout when the user
    // picks one, not on every click (pickCheckout).
    openProject(projectId, dir) {
      if (!projectId && dir) {
        // Resolve id from a history row when only path is known (rare).
        const hit = (this.projectHistory || []).find((r) => r.dir === dir && r.projectId);
        projectId = hit ? hit.projectId : null;
      }
      if (!projectId) return;
      location.hash = buildProjectHash({ projectId });
    },
    /** Show one checkout of the open project (null: all of them); the route carries the choice. */
    pickCheckout(dir) {
      if (!this.selectedProjectId) return;
      location.hash = buildProjectHash({ projectId: this.selectedProjectId, dir: dir || null });
    },
    /** The files pane's Back control: up one level to the project page, not out to the list. */
    closeCode() {
      const view = this.$refs.codeView;
      if (view && view.dirty && typeof view.confirmDiscard === 'function' && !view.confirmDiscard()) {
        return;
      }
      if (this.selectedProjectId) this.openProject(this.selectedProjectId);
      else this.setView('projects');
    },
    /** Activity card badge / coord → project detail (by projectId, with dir fallback). */
    openProjectFromCard(card) {
      if (!card) return;
      this.openProject(card.projectId, card.dir);
    },
    // Re-derive view + selected project from the hash, loading whatever that route needs.
    applyRoute() {
      if (this.authModal) return;
      const r = routeFromHash();
      const idChanged = r.projectId !== this.selectedProjectId;
      const dirChanged = (r.dir || null) !== this.selectedProjectDir;
      this.view = r.view;
      this.selectedProjectId = r.projectId;
      this.selectedProjectDir = r.dir || null;
      this.filesOpen = !!r.files;
      this.codePath = r.path;
      this.codeLine = r.line;
      this.codeCol = r.col || 0;
      this.codeLineErr = !!r.lineErr;
      this.codeMsg = r.msg || '';
      this.pinnedRun = r.run || 0;
      // Collapse the expensive graph panel when leaving project view or switching projects.
      if (r.view !== 'project' || idChanged || r.files) this.projectGraphOpen = false;
      // Project identity cannot change between two clicks on the same #project/<id> route, and
      // every /api/project hit re-runs identity resolution engine-side (git probe + project-home
      // scan) — so reload metadata only on an actual project switch or when it was never loaded.
      // This also stops the header flicker from nulling projectMeta per file click.
      // A load already running for this id counts as loaded: projectMeta is null for the whole
      // in-flight window, so testing only it would refetch on every file click until the response.
      if (
        r.view === 'project' &&
        r.projectId &&
        (idChanged || dirChanged || (!this.projectMeta && this._projectMetaFor !== r.projectId))
      ) {
        this.loadProjectMeta(r.projectId);
      }
      if (r.view === 'projects') {
        this.loadProjectHistory();
        this.refreshMetrics();
      }
      if (r.view === 'status') this.refresh();
    },
    browseCodebase() {
      this.openCode({ projectId: this.selectedProjectId });
    },
    /** `#project/<id>/run/<n>`, or the plain project route when `buildNumber` is 0 (follow newest). */
    projectRunHash(projectId, buildNumber) {
      return buildProjectHash({ projectId, dir: this.selectedProjectDir, run: buildNumber || 0 });
    },
    openCode({ projectId, path, line, col, err, msg, replace } = {}) {
      if (this.authModal) return;
      const id = projectId || this.selectedProjectId;
      if (!id) return;
      const hash = buildProjectHash({
        projectId: id,
        dir: this.selectedProjectDir,
        files: true,
        path: path || null,
        line: line || 0,
        col: col || 0,
        err: !!err,
        msg: msg || '',
      });
      if (replace) {
        // replaceState does not fire hashchange — apply the route ourselves.
        history.replaceState(null, '', hash);
        this.applyRoute();
      } else {
        location.hash = hash;
      }
    },
    onCodeNavigate({ path, line, replace }) {
      this.openCode({
        projectId: this.selectedProjectId,
        path,
        line: line || 0,
        replace: !!replace,
      });
    },
    /** Toggle the Project-page Dependencies accordion (lazy graph load on open). */
    toggleProjectGraph() {
      this.projectGraphOpen = !this.projectGraphOpen;
    },
    /**
     * The raw meta fetch — separated so the headless suite can control response timing. With a
     * checkout named, the engine answers that tree's card; without one it implies the single
     * live checkout or answers only the `checkouts` list.
     */
    fetchProjectMeta(projectId, dir) {
      return get(
        '/api/project?project=' + encodeURIComponent(projectId) + (dir ? '&dir=' + encodeURIComponent(dir) : ''),
      );
    },
    // Live coord + description for the open project (by durable id).
    async loadProjectMeta(projectId) {
      if (this.authModal) return;
      this._projectMetaFor = projectId;
      this.projectMeta = null;
      try {
        const meta = await this.fetchProjectMeta(projectId, this.selectedProjectDir);
        // A slow response for a project the user already navigated away from must not overwrite
        // the current project's state — with same-project refetches suppressed above, the stale
        // data would stick until the next switch.
        if (this.selectedProjectId !== projectId) return;
        this.projectMeta = meta;
      } catch (e) {
        // A failed load must not latch the guard — the next click retries.
        if (this._projectMetaFor === projectId) this._projectMetaFor = null;
        if (this.selectedProjectId !== projectId) return;
        this.handleHttpError(e);
      }
      if (!this.projectHistory.length) this.loadProjectHistory(); // detail rows come from history
    },
    /**
     * Re-pull the open project's meta in place — coord/description/dir go stale after a manifest
     * save or a finished build (branch switch, edited jk.toml). Unlike {@code loadProjectMeta}
     * this never nulls {@code projectMeta}, so the header keeps its last values instead of
     * flickering; errors keep the stale header rather than surfacing (the next full load does).
     */
    async refreshProjectMeta() {
      const projectId = this.selectedProjectId;
      if (this.authModal || this.view !== 'project' || !projectId) return;
      try {
        const meta = await this.fetchProjectMeta(projectId, this.selectedProjectDir);
        if (this.selectedProjectId !== projectId) return; // stale response
        this.projectMeta = meta;
        this._projectMetaFor = projectId;
      } catch {
        // keep the last known header
      }
    },
    /** A code-view save landed. Manifest edits change the header — re-pull the meta. */
    onCodeSaved({ path } = {}) {
      if (path === 'jk.toml' || (path && path.endsWith('/jk.toml'))) this.refreshProjectMeta();
    },
    // The "Build" button: kick off a fresh build of this project and jump to the live Activity feed.
    buildProject(dir) {
      this.triggerBuild(dir);
      this.setView('activity');
    },
    // Cancel a running job by jid (card.id === requestId/jid from request-start).
    async cancelCard(card) {
      if (card.id == null) return;
      try {
        await post('/api/cancel', { jid: card.id });
      } catch (e) {
        if (this.handleHttpError(e)) return;
        this.buildError = e.error || 'Cancel failed';
      }
    },
    /** Project page title — plain {@code group:name} (or name alone), same weight as other view h2s. */
    projectTitle() {
      const p = this.projectDetail;
      if (!p) return 'Project';
      if (p.group) return p.group + ':' + p.name;
      return p.name || 'Project';
    },
    /**
     * At most one in-flight REST call per key. Concurrent callers share the same Promise so
     * double refresh / reconnect cannot stack GETs for the same path.
     */
    fetchOnce(key, fn) {
      const inflight = this._inflight;
      if (inflight[key]) return inflight[key];
      const p = Promise.resolve()
        .then(fn)
        .finally(() => {
          if (inflight[key] === p) delete inflight[key];
        });
      inflight[key] = p;
      return p;
    },
    // Backfill the feed from the persisted journal (/api/history), reconciled with live cards.
    // Always reassign `this.cards` so a bulk seed after a hard-refresh repaints (Vue tracks the
    // array identity as well as mutations).
    async loadHistory() {
      if (this.authModal) return;
      return this.fetchOnce('history', async () => {
        try {
          const records = await get('/api/history');
          this.projectHistory = records; // shared with the Projects tab (loadProjectHistory)
          const next = this.cards.slice();
          seedFromHistory(next, records);
          this.cards = next;
        } catch (e) {
          this.handleHttpError(e);
        }
      });
    },
    // Delete a finished run from history (engine + disk), then drop its card locally.
    async deleteCard(card) {
      if (!card.historyId) return;
      try {
        await del('/api/history?id=' + encodeURIComponent(card.historyId));
        const i = this.cards.indexOf(card);
        if (i >= 0) this.cards.splice(i, 1);
      } catch (e) {
        if (this.handleHttpError(e)) return;
        this.buildError = 'Could not delete this run';
      }
    },
    async triggerBuild(dir) {
      if (this.authModal) return;
      this.buildError = null;
      const target = (dir ?? this.buildDir).trim();
      if (!target) return;
      try {
        await post('/api/build', { dir: target });
        if (dir == null) this.buildDir = '';
      } catch (e) {
        if (this.handleHttpError(e)) return;
        this.buildError = e.error || 'Build request failed';
      }
    },
    shortDir(dir) {
      const parts = dir.split('/').filter(Boolean);
      return parts.length > 2 ? '…/' + parts.slice(-2).join('/') : dir;
    },
    lastSegment(dir) {
      const parts = dir.split('/').filter(Boolean);
      return parts.length ? parts[parts.length - 1] : dir;
    },
    connectionLabel() {
      return {
        connecting: 'Connecting…',
        live: 'Live',
        offline: 'Engine stopped — run any jk command to restart it',
        unauthorized: 'Access denied — run `jk web` and follow the instructions',
      }[this.connection];
    },
    /** Copy `jk web` for the Access Denied console — clipboard only, never the prompt glyph. */
    async copyJkWeb() {
      const text = 'jk web';
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(text);
        } else {
          const ta = document.createElement('textarea');
          ta.value = text;
          ta.setAttribute('readonly', '');
          ta.style.position = 'fixed';
          ta.style.left = '-9999px';
          document.body.appendChild(ta);
          ta.select();
          document.execCommand('copy');
          document.body.removeChild(ta);
        }
        this.authCopied = true;
        if (this._authCopiedTimer) clearTimeout(this._authCopiedTimer);
        this._authCopiedTimer = setTimeout(() => {
          this.authCopied = false;
          this._authCopiedTimer = null;
        }, 1500);
      } catch (_) {
        // Clipboard blocked — user can still select the command text.
      }
    },
    // Formatting has one owner (format.js). These entries exist so the in-DOM template can reach
    // it; `ago` and `agoLong` take the reactive 1s clock rather than reading the wall clock, or the
    // relative times would never re-render.
    fmtDateTime,
    fmtClockSeconds,
    count,
    mib,
    gib,
    duration: fmtDuration,
    bytes: fmtBytes,
    ago(millis) {
      return agoOf(millis, this.now);
    },
    agoLong(millis) {
      return agoBreakdown(millis, this.now);
    },
  },
};

if (typeof Vue !== 'undefined' && typeof document !== 'undefined') {
  Vue.createApp(appOptions)
    .component('jk-icon', JkIcon)
    .component('phase-chain', PhaseChain)
    .component('run-delta', RunDelta)
    .component('run-coverage', RunCoverage)
    .component('fail-report', FailReport)
    .component('build-bars', BuildBars)
    .component('module-dep-graph', ModuleDepGraph)
    .component('code-view', CodeView)
    .mount('#app');

  // Themed tooltips for data-tip / title (native title= is unstyleable OS chrome).
  installTips(document);
}
