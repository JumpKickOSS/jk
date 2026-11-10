// SPDX-License-Identifier: Apache-2.0
// The Activity page: what happened on this machine, newest first. The engine merges the change
// journal, the decision history (who asked, which surface answered, how it reached the person) and
// the bell's notices into GET /api/activity, one page at a time behind a cursor, so a long history
// never slows the dashboard. Opening the page moves the bell's seen marker: history seen is news seen.
//
// Routes: #activity, #activity?kind=change&project=…&session=…&surface=…, and #activity/<entry id>,
// which opens that entry (a change's diff, a decision's outcome) once its page has loaded.

import { get } from './api.js';

/** How many entries one page asks for. */
export const PAGE = 50;

/** How many more pages a deep link to an older entry loads, looking for it, before it gives up. */
export const FIND_PAGES = 10;

/** The filters, in the order the page shows them. */
export const FILTERS = ['kind', 'project', 'session', 'surface'];

const KIND_LABELS = {
  notification: 'Notifications',
  decision: 'Decisions',
  change: 'Changes',
  setup: 'Machine setup',
  install: 'Installs',
};

const SURFACE_LABELS = {
  cli: 'Terminal',
  dashboard: 'Dashboard',
  agent: "Agent's host",
  import: 'Import',
  engine: 'JumpKick',
};

const CHANNEL_LABELS = {
  'in-app': 'in the dashboard',
  browser: 'browser notification',
  native: 'desktop notification',
  open: 'opened the browser',
  none: 'link only',
};

const OUTCOME_LABELS = { open: 'waiting', accepted: 'accepted', declined: 'declined', expired: 'expired' };

export function activityKindLabel(kind) {
  return KIND_LABELS[kind] || kind;
}

export function surfaceLabel(surface) {
  return SURFACE_LABELS[surface] || surface || '';
}

export function channelLabel(channel) {
  return CHANNEL_LABELS[channel] || channel || '';
}

export function outcomeLabel(outcome) {
  return OUTCOME_LABELS[outcome] || outcome || '';
}

/** The filters a route names: only the known keys, blank ones left out. */
export function activityFilters(query) {
  const out = {};
  for (const k of FILTERS) {
    const v = query && query[k];
    if (v != null && String(v).trim() !== '') out[k] = String(v);
  }
  return out;
}

/** `/api/activity?…` for one page under `filters`, after `before` (the page before's `next`). */
export function activityUrl(filters = {}, before = null, limit = PAGE) {
  const q = [];
  for (const k of FILTERS) if (filters[k]) q.push(k + '=' + encodeURIComponent(filters[k]));
  if (before) q.push('before=' + encodeURIComponent(before));
  q.push('limit=' + limit);
  return '/api/activity?' + q.join('&');
}

/** `#activity`, with the filters and an opened entry when given. */
export function buildActivityHash(filters = {}, entryId = null) {
  let h = '#activity';
  // Entry ids are `change:…`, `decision:…`, `notice:…`: the colon stays readable in the hash.
  if (entryId) h += '/' + encodeURIComponent(entryId).replace(/%3A/gi, ':');
  const q = [];
  for (const k of FILTERS) if (filters[k]) q.push(k + '=' + encodeURIComponent(filters[k]));
  return q.length ? h + '?' + q.join('&') : h;
}

/** Appends `page`'s entries to `entries`, each once, newest first. */
export function mergePage(entries, page) {
  const seen = new Set((entries || []).map((e) => e.id));
  const out = (entries || []).slice();
  for (const e of (page && page.entries) || []) {
    if (!seen.has(e.id)) {
      seen.add(e.id);
      out.push(e);
    }
  }
  return out.sort((a, b) => b.at - a.at || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0));
}

/**
 * The project page an entry's checkout or build file belongs to: the history row whose directory
 * holds it, the longest such, so a nested module's file finds its own project. Null for none.
 */
export function projectFor(path, rows) {
  if (!path) return null;
  const p = String(path).replace(/\\/g, '/');
  let best = null;
  for (const r of Array.isArray(rows) ? rows : []) {
    if (!r || !r.dir || !r.projectId) continue;
    const dir = String(r.dir).replace(/\\/g, '/').replace(/\/+$/, '');
    if ((p === dir || p.startsWith(dir + '/')) && (!best || dir.length > best.dir.length)) best = { dir, projectId: r.projectId };
  }
  return best;
}

