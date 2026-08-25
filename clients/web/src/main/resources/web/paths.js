// SPDX-License-Identifier: Apache-2.0
// Workspace paths as the files pane sees them: which extension is which language, which files the
// Preview button may open, and how a markdown-relative href or a build diagnostic's file resolves
// to a path under the project checkout. Pure string work — no DOM, no network.

/** Server vocabulary too — WorkspaceFileAccess.LANG_BY_EXT gates which files are servable. */
const LANG_BY_EXT = {
  '.java': 'java',
  '.kt': 'kotlin',
  '.kts': 'kotlin',
  '.groovy': 'groovy',
  '.scala': 'scala',
  '.sc': 'scala',
  '.toml': 'toml',
  '.xml': 'xml',
  '.yaml': 'yaml',
  '.yml': 'yaml',
  '.json': 'json',
  '.jsonl': 'json',
  '.sql': 'sql',
  '.properties': 'properties',
  '.sh': 'shell',
  '.bash': 'shell',
  '.zsh': 'shell',
  '.md': 'markdown',
  '.markdown': 'markdown',
  '.mmd': 'mermaid',
  '.mermaid': 'mermaid',
  '.dot': 'graphviz',
  '.gv': 'graphviz',
  '.adoc': 'asciidoc',
  '.asciidoc': 'asciidoc',
  '.d2': 'd2',
  '.png': 'image',
  '.jpg': 'image',
  '.jpeg': 'image',
  '.gif': 'image',
  '.webp': 'image',
  '.ico': 'image',
  '.svg': 'image',
  '.bmp': 'image',
  '.avif': 'image',
};

/** Extensions the Preview button may open (client-side; server allow-list must match). */
const PREVIEW_KINDS = {
  '.md': 'markdown',
  '.markdown': 'markdown',
  '.png': 'image',
  '.jpg': 'image',
  '.jpeg': 'image',
  '.gif': 'image',
  '.webp': 'image',
  '.ico': 'image',
  '.svg': 'image',
  '.bmp': 'image',
  '.avif': 'image',
  '.mmd': 'mermaid',
  '.mermaid': 'mermaid',
  '.dot': 'graphviz',
  '.gv': 'graphviz',
  '.adoc': 'asciidoc',
  '.asciidoc': 'asciidoc',
  '.d2': 'd2',
};

export function previewKind(path) {
  const name = String(path || '').split('/').pop() || '';
  const lower = name.toLowerCase();
  const dot = lower.lastIndexOf('.');
  if (dot < 0) return null;
  return PREVIEW_KINDS[lower.slice(dot)] || null;
}

export function isPreviewable(path) {
  return previewKind(path) != null;
}

export function isImagePath(path) {
  return previewKind(path) === 'image';
}

export function isTextWritableLang(lang) {
  return !!lang && lang !== 'image';
}

/** Final path segment for the open-file tab label (full path stays on the tooltip). */
export function baseFileName(path) {
  const parts = String(path || '').split('/');
  return parts[parts.length - 1] || path || '';
}

/** True for absolute http(s) image URLs (badges, GitHub user-attachments, etc.). */
export function isRemoteHttpUrl(href) {
  if (href == null) return false;
  const h = String(href).trim();
  return /^https?:\/\//i.test(h) || h.startsWith('//');
}

/**
 * Resolve a markdown href against the open file into a workspace-relative path, or null when
 * external / escapes the root. Leading {@code /} means workspace root (GitHub-style).
 * Fragment/query are stripped ({@code docs/x.md#sec} → {@code docs/x.md}).
 */
export function resolveWorkspaceRelPath(fromFile, href) {
  if (href == null) return null;
  let h = String(href).trim();
  if (!h) return null;
  // Protocol-relative or absolute URLs are remote (not in-workspace).
  if (/^[a-z][a-z0-9+.-]*:/i.test(h) || h.startsWith('//')) return null;
  // In-page anchors only.
  if (h.startsWith('#')) return null;
  h = h.split('#')[0].split('?')[0];
  if (!h) return null;
  try {
    h = decodeURIComponent(h);
  } catch {
    return null;
  }
  h = h.replace(/\\/g, '/');
  let parts;
  if (h.startsWith('/')) {
    parts = h.split('/').filter(Boolean);
  } else {
    const base = String(fromFile || '')
      .replace(/\\/g, '/')
      .split('/')
      .filter(Boolean);
    if (base.length) base.pop(); // directory of the open markdown file
    parts = base.concat(h.split('/').filter((p) => p !== ''));
  }
  const out = [];
  for (const p of parts) {
    if (p === '.') continue;
    if (p === '..') {
      if (!out.length) return null;
      out.pop();
      continue;
    }
    if (p.includes('\0')) return null;
    out.push(p);
  }
  if (!out.length) return null;
  return out.join('/');
}

/**
 * Image-only resolve: workspace-relative path must be a previewable image type.
 * Remote http(s) returns null — remote images stay on their own URLs (no proxy).
 */
export function resolveMarkdownImagePath(fromFile, href) {
  const rel = resolveWorkspaceRelPath(fromFile, href);
  if (!rel || !isImagePath(rel)) return null;
  return rel;
}

/**
 * Link resolve for markdown {@code [text](path)} / badge targets — any workspace-relative path.
 */
