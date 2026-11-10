// SPDX-License-Identifier: Apache-2.0
// The file-tree model behind the files pane. The engine answers one directory at a time
// (GET /api/project/dir), so the pane holds a map of the directories it has loaded, keyed by path
// ('' is the checkout root), and these pure functions turn that map and the open folders into the
// rows a virtual list draws. Nothing here touches the DOM.

/** Height of one tree row in px: the virtual list's unit, and .code-row's fixed height in style.css. */
export const ROW_HEIGHT = 24;

/** Rows drawn above and below the viewport, so a fling does not show blank space. */
export const OVERSCAN = 12;

/**
 * The open tree as flat rows, depth-first. A loaded directory contributes its entries in the
 * engine's order (folders first, folded package chains as one row); an open folder still loading
 * shows a `loading` row, a failed one an `error` row, and a directory with more pages ends in a
 * `more` row. Every row has a unique `key`.
 *
 * `dirs`: `{ [path]: { entries, next, total, loading, error } }`. `expanded`: `{ [path]: true }`.
 */
export function treeRows(dirs, expanded) {
  const out = [];
  const open = expanded || {};
  const walk = (path, depth) => {
    const dir = dirs && dirs[path];
    if (!dir) return;
    if (dir.error && !(dir.entries && dir.entries.length)) {
      out.push({ kind: 'error', key: path + '\0error', path, depth, text: dir.error });
      return;
    }
    for (const e of dir.entries || []) {
      if (!e.dir) {
        out.push({ kind: 'file', key: e.path, name: e.name, path: e.path, depth, lang: e.lang || null, output: !!e.output });
        continue;
      }
      const isOpen = !!open[e.path];
      out.push({
        kind: 'dir',
        key: e.path,
        name: e.name,
        path: e.path,
        depth,
        open: isOpen,
        output: !!e.output,
        empty: !!e.empty,
      });
      if (!isOpen || e.empty) continue;
      const child = dirs[e.path];
      if (!child || (child.loading && !(child.entries && child.entries.length))) {
        out.push({ kind: 'loading', key: e.path + '\0loading', path: e.path, depth: depth + 1 });
      } else {
        walk(e.path, depth + 1);
      }
    }
    if (dir.next) {
      const shown = (dir.entries || []).length;
      out.push({
        kind: 'more',
        key: path + '\0more',
        path,
        depth,
        remaining: Math.max(0, (dir.total || 0) - shown),
        loading: !!dir.loading,
      });
    }
  };
  walk('', 0);
  return out;
}

/**
 * The slice of `count` rows a viewport of `height` px scrolled to `scrollTop` shows, widened by
 * `overscan` rows each side: `{ start, end, offset, total }`, `end` exclusive, `offset` the px the
 * slice is pushed down, `total` the full height the scroller must have.
 */
export function visibleWindow(count, scrollTop, height, rowHeight = ROW_HEIGHT, overscan = OVERSCAN) {
  const n = Math.max(0, count | 0);
  const first = Math.floor(Math.max(0, scrollTop || 0) / rowHeight);
  const shown = Math.ceil(Math.max(0, height || 0) / rowHeight) + 1;
  const start = Math.max(0, Math.min(n, first - overscan));
  const end = Math.max(start, Math.min(n, first + shown + overscan));
  return { start, end, offset: start * rowHeight, total: n * rowHeight };
}

/**
 * The entry of a directory listing on the way to `target`: the file itself, or the folder (a folded
 * chain counts by its deepest path) that holds it. Null when the listing does not have it.
 */
export function entryToward(entries, target) {
  const t = String(target || '');
  if (!t) return null;
  for (const e of entries || []) {
    if (e.path === t) return e;
    if (e.dir && t.startsWith(e.path + '/')) return e;
  }
  return null;
}

/**
 * Forget a folder: its own listing, everything loaded below it, and the open state of what is
 * below. Collapsing a folder calls this, so what the pane holds is bounded by what is open, and
 * opening it again fetches it fresh. Returns new `{ dirs, expanded }` objects.
 */
export function dropSubtree(dirs, expanded, path) {
  const under = (k) => k === path || k.startsWith(path + '/');
  const nextDirs = {};
  for (const [k, v] of Object.entries(dirs || {})) if (!under(k)) nextDirs[k] = v;
  const nextOpen = {};
  for (const [k, v] of Object.entries(expanded || {})) if (!under(k)) nextOpen[k] = v;
  return { dirs: nextDirs, expanded: nextOpen };
}

/** The build definition is the one file every jk workspace has, so it opens by default. */
export const DEFAULT_FILE = 'jk.toml';

/**
 * The file to open when the route names none: the workspace-root `jk.toml`, and only if the root
 * listing has it — a member's `sub/jk.toml` is not the workspace, and an imported Maven/Gradle tree
 * has none at all, which is what leaves the pane on its empty state.
 */
export function defaultFilePath(rootEntries) {
  return (rootEntries || []).some((e) => e && !e.dir && e.path === DEFAULT_FILE) ? DEFAULT_FILE : null;
}

export function plainRows(content) {
  const text = content == null ? '' : String(content);
  if (text === '') return [''];
  const parts = text.split('\n');
  if (parts.length && parts[parts.length - 1] === '') parts.pop();
  return parts.length ? parts : [''];
}