/** The project's page link for an entry, or null when no known project holds it. */
export function projectHref(entry, rows) {
  const hit = projectFor(entry && entry.project, rows);
  return hit ? '#project/' + encodeURIComponent(hit.projectId) : null;
}

/**
 * One row as the page shows it: the line, who, where and how, and the links. A decision links to
 * its dialog; every entry also links to itself (#activity/<id>), which opens it in place.
 */
export function activityRow(entry, rows) {
  const meta = [entry.who];
  // A notice JumpKick raised by itself says so once, not again as its surface.
  if (entry.surface && entry.surface !== 'engine') meta.push((entry.decision ? 'answered in ' : 'from ') + surfaceLabel(entry.surface));
  if (entry.channel) meta.push(channelLabel(entry.channel));
  return {
    id: entry.id,
    kind: entry.kind,
    at: entry.at,
    title: entry.title,
    meta: meta.filter(Boolean).join(' · '),
    outcome: entry.outcome ? outcomeLabel(entry.outcome) : '',
    href: entry.href,
    self: buildActivityHash({}, entry.id),
    project: projectHref(entry, rows),
    diff: entry.diff || '',
    detail: entry.detail || [],
  };
}

/** A unified diff as lines, each with the class its first character earns. */
export function diffLines(diff) {
  return String(diff || '')
    .split('\n')
    .filter((l, i, all) => !(i === all.length - 1 && l === ''))
    .map((l) => ({
      text: l,
      cls: l.startsWith('+++') || l.startsWith('---') ? 'diff-file' : l.startsWith('@@') ? 'diff-hunk' : l.startsWith('+') ? 'diff-add' : l.startsWith('-') ? 'diff-del' : '',
    }));
}

/** The root component's share. */
export function activityData(route = {}) {
  return {
    activity: [],
    activityNext: null,
    activityFacets: null,
    activityFilter: activityFilters(route.activityFilter || {}),
    activityOpen: route.activityEntry || null,
    activityLoading: false,
    activityError: '',
  };
}

export const activityMethods = {
  /** Loads the first page under the current filters; the page opening moves the seen marker. */
  async loadActivity() {
    this.activity = [];
    this.activityNext = null;
    this.activityError = '';
    if (this.markSeen) this.markSeen();
    await this.moreActivity(true);
    // A deep link to an older entry: keep paging until it shows, a few pages at most.
    for (let i = 0; this.activityOpen && this.activityNext && i < FIND_PAGES; i++) {
      if (this.activity.some((e) => e.id === this.activityOpen)) break;
      await this.moreActivity();
    }
  },
  /** The next page, appended; nothing while one loads or when there is no more. */
  async moreActivity(first = false) {
    if (this.activityLoading || (!first && !this.activityNext)) return;
    this.activityLoading = true;
    try {
      const page = await get(activityUrl(this.activityFilter, first ? null : this.activityNext));
      this.activity = mergePage(first ? [] : this.activity, page);
      this.activityNext = (page && page.next) || null;
      if (page && page.facets) this.activityFacets = page.facets;
    } catch (e) {
      if (e && e.status === 404) this.activityError = 'This engine keeps no activity yet. Update JumpKick to see it.';
      else if (this.handleHttpError) this.handleHttpError(e);
    } finally {
      this.activityLoading = false;
    }
  },
  /** Sets one filter (blank clears it); the route carries the filters, so Back undoes it. */
  setActivityFilter(key, value) {
    const next = { ...this.activityFilter };
    if (value) next[key] = value;
    else delete next[key];
    location.hash = buildActivityHash(next);
  },
  /** Opens or closes one entry in place; the route remembers it. */
  toggleActivity(id) {
    const open = this.activityOpen === id ? null : id;
    history.replaceState(null, '', buildActivityHash(this.activityFilter, open));
    this.activityOpen = open;
  },
  /** The rows the page renders. */
  activityRows() {
    return (this.activity || []).map((e) => activityRow(e, this.projectHistory));
  },
};
