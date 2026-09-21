// SPDX-License-Identifier: Apache-2.0
// The dashboard's URL: `#project/<id>/files/<rel>?line=..&col=..` parsed into a route object and
// rendered back. The hash is the whole client-side router, so a deep link into a failing line is
// just a string both directions.

export function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function decodeComp(raw) {
  try {
    return decodeURIComponent(raw);
  } catch {
    return null;
  }
}

export function parseHashQuery(hash) {
  const h = String(hash || '');
  const q = h.indexOf('?');
  if (q < 0) return {};
  const out = Object.create(null);
  for (const pair of h.slice(q + 1).split('&')) {
    const eq = pair.indexOf('=');
    if (eq <= 0) continue;
    const k = decodeComp(pair.slice(0, eq));
    const v = decodeComp(pair.slice(eq + 1).replace(/\+/g, ' '));
    if (k != null && v != null) out[k] = v;
  }
  return out;
}

function parseLine(raw) {
  const line = parseInt(raw, 10);
  return Number.isFinite(line) && line > 0 ? line : 0;
}

/** Truthy query values: 1 / true / yes / on (case-insensitive). */
export function parseTruthy(raw) {
  if (raw == null || raw === '') return false;
  const t = String(raw).trim().toLowerCase();
  return t === '1' || t === 'true' || t === 'yes' || t === 'on';
}

/**
 * Hash routes:
 *  #project/<id>
 *  #project/<id>/run/<buildNumber>            (pin one run; without it the page follows the newest)
 *  #project/<id>/files
 *  #project/<id>/files/<rel/path>?line=<n>
 *  #project/<id>/files/<rel/path>?line=<n>&col=<c>&err=true&msg=<note>  (fail-report / OSC-8)
 *
 * Any project route may carry `?dir=<checkout>`: every worktree of a repository shares the id, so
 * the checkout names which tree the page shows and the files pane edits. Without it the engine
 * implies the one live checkout, or the page lists several to pick from.
 */
function routeFields(over = {}) {
  return {
    view: 'activity',
    projectId: null,
    dir: null,
    files: false,
    path: null,
    line: 0,
    col: 0,
    lineErr: false,
    msg: '',
    run: 0,
    ...over,
  };
}

export function routeFromHash(hash = typeof location !== 'undefined' ? location.hash : '') {
  const h = hash || '';
  const q = parseHashQuery(h);
  const pathPart = h.indexOf('?') >= 0 ? h.slice(0, h.indexOf('?')) : h;
  const empty = routeFields();
  if (pathPart === '#projects') {
    return routeFields({ view: 'projects' });
  }
  if (pathPart === '#status') {
    return routeFields({ view: 'status' });
  }
  if (pathPart.startsWith('#project/')) {
    const segs = pathPart.slice('#project/'.length).split('/');
    const id = decodeComp(segs[0] || '');
    if (!id) return empty;
    const dir = q.dir || null;
    if (segs[1] === 'files') {
      const rel = [];
      for (const raw of segs.slice(2)) {
        if (raw === '') continue;
        const s = decodeComp(raw);
        if (s == null || s === '' || s === '.' || s === '..') {
          return routeFields({
            view: 'project',
            projectId: id,
            dir,
            files: true,
            line: parseLine(q.line),
            col: parseLine(q.col),
            lineErr: parseTruthy(q.err),
            msg: q.msg || '',
          });
        }
        rel.push(s);
      }
      return routeFields({
        view: 'project',
        projectId: id,
        dir,
        files: true,
        path: rel.length ? rel.join('/') : null,
        line: parseLine(q.line),
        col: parseLine(q.col),
        lineErr: parseTruthy(q.err),
        msg: q.msg || '',
      });
    }
    if (segs[1] === 'run') {
      return routeFields({ view: 'project', projectId: id, dir, run: parseLine(segs[2]) });
    }
    return routeFields({ view: 'project', projectId: id, dir });
  }
  return empty;
}

export function buildProjectHash({ projectId, dir, files, path, line, col, err, msg, run } = {}) {
  if (!projectId) return '#projects';
  let h = '#project/' + encodeURIComponent(projectId);
  const query = [];
  if (run > 0 && !files && !path) {
    h += '/run/' + run;
  } else {
    if (files || path) {
      h += '/files';
      if (path) {
        h +=
          '/' +
          String(path)
            .split('/')
            .filter(Boolean)
            .map((s) => encodeURIComponent(s))
            .join('/');
      }
    }
    if (line > 0) {
      query.push('line=' + line);
      if (col > 0) query.push('col=' + col);
      if (err) query.push('err=true');
      const note = clipHashMsg(msg);
      if (note) query.push('msg=' + encodeURIComponent(note));
    }
  }
  if (dir) query.push('dir=' + encodeURIComponent(dir));
  return query.length ? h + '?' + query.join('&') : h;
}

/**
 * Visible locus on a failure path: {@code path}, {@code path:line}, or {@code path:line:col}.
 * Copy-paste still carries the jump after the hash / OSC-8 link is stripped.
 */
export function locusLabel(path, line = 0, col = 0) {
  const p = path == null ? '' : String(path);
  if (!p) return '';
  const n = Number(line) || 0;
  if (n < 1) return p;
  const c = Number(col) || 0;
  return c > 0 ? p + ':' + n + ':' + c : p + ':' + n;
}

/** Keep hash / OSC-8 URLs from ballooning; compiler notes are a few short lines. */
export function clipHashMsg(msg, max = 800) {
  const t = msg == null ? '' : String(msg).trim();
  if (!t) return '';
  if (t.length <= max) return t;
  return t.slice(0, Math.max(1, max - 1)) + '…';
}
