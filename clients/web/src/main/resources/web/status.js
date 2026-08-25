// SPDX-License-Identifier: Apache-2.0
// The Status view and the polling behind it: the /api/status, /api/cache, /api/metrics, /api/log
// and /api/config reads, the offline fallback that takes over when the SSE stream drops, and the
// storage and system meters those payloads drive. Spread into the root component's `methods`.

import { get, getText, hardRefreshForEpoch, noteEngineEpoch } from './api.js';
import { ago } from './format.js';
import { outcomeOf } from './outcome.js';

export const statusMethods = {
  clearOfflineStatusFallback() {
    if (this._offlineStatusTimer != null) {
      clearTimeout(this._offlineStatusTimer);
      this._offlineStatusTimer = null;
    }
  },
  /**
   * Offline-only status poll with stepped backoff (5s → … → 30s). No-op while SSE is live or
   * the document is hidden (keep EventSource open; do not REST-hammer a background tab).
   */
  scheduleOfflineStatusFallback() {
    this.clearOfflineStatusFallback();
    if (document.hidden || this.connection === 'live' || this.connection === 'unauthorized') return;
    const delay = this._offlineStatusBackoffMs || 5_000;
    this._offlineStatusTimer = setTimeout(async () => {
      this._offlineStatusTimer = null;
      if (document.hidden || this.connection === 'live' || this.connection === 'unauthorized') return;
      // A non-200 answer (503 during an engine respawn, exhausted SSE budget, 421) kills
      // EventSource for good — it only auto-reconnects after network errors. The offline poll
      // doubles as the reconnect probe so the stream comes back once the engine is healthy
      //.
      if (this._eventSource && this._eventSource.readyState === EventSource.CLOSED) {
        this.connectEvents();
      }
      await this.refreshStatus();
      this._offlineStatusBackoffMs = Math.min(30_000, Math.round((this._offlineStatusBackoffMs || 5_000) * 1.5));
      this.scheduleOfflineStatusFallback();
    }, delay);
  },
  // Merge a live `status` SSE frame into this.status. Frames carry core vitals only (not httpUrl
  // / config knobs from GET /api/status) — keep REST fields when present.
  applyStatusEvent(data) {
    if (this.authModal || this.connection === 'unauthorized') return;
    if (!data || typeof data !== 'object') return;
    if (noteEngineEpoch(data) === 'mismatch') {
      hardRefreshForEpoch();
      return;
    }
    this.status = this.status ? { ...this.status, ...data } : { ...data };
  },
  /**
   * Immediate plan-count update from request-start/finish. When the event carries
   * {@code activeBuildPlans}, use it; otherwise count running activity cards so the footer
   * never lags the Live feed.
   */
  applyActiveBuildPlans(data) {
    const n =
      data && typeof data.activeBuildPlans === 'number'
        ? data.activeBuildPlans
        : this.runningCardCount();
    if (!this.status) this.status = {};
    this.status = { ...this.status, activeBuildPlans: n };
  },
  /** Number of Live activity cards still in flight. */
  runningCardCount() {
    let n = 0;
    for (const c of this.cards || []) {
      if (outcomeOf(c) === 'running') n++;
    }
    return n;
  },
  // Thin live `cache` frames merge into the last full REST snapshot; full frames replace.
  applyCacheEvent(data) {
    if (this.authModal || this.connection === 'unauthorized') return;
    if (!data || typeof data !== 'object') return;
    if (data.thin) {
      const prev = this.cache || {};
      this.cache = { ...prev, ...data };
    } else {
      this.cache = data;
    }
  },
  // REST hydrate / offline fallback for header sysbox + footer heap / builds-running.
  async refreshStatus() {
    if (this.authModal) return;
    return this.fetchOnce('status', async () => {
      try {
        const s = await get('/api/status', { bootstrap: true });
        if (noteEngineEpoch(s) === 'mismatch') {
          hardRefreshForEpoch();
          return;
        }
        this.status = s;
      } catch (e) {
        this.handleHttpError(e);
      }
    });
  },
  /** Full cache breakdown for Status panels (REST). Footer uses thin SSE while live. */
  async refreshCache() {
    if (this.authModal) return;
    return this.fetchOnce('cache', async () => {
      try {
        this.cache = await get('/api/cache');
      } catch (e) {
        this.handleHttpError(e);
      }
    });
  },
  /**
   * Running build aggregates — view-scoped, not a global chrome poll. Call when
   * opening Status/Projects or after a finished build while those views are visible.
   */
  async refreshMetrics() {
    if (this.authModal) return;
    return this.fetchOnce('metrics', async () => {
      try {
        this.metrics = await get('/api/metrics');
      } catch (e) {
        this.handleHttpError(e);
      }
    });
  },
  async refreshLog() {
    if (this.authModal) return;
    return this.fetchOnce('log', async () => {
      try {
        this.engineLog = await getText('/api/log?lines=100');
      } catch (e) {
        if (this.handleHttpError(e)) return;
      }
    });
  },
  /** Effective machine config.toml (key / default / override) for the Status Configuration panel. */
  async refreshConfig() {
    if (this.authModal) return;
    return this.fetchOnce('config', async () => {
      try {
        const payload = await get('/api/config');
        this.configPath = payload.path || null;
        this.configRows = Array.isArray(payload.rows) ? payload.rows : [];
      } catch (e) {
        this.handleHttpError(e);
      }
    });
  },
  /**
   * Hydrate REST surfaces for the current view. Status always pulls full cache + metrics + log;
   * Projects pulls metrics; Activity only needs status/cache hydrate when offline or first paint.
   */
  async refresh(opts) {
    if (this.authModal) return;
    const sseLive = this.connection === 'live';
    const wantStatus = !opts || opts.status !== false;
    if (wantStatus) await this.refreshStatus();

    if (this.view === 'status') {
      await this.refreshLog();
      await this.refreshConfig();
      await this.refreshMetrics();
      await this.refreshCache(); // full breakdown for dual Status panels
      return;
    }
    if (this.view === 'projects' || this.view === 'project') {
      await this.refreshMetrics();
    }
    // Cache tier + artifact store footer: prefer thin SSE while live; REST only while no
    // cache frame has arrived at all. A first-ever connect gets no SSE cache hydrate (no
    // snapshot captured yet) and the safety-net sampler ticks every 60s, so without this
    // one-shot the footer showed dashes for up to a minute. Snapshot walks are
    // single-flight + TTL-memoized engine-side, so this cannot storm the store (-era
    // CacheSnapshot).
    if (!this.cache) {
      await this.refreshCache();
    }
  },
  /** Action-cache bytes (CLI: jk cache usage) — action index + cache CAS. */
  actionCacheBytes() {
    return this.cache?.actionCacheBytes ?? null;
  },
  actionMaxBytes() {
    return this.cache?.actionMaxBytes ?? null;
  },
  /** Artifact store: store CAS + worker jars (CLI: jk storage usage). Never budgeted. */
  artifactStorageBytes() {
    return this.cache?.artifactStorageBytes ?? null;
  },
  actionCacheUtilizationPercent() {
    const used = this.actionCacheBytes();
    const max = this.actionMaxBytes();
    if (used == null || !max || max <= 0) return 0;
    return Math.min(100, Math.round((100 * used) / max));
  },
  prunedAgo() {
    return this.cache?.lastPrunedMillis ? ago(this.cache.lastPrunedMillis, this.now) : 'never';
  },
  /** Format-stamp count cap; from the API, or the local fallback below. */
  formatStampsMax() {
    const m = this.cache?.formatStampsMax;
    if (m != null && m > 0) return m;
    return 65536;
  },
  formatStampsMaxLabel() {
    return this.formatStampsMax().toLocaleString();
  },
  /** Percent of stamp-file cap in use (one decimal), not byte utilization. */
  formatStampsUsedPct() {
    const n = this.cache?.formatStampsCount;
    const max = this.formatStampsMax();
    if (n == null || max <= 0) return '—';
    return ((100 * n) / max).toFixed(1);
  },
  /** Zinc analysis state vs its own budget — never the action budget; the two are separate tiers. */
  incrementalUsedPct() {
    const used = this.cache?.incrementalBytes;
    const max = this.cache?.incrementalMaxBytes;
    if (used == null || !max || max <= 0) return '—';
    return Math.min(100, (100 * used) / max).toFixed(1);
  },
  // The machine-wide invocation rows (one per kind: build, test), stable order.
  metricsGlobal() {
    return (this.metrics || [])
      .filter((r) => r.scope === 'global')
      .sort((a, b) => a.kind.localeCompare(b.kind));
  },
  // Machine-wide per-task rows (wire: scope "task", name in "task"), biggest total first.
  metricsSteps() {
    return (this.metrics || [])
      .filter((r) => r.scope === 'task')
      .sort((a, b) => b.okTotalMillis - a.okTotalMillis)
      .slice(0, 10);
  },
  // Sums across kinds for the KPI tiles: total runs, ok, failed+cancelled, total wall-clock.
  metricsTotals() {
    const g = this.metricsGlobal();
    const sum = (f) => g.reduce((acc, r) => acc + f(r), 0);
    const ok = sum((r) => r.okCount);
    const bad = sum((r) => r.failCount) + sum((r) => r.cancelledCount);
    return { runs: ok + bad, ok, bad, totalMillis: sum((r) => r.okTotalMillis + r.failTotalMillis) };
  },
  successRate() {
    const t = this.metricsTotals();
    return t.runs === 0 ? '—' : Math.round((100 * t.ok) / t.runs) + '%';
  },
  // Header / about: whole-host CPU utilisation from /api/status systemCpuLoad ∈ [0,1].
  // The bean returns -1 until the first sample; show an em-dash rather than "0%".
  loadPercent() {
    const n = this.cpuPercent();
    return n == null ? '—' : n + '%';
  },
  /** Whole-host CPU % for the header sysbox meter, or null when not yet sampled. */
  cpuPercent() {
    const load = this.status?.systemCpuLoad;
    if (load == null || load < 0) return null;
    return Math.min(100, Math.round(load * 100));
  },
  /**
   * Host RAM used % for the header sysbox: (total − available) / total.
   * availableMemoryBytes is available headroom (see StatusSnapshot).
   */
  ramPercent() {
    const s = this.status;
    if (!s || s.totalMemoryBytes == null || s.totalMemoryBytes <= 0) return null;
    const avail = s.availableMemoryBytes;
    if (avail == null || avail < 0) return null;
    const used = Math.max(0, s.totalMemoryBytes - avail);
    return Math.min(100, Math.round((100 * used) / s.totalMemoryBytes));
  },
  /** Used host RAM in bytes (total − available), or null. */
  ramUsedBytes() {
    const s = this.status;
    if (!s || s.totalMemoryBytes == null || s.totalMemoryBytes <= 0) return null;
    const avail = s.availableMemoryBytes;
    if (avail == null || avail < 0) return null;
    return Math.max(0, s.totalMemoryBytes - avail);
  },
  /** 1-minute load average formatted to one decimal, or null when unobservable. */
  loadAverageText() {
    const avg = this.status?.systemLoadAverage;
    if (avg == null || avg < 0) return null;
    return avg.toFixed(1);
  },
  /** Sysbox percent label ({@code 6%}, {@code 51%}); em-dash when unsampled. */
  sysMeterPct(pct) {
    return pct == null ? '—' : pct + '%';
  },
  /**
   * CPU sysbox tip on the % / load text: total cores + 1m load as "cores used".
   * Example: {@code 24 cores. 1.4 cores used recently}
   */
  cpuSysTip() {
    const cores = this.status?.cores;
    const load = this.loadAverageText();
    if (cores == null && load == null) return '';
    const c = cores != null ? String(cores) : '—';
    const l = load != null ? load : '—';
    return c + ' cores. ' + l + ' cores used recently';
  },
  /**
   * RAM sysbox tip on the % / used text: total + used.
   * Example: {@code 30.4 GiB total RAM. 15.6 GiB used.}
   */
  ramSysTip() {
    const total = this.status?.totalMemoryBytes;
    const used = this.ramUsedBytes();
    if (total == null && used == null) return '';
    return this.gib(total) + ' total RAM. ' + this.gib(used) + ' used.';
  },
  /** CSS level on a sysrow: cyan default, warn >90%, crit >97%. */
  sysMeterLevel(pct) {
    if (pct == null) return '';
    if (pct > 97) return 'crit';
    if (pct > 90) return 'warn';
    return '';
  },
  // Header version pill: "v0.10.0" — the build-metadata suffix (-SNAPSHOT) is dropped for the chip.
  versionPill() {
    return this.status ? 'v' + String(this.status.version).replace(/-SNAPSHOT$/, '') : '';
  },
  /**
   * Footer "Builds Running": lockstep with Live activity while live.
   * Offline falls back to last status snapshot.
   */
  buildsRunning() {
    if (this.connection === 'live') return this.runningCardCount();
    return this.status?.activeBuildPlans ?? 0;
  },
  heapUsedPercent() {
    return this.percentOfMax(this.status?.heapUsedBytes);
  },
  heapCommittedPercent() {
    return this.percentOfMax(this.status?.heapCommittedBytes);
  },
  percentOfMax(bytes) {
    const s = this.status;
    if (!s || s.heapMaxBytes <= 0 || bytes == null || bytes < 0) return 0;
    return Math.min(100, Math.round((100 * bytes) / s.heapMaxBytes));
  },
  uptime() {
    const s = this.status;
    if (!s) return '—';
    const total = Math.max(0, Math.floor((this.now - s.startedAt) / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    return h + 'h ' + m + 'm ' + (total % 60) + 's';
  },
};
