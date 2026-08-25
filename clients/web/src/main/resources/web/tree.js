// SPDX-License-Identifier: Apache-2.0
// The file-tree model behind the files pane: flat servable paths in, a directory-first render tree
// and its visible-row projection out. Pure functions of (paths, expanded) so the pane's expand and
// filter behaviour is testable without a DOM.

const byName = (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });

/**
 * Flat servable paths → a render-ready directory tree: at every level directories come first, then
 * files, each side name-sorted — case-insensitive and numeric-aware, so `build.gradle.kts` sits
 * next to `README.md` and `Step2` after `Step10` does not happen.
 *
 * A directory whose only child is another directory is **compacted** into one node —
 * `core` + `src/main/java/cc/jumpkick` become a single row — so a Java source file is a handful of
 * rows deep instead of a dozen. The compacted node keeps the *deepest* path as its key, which is
 * what {@link ancestorDirs} produces for a file under it.
 */
export function buildFileTree(paths) {
  const root = { name: '', path: '', dirs: new Map(), files: [] };
  for (const raw of paths || []) {
    const segs = String(raw || '').split('/').filter(Boolean);
    if (!segs.length) continue;
    let node = root;
    for (let i = 0; i < segs.length - 1; i++) {
      let next = node.dirs.get(segs[i]);
      if (!next) {
        next = { name: segs[i], path: segs.slice(0, i + 1).join('/'), dirs: new Map(), files: [] };
        node.dirs.set(segs[i], next);
      }
      node = next;
    }
    node.files.push({ name: segs[segs.length - 1], path: segs.join('/'), dir: false });
  }
  const finish = (node) => {
    const children = [...node.dirs.values()].sort(byName).map(finish).concat(node.files.sort(byName));
    let out = { name: node.name, path: node.path, dir: true, children };
    // Bottom-up, so a chain of single-child dirs folds into one node in a single pass.
    while (out.children.length === 1 && out.children[0].dir) {
      const only = out.children[0];
      out = { name: out.name + '/' + only.name, path: only.path, dir: true, children: only.children };
    }
    return out;
  };
  // The root itself is never a row, so it never compacts — a lone top-level dir stays clickable.
  return {
    children: [...root.dirs.values()].sort(byName).map(finish).concat(root.files.sort(byName)),
  };
}

/** Flatten the open parts of a tree into rows — one flat v-for beats a recursive component here. */
export function visibleRows(tree, expanded) {
  const open = expanded || {};
  const out = [];
  const walk = (nodes, depth) => {
    for (const node of nodes) {
      if (!node.dir) {
        out.push({ name: node.name, path: node.path, dir: false, depth });
        continue;
      }
      const isOpen = !!open[node.path];
      out.push({ name: node.name, path: node.path, dir: true, depth, open: isOpen });
      if (isOpen) walk(node.children, depth + 1);
    }
  };
  walk((tree && tree.children) || [], 0);
  return out;
}

/** Every directory prefix of a file path — the keys to expand so a deep link lands revealed. */
export function ancestorDirs(path) {
  const segs = String(path || '').split('/').filter(Boolean);
  segs.pop(); // the file itself
  const out = [];
  let acc = '';
  for (const seg of segs) {
    acc = acc ? acc + '/' + seg : seg;
    out.push(acc);
  }
  return out;
}

/** The build definition is the one file every jk workspace has, so it opens by default. */
export const DEFAULT_FILE = 'jk.toml';

/**
 * The file to open when the route names none: the workspace-root `jk.toml`, and only if the list
 * actually served it — a member's `sub/jk.toml` is not the workspace, and an imported Maven/Gradle
 * tree has none at all, which is what leaves the pane on its empty state.
 */
export function defaultFilePath(files) {
  return (files || []).some((f) => f && f.path === DEFAULT_FILE) ? DEFAULT_FILE : null;
}

export function plainRows(content) {
  const text = content == null ? '' : String(content);
  if (text === '') return [''];
  const parts = text.split('\n');
  if (parts.length && parts[parts.length - 1] === '') parts.pop();
  return parts.length ? parts : [''];
}