export function resolveMarkdownLinkPath(fromFile, href) {
  return resolveWorkspaceRelPath(fromFile, href);
}

/**
 * Pull fenced ```mermaid blocks out of markdown so the rest can go through marked, then re-inject
 * rendered SVGs by placeholder. Placeholders are plain tokens that survive DOMPurify.
 */
export function extractMermaidFences(markdown) {
  const src = markdown == null ? '' : String(markdown);
  const fences = [];
  // Opening fence at line start; language is mermaid (optional trailing attrs ignored).
  const re = /^[ \t]*```[ \t]*mermaid[ \t]*\r?\n([\s\S]*?)^[ \t]*```[ \t]*$/gim;
  const markdownOut = src.replace(re, (_, body) => {
    const i = fences.length;
    fences.push(String(body).replace(/\r\n/g, '\n').replace(/\s+$/, ''));
    return '\n\nJKMERMAIDPLACEHOLDER' + i + 'X\n\n';
  });
  return { markdown: markdownOut, fences };
}

/** Replace JKMERMAIDPLACEHOLDER{n}X tokens with the corresponding rendered SVG (or error HTML). */
export function injectMermaidSvgs(html, svgs) {
  let out = html == null ? '' : String(html);
  const list = svgs || [];
  for (let i = 0; i < list.length; i++) {
    const token = 'JKMERMAIDPLACEHOLDER' + i + 'X';
    const svg = list[i] == null ? '' : String(list[i]);
    out = out.split(token).join(svg);
  }
  return out;
}

/**
 * One DOMPurify chokepoint for renderer SVG (mermaid, graphviz, d2): SVG profile plus filters.
 * Renderer output derives from workspace source text — never inject it unsanitized.
 */
export function sanitizeDiagramSvg(purify, svg) {
  return purify.sanitize(svg == null ? '' : String(svg), {
    USE_PROFILES: { svg: true, svgFilters: true },
  });
}

/** Human-readable save failure (network / auth / concurrency). */
export function saveErrorMessage(e) {
  if (!e) return 'Failed to save file';
  if (e.status === 409 && e.error === 'file changed on disk') {
    return 'File changed on disk — reload it to edit the latest version';
  }
  if (e.status === 409 && (e.error === 'engine-epoch-mismatch' || e.engineEpoch)) {
    return 'Engine restarted — reload the page';
  }
  if (e.status === 401) return 'Not authorized — run jk web for a fresh token';
  if (e.status === 413) return 'File too large to save (1 MiB limit)';
  if (e.status === 415) return 'This file type cannot be saved from the editor';
  if (e.status === 404) return 'File no longer exists on disk';
  if (e.status === 500) return (e.error && String(e.error)) || 'Engine failed to write the file';
  // fetch network failure (TypeError) or missing status
  if (e.status == null || e.name === 'TypeError') {
    return 'Cannot reach the engine — is it running?';
  }
  if (e.error) return String(e.error);
  return 'Save failed (HTTP ' + e.status + ')';
}

export function langFromPath(path) {
  const name = String(path || '').split('/').pop() || '';
  const lower = name.toLowerCase();
  const dot = lower.lastIndexOf('.');
  if (dot < 0) return null;
  return LANG_BY_EXT[lower.slice(dot)] || null;
}

export function posixJoin(...parts) {
  return parts
    .filter((p) => p != null && p !== '' && p !== '.')
    .join('/')
    .replace(/\/+/g, '/');
}

export function relativizeUnder(root, abs) {
  if (!root || !abs) return null;
  const norm = (s) =>
    String(s)
      .replace(/\\/g, '/')
      .replace(/\/+$/, '');
  const r = norm(root);
  const a = norm(abs);
  if (a === r) return '';
  const prefix = r.endsWith('/') ? r : r + '/';
  if (!a.startsWith(prefix)) return null;
  return a.slice(prefix.length);
}

/**
 * Workspace-relative path for a fail-report jump into {@code #project/<id>/files/…}.
 *
 * Test snippets carry a <em>module-relative</em> path. Live single-plan builds use the empty
 * {@code SINGLE_PLAN_DIR} for the module row, so {@code moduleDir} is often {@code ''} — treat that
 * (and module === checkout) as "file is already checkout-relative". Basename-only paths stay
 * non-linkable (ambiguous under multi-module trees).
 */
export function codePathForFailure({ checkoutDir, moduleDir, file } = {}) {
  if (!file) return null;
  const isAbs = file.startsWith('/') || /^[A-Za-z]:[\\/]/.test(file);
  if (isAbs) {
    if (!checkoutDir) return null;
    const rel = relativizeUnder(checkoutDir, file);
    if (rel == null || rel.split('/').includes('..')) return null;
    return rel;
  }
  if (!file.includes('/')) return null;
  if (file.split('/').includes('..')) return null;
  // Empty moduleDir: single-plan live key — file is already workspace/checkout-relative.
  if (!moduleDir) return file;
  if (!checkoutDir) return null;
  const moduleRel = relativizeUnder(checkoutDir, moduleDir);
  if (moduleRel == null) return null;
  const joined = posixJoin(moduleRel, file);
  if (!joined || joined.split('/').includes('..')) return null;
  return joined;
}
