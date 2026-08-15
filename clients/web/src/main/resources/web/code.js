// SPDX-License-Identifier: Apache-2.0
// Dashboard project file viewer: #project/<id>/files/<rel>, Monaco (vs-dark), <code-view>.

export const HIGHLIGHT_MAX_BYTES = 200 * 1024;
export const HIGHLIGHT_MAX_LINES = 4000;

const MONACO = '0.56.0';

/** Same CDN origin as Vue/ECharts in index.html, so the shell's CSP needs no new host. */
const MONACO_BASE = `https://unpkg.com/monaco-editor@${MONACO}/min/vs`;

/**
 * Only the AMD loader can be SRI-pinned: it then fetches `editor.main.js`, `editor.main.css` and
 * the per-language chunks itself, and the loader has no integrity plumbing to pass down. Moving
 * the pin means recomputing this one hash (docs/webclient.md).
 */
const MONACO_LOADER = {
  src: `${MONACO_BASE}/loader.js`,
  integrity: 'sha384-jwxunh5pHKFzi60XobDcwOM7qnk4lSGpdBU/hNn9pUGp+ZUpaHJqadNLRF2k+jPu',
};

/**
 * The built-in Visual Studio Dark theme, with one override: the canvas is the shell's console
 * background (`--console-bg`) instead of VS Code's #1e1e1e, so a source pane reads as the same
 * surface as the console tail / log panels. Every token colour, widget and ruler stays vs-dark's.
 */
const MONACO_THEME_BASE = 'vs-dark';
export const MONACO_THEME = 'jk-vs-dark';

/** style.css's `--console-bg`; the literal is the fallback for a missing/rewritten token. */
export const CONSOLE_BG_FALLBACK = '#0b1116';

export function consoleBackground(root) {
  const el = root || (typeof document !== 'undefined' ? document.documentElement : null);
  if (!el || typeof getComputedStyle !== 'function') return CONSOLE_BG_FALLBACK;
  const raw = getComputedStyle(el).getPropertyValue('--console-bg').trim();
  // Monaco only accepts #rgb/#rrggbb(aa) here and throws on anything else, so anything exotic
  // (a var() chain, a colour function, an empty string) falls back rather than breaking the pane.
  return /^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(raw) ? raw : CONSOLE_BG_FALLBACK;
}

/** `inherit: true` + no rules: vs-dark verbatim, minus the background. */
export function themeDefinition(background) {
  return {
    base: MONACO_THEME_BASE,
    inherit: true,
    rules: [],
    colors: { 'editor.background': background || CONSOLE_BG_FALLBACK },
  };
}

/** Server vocabulary too — WorkspaceFileAccess.LANG_BY_EXT gates which files are servable. */
const LANG_BY_EXT = {
  '.java': 'java',
  '.kt': 'kotlin',
  '.kts': 'kotlin',
  '.groovy': 'groovy',
  '.toml': 'toml',
  '.json': 'json',
  '.jsonl': 'json',
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

/**
 * Monaco language ids for the langs above. Monaco ships no Groovy or TOML grammar, so those fall
 * back to the closest one it has: Java (C-like — comments, strings, numbers, most keywords) and
 * ini (`[section]`, `key = value`, `#` comments). Anything unknown tokenizes as plain text rather
 * than handing Monaco an unregistered id.
 */
const MONACO_LANG = {
  java: 'java',
  kotlin: 'kotlin',
  groovy: 'java',
  toml: 'ini',
  json: 'json',
  markdown: 'markdown',
  mermaid: 'plaintext',
  graphviz: 'plaintext',
  asciidoc: 'plaintext',
  d2: 'plaintext',
  image: 'plaintext',
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

function escapeHtml(s) {
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
 *   #project/<id>
 *   #project/<id>/files
 *   #project/<id>/files/<rel/path>?line=<n>
 *   #project/<id>/files/<rel/path>?line=<n>&err=true  (fail-report / OSC-8 jump)
 */
export function routeFromHash(hash = typeof location !== 'undefined' ? location.hash : '') {
  const h = hash || '';
  const q = parseHashQuery(h);
  const pathPart = h.indexOf('?') >= 0 ? h.slice(0, h.indexOf('?')) : h;
  const empty = { view: 'activity', projectId: null, files: false, path: null, line: 0, lineErr: false };
  if (pathPart === '#projects') {
    return { view: 'projects', projectId: null, files: false, path: null, line: 0, lineErr: false };
  }
  if (pathPart === '#status') {
    return { view: 'status', projectId: null, files: false, path: null, line: 0, lineErr: false };
  }
  if (pathPart.startsWith('#project/')) {
    const segs = pathPart.slice('#project/'.length).split('/');
    const id = decodeComp(segs[0] || '');
    if (!id) return empty;
    if (segs[1] === 'files') {
      const rel = [];
      for (const raw of segs.slice(2)) {
        if (raw === '') continue;
        const s = decodeComp(raw);
        if (s == null || s === '' || s === '.' || s === '..') {
          return {
            view: 'project',
            projectId: id,
            files: true,
            path: null,
            line: parseLine(q.line),
            lineErr: parseTruthy(q.err),
          };
        }
        rel.push(s);
      }
      return {
        view: 'project',
        projectId: id,
        files: true,
        path: rel.length ? rel.join('/') : null,
        line: parseLine(q.line),
        lineErr: parseTruthy(q.err),
      };
    }
    return { view: 'project', projectId: id, files: false, path: null, line: 0, lineErr: false };
  }
  return empty;
}

export function buildProjectHash({ projectId, files, path, line, err } = {}) {
  if (!projectId) return '#projects';
  let h = '#project/' + encodeURIComponent(projectId);
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
    h += '?line=' + line;
    if (err) h += '&err=true';
  }
  return h;
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

/** Server lang (or {@link langFromPath}) → a language id Monaco actually has registered. */
export function monacoLang(lang) {
  return MONACO_LANG[String(lang || '')] || 'plaintext';
}

/**
 * `?line=` whole-line decoration — plain IRange objects, testable without a monaco global.
 * Empty for line 0. Neutral (cyan/soft) by default; {@code err: true} uses the fail-report red wash
 * ({@code ?line=N&err=true} from Activity / OSC-8).
 */
export function lineDecorations(line, err = false) {
  const n = Number(line) || 0;
  if (n < 1) return [];
  const error = !!err;
  const opts = {
    isWholeLine: true,
    className: error ? 'code-line-err' : 'code-line-hl',
    linesDecorationsClassName: error ? 'code-line-err-gutter' : 'code-line-hl-gutter',
  };
  if (error) {
    // Stick the mark in the overview/minimap so a long file still shows where the jump landed.
    opts.overviewRuler = { color: 'rgba(255, 51, 102, 0.85)', position: 1 };
    opts.minimap = { color: 'rgba(255, 51, 102, 0.85)', position: 1 };
  }
  return [
    {
      range: { startLineNumber: n, startColumn: 1, endLineNumber: n, endColumn: 1 },
      options: opts,
    },
  ];
}

/**
 * Options for `monaco.editor.create` — light editor: no context menu / suggestions; `?line=` is
 * still the only whole-line decoration (`renderLineHighlight: 'none'`). Content rides `value`
 * (a model string Monaco tokenizes), never innerHTML.
 */
export function viewerOptions({ content, lang, readOnly = false } = {}) {
  return {
    value: content == null ? '' : String(content),
    language: monacoLang(lang),
    theme: MONACO_THEME,
    readOnly: !!readOnly,
    domReadOnly: !!readOnly,
    automaticLayout: true,
    // Mirrors --mono / .code-pre in style.css so the pane matches the rest of the shell.
    fontFamily:
      '"JetBrainsMono Nerd Font Mono", "JetBrains Mono Nerd Font Mono", "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: 12.5,
    lineHeight: 19,
    lineNumbers: 'on',
    minimap: { enabled: true, renderCharacters: false },
    scrollBeyondLastLine: false,
    renderLineHighlight: 'none',
    occurrencesHighlight: 'off',
    selectionHighlight: false,
    contextmenu: false,
    quickSuggestions: false,
    stickyScroll: { enabled: false },
    scrollbar: { useShadows: false },
    wordWrap: 'off',
    tabSize: 4,
  };
}

/**
 * Preview renderers (unpkg only; never self-hosted).
 * marked + DOMPurify load as ESM via dynamic import() so we never park Monaco's AMD {@code define}
 * during README Preview (parking races Monaco's on-demand markdown grammar → "define is not a function").
 * Mermaid / viz / asciidoctor still use classic UMD + noAmd, and only when that diagram kind is needed.
 */
const CDN = {
  markedEsm: 'https://unpkg.com/marked@15.0.12/lib/marked.esm.js',
  purifyEsm: 'https://unpkg.com/dompurify@3.2.5/dist/purify.es.mjs',
  mermaid: {
    src: 'https://unpkg.com/mermaid@11.6.0/dist/mermaid.min.js',
    integrity: 'sha384-zkWMJO4sgpPUzyuOgDx8HB/K55glbAwajEpk1Go2NWRuPkPA/wIhoEJTuSkmOYrV',
  },
  viz: {
    src: 'https://unpkg.com/@viz-js/viz@3.11.0/lib/viz-standalone.js',
    integrity: 'sha384-6ECxW8G4FmLMEr8fKkk617NsYhxb7AhsiyyT6Gey/Sru4R+lyRpVM12ekzlOD08g',
  },
  asciidoctor: {
    src: 'https://unpkg.com/@asciidoctor/core@3.0.4/dist/browser/asciidoctor.min.js',
    integrity: 'sha384-Rd2/5b41kZm7C1w7DbZXiNbMElh62qr8urCNwxmNaYVvL06PjEwpmw1PRGqAwOZP',
  },
};

const previewLoaders = Object.create(null);

async function ensureCdn(name) {
  if (previewLoaders[name]) return previewLoaders[name];

  if (name === 'marked') {
    previewLoaders.marked = import(/* @vite-ignore */ CDN.markedEsm)
      .then((m) => {
        const api = m.marked || m.default || m;
        if (api == null) throw new Error('marked ESM export missing');
        return api;
      })
      .catch((e) => {
        delete previewLoaders.marked;
        throw e;
      });
    return previewLoaders.marked;
  }

  if (name === 'purify') {
    previewLoaders.purify = import(/* @vite-ignore */ CDN.purifyEsm)
      .then((m) => {
        // purify.es.mjs exports a ready DOMPurify instance as default.
        const api = m.default || m.DOMPurify || m;
        if (api == null || typeof api.sanitize !== 'function') {
          throw new Error('DOMPurify ESM export missing');
        }
        return api;
      })
      .catch((e) => {
        delete previewLoaders.purify;
        throw e;
      });
    return previewLoaders.purify;
  }

  const pin = CDN[name];
  if (!pin || !pin.src) throw new Error('unknown CDN lib ' + name);
  // UMD diagram libs only: park define for the load so they attach globals (Monaco claims AMD).
  previewLoaders[name] = loadScript({ ...pin, noAmd: true })
    .then(() => {
      const global =
        name === 'mermaid'
          ? globalThis.mermaid
          : name === 'viz'
            ? globalThis.Viz
            : name === 'asciidoctor'
              ? globalThis.Asciidoctor
              : null;
      if (global == null) {
        delete previewLoaders[name];
        throw new Error(name + ' CDN script loaded but global was not set');
      }
      return global;
    })
    .catch((e) => {
      delete previewLoaders[name];
      throw e;
    });
  return previewLoaders[name];
}

/** marked UMD may expose parse as a method or as the callable itself. */
export function markedParse(marked, src) {
  if (marked == null) throw new Error('marked is not loaded');
  if (typeof marked.parse === 'function') return marked.parse(src);
  if (typeof marked === 'function') return marked(src);
  throw new Error('marked API unavailable');
}

export async function copyText(str) {
  const text = str == null ? '' : String(str);
  if (typeof navigator !== 'undefined' && navigator.clipboard && navigator.clipboard.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  if (typeof document === 'undefined') return;
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

/**
 * Refcounted park of Monaco's AMD {@code define}. Preview CDNs load in parallel (marked + purify
 * + mermaid); a naive park/restore per tag lets the first onload put {@code define} back while
 * another UMD is still evaluating — that one AMD-registers and never sets its global
 * (e.g. "purify CDN script loaded but global was not set").
 */
let amdParkDepth = 0;
let amdParkSaved = undefined;

function parkAmdDefine() {
  if (typeof globalThis === 'undefined') return false;
  if (amdParkDepth === 0) {
    if (typeof globalThis.define !== 'function') return false;
    amdParkSaved = globalThis.define;
    try {
      globalThis.define = undefined;
    } catch {
      return false;
    }
  }
  amdParkDepth++;
  return true;
}

function unparkAmdDefine() {
  if (amdParkDepth <= 0) return;
  amdParkDepth--;
  if (amdParkDepth > 0) return;
  try {
    globalThis.define = amdParkSaved;
  } catch {
    // ignore
  }
  amdParkSaved = undefined;
}

/**
 * Load a classic script. When {@code noAmd} is set, park {@code window.define} for the load so
 * UMD builds take their browser-global branch instead of registering with Monaco's AMD loader.
 */
function loadScript({ src, integrity, noAmd = false }) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    if (integrity) {
      s.integrity = integrity;
      s.crossOrigin = 'anonymous';
    } else {
      s.crossOrigin = 'anonymous';
    }
    const parked = noAmd && parkAmdDefine();
    const restore = () => {
      if (parked) unparkAmdDefine();
    };
    s.onload = () => {
      restore();
      resolve();
    };
    s.onerror = () => {
      restore();
      s.remove(); // retries append a fresh tag; don't accumulate dead ones (JK-1953)
      reject(new Error('failed to load ' + src));
    };
    document.head.appendChild(s);
  });
}

let monacoPromise = null;

/**
 * Lazy-load Monaco once, on first `/files` open, and resolve the `monaco` global.
 *
 * Monaco's loader installs AMD `define`/`require` globals and keeps needing them (language
 * grammars are chunks it `require`s on demand), so it must load *after* the shell's UMD CDN
 * globals — Vue and ECharts are deferred script tags in index.html, i.e. long resolved before a
 * user can open a project's files. Its language workers are Blob-URL workers that
 * `importScripts` the CDN bundle, which is what `blob:` in the shell CSP is for (StaticContent).
 */
export function ensureMonaco() {
  if (monacoPromise) return monacoPromise;
  if (typeof document === 'undefined' || typeof window === 'undefined') {
    monacoPromise = Promise.reject(new Error('no document'));
    monacoPromise.catch(() => { monacoPromise = null; });
    return monacoPromise;
  }
  monacoPromise = (async () => {
    await loadScript(MONACO_LOADER);
    if (!window.require || typeof window.require.config !== 'function') {
      throw new Error('monaco loader did not install require');
    }
    window.require.config({ paths: { vs: MONACO_BASE } });
    await new Promise((resolve, reject) => {
      window.require(['vs/editor/editor.main'], resolve, reject);
    });
    if (!window.monaco || !window.monaco.editor) throw new Error('monaco did not initialize');
    window.monaco.editor.defineTheme(MONACO_THEME, themeDefinition(consoleBackground()));
    return window.monaco;
  })();
  // A transient CDN hiccup must not latch the plain-text fallback for the tab's life
  // (JK-1953): drop the memo on rejection so the next file open retries the load.
  monacoPromise.catch(() => { monacoPromise = null; });
  return monacoPromise;
}

export function canHighlight(file) {
  if (!file) return false;
  const bytes = file.bytes != null ? file.bytes : (file.content || '').length;
  const lines = file.lines != null ? file.lines : 0;
  return bytes <= HIGHLIGHT_MAX_BYTES && lines <= HIGHLIGHT_MAX_LINES;
}

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

export const CodeView = {
  props: {
    projectId: { type: String, default: null },
    path: { type: String, default: null },
    line: { type: Number, default: 0 },
    /** True when the hash carried {@code err=true} (fail-report / OSC-8 jump). */
    lineErr: { type: Boolean, default: false },
  },
  emits: ['navigate', 'build'],
  data: () => ({
    loadingList: false,
    loadingFile: false,
    error: null,
    files: [],
    truncated: false,
    file: null,
    filter: '',
    expanded: {}, // open directory paths, keyed by the (possibly compacted) node path
    highlighterFailed: false,
    copied: false,
    saved: false,
    dirty: false,
    saving: false,
    previewOpen: false,
    previewError: null,
    previewHtml: '',
    previewImageUrl: null,
    previewLoading: false,
    _copiedTimer: null,
    _savedTimer: null,
    _previewTimer: null,
    _previewGen: 0,
    _listAbort: null,
    _fileAbort: null,
    _baseline: '',
    _etag: null,
    // The editor/model/decorations handles stay OFF data() on purpose: data is deeply reactive,
    // and wrapping Monaco's instances in a Proxy breaks them. They live on the raw instance
    // (this._editor, set in mount()).
  }),
  computed: {
    filtering() {
      return !!(this.filter || '').trim();
    },
    visibleFiles() {
      const q = (this.filter || '').trim().toLowerCase();
      const all = this.files || [];
      if (!q) return all;
      return all.filter((f) => f.path.toLowerCase().includes(q));
    },
    fileTree() {
      return buildFileTree((this.files || []).map((f) => f.path));
    },
    /**
     * Tree rows while browsing; a flat list of full paths while filtering — same as GitHub, where
     * the tree is for browsing and the file finder answers with paths.
     */
    treeRows() {
      if (this.filtering) {
        return this.visibleFiles.map((f) => ({ name: f.path, path: f.path, dir: false, depth: 0 }));
      }
      return visibleRows(this.fileTree, this.expanded);
    },
    skippedHighlight() {
      return !!(this.file && !canHighlight(this.file));
    },
    rows() {
      return this.file ? plainRows(this.file.content) : [];
    },
    isImage() {
      return !!(this.path && isImagePath(this.path));
    },
    previewable() {
      return !!(this.path && isPreviewable(this.path));
    },
    editable() {
      // Oversized / failed-highlighter plain branch and image-only opens are not edited in Monaco.
      return !!(
        this.file &&
        !this.isImage &&
        !this.skippedHighlight &&
        !this.highlighterFailed &&
        isTextWritableLang(this.file.lang || langFromPath(this.file.path))
      );
    },
    canSave() {
      return !!(this.editable && this.dirty && !this.saving && !this.loadingFile && this.path && this.projectId);
    },
    /**
     * The editor host stays mounted and is only hidden (v-show), never v-if'd: a `v-if` would rip
     * the host out of the DOM on every `file = null` between loads, leaving the live editor
     * attached to a detached node — a blank pane on the next file.
     */
    editorVisible() {
      // Stay mounted while Preview is open for text so edits re-render the live pane.
      return (
        !!this.file &&
        !this.error &&
        !this.loadingFile &&
        !this.skippedHighlight &&
        !this.highlighterFailed &&
        !this.isImage
      );
    },
    previewVisible() {
      return !!(this.previewOpen && this.previewable && !this.loadingFile && this.path);
    },
    /** Image-only preview fills the pane; text Preview shares the pane with Monaco (live edit). */
    paneMode() {
      if (this.previewVisible && this.isImage) return 'preview-only';
      if (this.previewVisible && this.editorVisible) return 'split';
      return 'editor';
    },
  },
  watch: {
    projectId() {
      this.loadList();
    },
    path(next, prev) {
      // Revert after a cancelled leave must not re-prompt or re-fetch (would wipe the dirty buffer).
      if (this._ignorePathGuard) {
        this._ignorePathGuard = false;
        return;
      }
      if (prev && this.dirty && next !== prev && !this.confirmDiscard()) {
        this._ignorePathGuard = true;
        this.$emit('navigate', { path: prev, line: 0, replace: true });
        return;
      }
      // Landing back on a bare /files (the Browse control) re-arms the default file.
      if (!this.path) this.openDefaultFile();
      this.expandTo(this.path);
      this.loadFile();
    },
    line() {
      this.$nextTick(() => this.scrollToLine());
    },
    // A buffer going clean releases an epoch reload the user declined while dirty (JK-1973).
    dirty(next) {
      if (!next && this._api) this._api.releaseDeferredEpochReload();
    },
    lineErr() {
      this.$nextTick(() => {
        if (this._decorations) this._decorations.set(lineDecorations(this.line, this.lineErr));
      });
    },
    // Preview open/close changes flex slots; Monaco only remeasures on layout().
    paneMode() {
      this.$nextTick(() => {
        if (this._editor) this._editor.layout();
      });
    },
    editorVisible(vis) {
      if (!vis) return;
      this.$nextTick(() => {
        if (this._editor) this._editor.layout();
      });
    },
  },
  async mounted() {
    // Browser-level loss guards (JK-1973): warn on tab close/F5 with unsaved edits, and let the
    // epoch hard-reload ask before discarding the buffer.
    this._api = await import('./api.js');
    this._dirtyProbe = () => this.dirty;
    this._api.registerDirtyGuard(this._dirtyProbe);
    this._beforeUnload = (e) => {
      if (this.dirty) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', this._beforeUnload);
    await this.loadList();
    await this.loadFile();
  },
  beforeUnmount() {
    this.teardown();
  },
  methods: {
    teardown() {
      if (this._beforeUnload) {
        window.removeEventListener('beforeunload', this._beforeUnload);
        this._beforeUnload = null;
      }
      if (this._api && this._dirtyProbe) {
        this._api.unregisterDirtyGuard(this._dirtyProbe);
        this._dirtyProbe = null;
      }
      if (this._listAbort) this._listAbort.abort();
      if (this._fileAbort) this._fileAbort.abort();
      if (this._copiedTimer) clearTimeout(this._copiedTimer);
      if (this._savedTimer) clearTimeout(this._savedTimer);
      if (this._previewTimer) clearTimeout(this._previewTimer);
      this.revokeImageUrl();
      this.disposeEditor();
    },
    disposeEditor() {
      if (this._contentSub) {
        this._contentSub.dispose();
        this._contentSub = null;
      }
      if (this._decorations) {
        this._decorations.clear();
        this._decorations = null;
      }
      if (this._editor) {
        this._editor.dispose();
        this._editor = null;
      }
      if (this._model) {
        this._model.dispose();
        this._model = null;
      }
    },
    revokeImageUrl() {
      if (this.previewImageUrl) {
        URL.revokeObjectURL(this.previewImageUrl);
        this.previewImageUrl = null;
      }
      if (this._mdImgBlobs) {
        for (const u of this._mdImgBlobs) {
          try {
            URL.revokeObjectURL(u);
          } catch {
            // ignore
          }
        }
        this._mdImgBlobs = [];
      }
    },
    /**
     * Markdown {@code <img>} handling (GitHub README style):
     * - Remote http(s): leave the absolute URL on the tag. Do <b>not</b> set {@code crossorigin}
     *   (that forces a CORS fetch and breaks github.com/user-attachments / many badge CDNs).
     *   {@code referrerpolicy=no-referrer} helps hosts that soft-block hotlinks by Referer.
     *   We intentionally skip the engine image proxy for remotes — GH user-attachments often 404
     *   server-side, and a failed proxy + {@code crossorigin} fallback was the CORS error loop.
     * - Relative: auth-fetch workspace raw bytes → blob: URL.
     */
    async hydrateMarkdownImages(html, gen) {
      if (!html || typeof DOMParser === 'undefined') return html;
      const parser = new DOMParser();
      const doc = parser.parseFromString('<div id="jk-md-root">' + html + '</div>', 'text/html');
      const root = doc.getElementById('jk-md-root');
      if (!root) return html;
      const imgs = root.querySelectorAll('img[src]');
      if (!imgs.length) return root.innerHTML;
      const { getBlob } = await import('./api.js');
      const blobs = [];
      const jobs = [];
      for (const img of imgs) {
        const src = img.getAttribute('src');
        if (!src) continue;

        img.removeAttribute('crossorigin');
        img.setAttribute('loading', 'lazy');

        if (isRemoteHttpUrl(src)) {
          let remote = src.trim();
          if (remote.startsWith('//')) remote = 'https:' + remote;
          img.setAttribute('src', remote);
          img.setAttribute('referrerpolicy', 'no-referrer');
          continue;
        }

        const rel = resolveMarkdownImagePath(this.path, src);
        if (!rel) continue;
        jobs.push(
          (async () => {
            try {
              const blob = await getBlob(
                '/api/project/file/raw?project=' +
                  encodeURIComponent(this.projectId) +
                  '&path=' +
                  encodeURIComponent(rel),
              );
              if (gen !== this._previewGen) return;
              const url = URL.createObjectURL(blob);
              blobs.push(url);
              img.setAttribute('src', url);
              img.removeAttribute('data-jk-img-miss');
              img.classList.remove('code-preview-img-miss');
            } catch {
              img.setAttribute('data-jk-img-miss', rel);
              img.classList.add('code-preview-img-miss');
            }
          })(),
        );
      }
      await Promise.all(jobs);
      if (gen !== this._previewGen) {
        for (const u of blobs) {
          try {
            URL.revokeObjectURL(u);
          } catch {
            // ignore
          }
        }
        return html;
      }
      this._mdImgBlobs = blobs;
      return root.innerHTML;
    },
    /**
     * Point relative markdown links at the files-pane hash so clicks open the target in
     * code-view (e.g. badge → {@code docs/architecture.md}). External http(s) stay as-is
     * (new tab).
     */
    rewriteMarkdownLinks(html) {
      if (!html || typeof DOMParser === 'undefined' || !this.projectId) return html;
      const parser = new DOMParser();
      const doc = parser.parseFromString('<div id="jk-md-root">' + html + '</div>', 'text/html');
      const root = doc.getElementById('jk-md-root');
      if (!root) return html;
      for (const a of root.querySelectorAll('a[href]')) {
        const href = a.getAttribute('href');
        if (!href) continue;
        if (isRemoteHttpUrl(href) || /^[a-z][a-z0-9+.-]*:/i.test(href.trim())) {
          // External: open outside the SPA.
          a.setAttribute('target', '_blank');
          a.setAttribute('rel', 'noopener noreferrer');
          continue;
        }
        if (href.trim().startsWith('#')) continue; // in-page anchor
        const rel = resolveMarkdownLinkPath(this.path, href);
        if (!rel) continue;
        a.setAttribute(
          'href',
          buildProjectHash({ projectId: this.projectId, files: true, path: rel }),
        );
        a.classList.add('code-preview-inlink');
        a.removeAttribute('target');
      }
      return root.innerHTML;
    },
    confirmDiscard() {
      if (typeof window === 'undefined' || !window.confirm) return true;
      return window.confirm('Discard unsaved changes?');
    },
    fileName(p) {
      return baseFileName(p);
    },
    /** A row's own click target: folders open/close, files navigate. */
    activate(row) {
      if (row.dir) this.toggleDir(row.path);
      else this.selectFile(row.path);
    },
    toggleDir(dirPath) {
      if (this.expanded[dirPath]) delete this.expanded[dirPath];
      else this.expanded[dirPath] = true;
    },
    /** Open every directory on the way to a file, so a `?line=` deep link lands revealed. */
    expandTo(filePath) {
      for (const dir of ancestorDirs(filePath)) this.expanded[dir] = true;
    },
    /**
     * `/files` with no path opens the workspace `jk.toml`. `replace` so the pane the user actually
     * asked for is not left behind a history entry the Back chevron has to walk through.
     */
    openDefaultFile() {
      if (this.path) return;
      const fallback = defaultFilePath(this.files);
      if (fallback) this.$emit('navigate', { path: fallback, line: 0, replace: true });
    },
    selectFile(p) {
      this.$emit('navigate', { path: p, line: 0 });
    },
    setBaseline(text, etag) {
      this._baseline = text == null ? '' : String(text);
      if (etag !== undefined) this._etag = etag || null;
      this.dirty = false;
    },
    recomputeDirty() {
      if (!this._editor || !this.editable) {
        this.dirty = false;
        return;
      }
      this.dirty = this._editor.getValue() !== this._baseline;
      this.schedulePreviewRefresh();
    },
    currentContent() {
      if (this._editor && this.editable) return this._editor.getValue();
      return this.file && this.file.content != null ? String(this.file.content) : '';
    },
    /** Debounced re-render while Preview is open and the buffer is text (not image). */
    schedulePreviewRefresh() {
      if (!this.previewOpen || this.isImage) return;
      if (this._previewTimer) clearTimeout(this._previewTimer);
      this._previewTimer = setTimeout(() => {
        this._previewTimer = null;
        if (this.previewOpen && !this.isImage) this.renderPreview();
      }, 350);
    },
    flashSaved() {
      this.saved = true;
      if (this._savedTimer) clearTimeout(this._savedTimer);
      this._savedTimer = setTimeout(() => {
        this.saved = false;
        this._savedTimer = null;
      }, 1500);
    },
    async loadList() {
      if (this._listAbort) this._listAbort.abort();
      this.files = [];
      this.truncated = false;
      this.expanded = {}; // a different project is a different tree
      this.error = null;
      if (!this.projectId) {
        this.loadingList = false;
        return;
      }
      const ac = new AbortController();
      this._listAbort = ac;
      this.loadingList = true;
      try {
        const { get } = await import('./api.js');
        const data = await get('/api/project/files?project=' + encodeURIComponent(this.projectId), {
          signal: ac.signal,
        });
        if (ac.signal.aborted) return;
        this.files = data.files || [];
        this.truncated = !!data.truncated;
        this.loadingList = false;
        this.expandTo(this.path); // the open file may predate the list (deep link / fail-report jump)
        this.openDefaultFile(); // and with no file in the route, the list decides the default
      } catch (e) {
        if (e && e.name === 'AbortError') return;
        if (ac.signal.aborted) return;
        this.loadingList = false;
        this.error = (e && e.error) || (e && e.status ? 'HTTP ' + e.status : 'Failed to list files');
      }
    },
    async loadFile() {
      if (this._fileAbort) this._fileAbort.abort();
      if (this._previewTimer) {
        clearTimeout(this._previewTimer);
        this._previewTimer = null;
      }
      this.file = null;
      this.dirty = false;
      this.saved = false;
      this.previewOpen = false;
      this.previewError = null;
      this.previewHtml = '';
      this.revokeImageUrl();
      this.setBaseline('', null);
      if (!this.projectId || !this.path) {
        this.loadingFile = false;
        return;
      }
      const ac = new AbortController();
      this._fileAbort = ac;
      this.loadingFile = true;
      this.error = null;
      try {
        if (isImagePath(this.path)) {
          // Images are not JSON-text; meta only — Preview uses the raw endpoint.
          this.file = {
            path: this.path,
            lang: 'image',
            content: '',
            bytes: 0,
            lines: 0,
            encoding: 'binary',
            etag: null,
          };
          this.loadingFile = false;
          this.previewOpen = true;
          await this.$nextTick();
          await this.renderPreview();
          return;
        }
        const { get } = await import('./api.js');
        const data = await get(
          '/api/project/file?project=' +
            encodeURIComponent(this.projectId) +
            '&path=' +
            encodeURIComponent(this.path),
          { signal: ac.signal },
        );
        if (ac.signal.aborted) return;
        this.file = data;
        this.setBaseline(data.content, data.etag || null);
        this.loadingFile = false;
        // Preview-eligible types open in preview by default (toggle still flips to source).
        this.previewOpen = isPreviewable(this.path);
        await this.$nextTick();
        await this.paint();
        if (this.previewOpen) await this.renderPreview();
      } catch (e) {
        if (e && e.name === 'AbortError') return;
        if (ac.signal.aborted) return;
        this.loadingFile = false;
        this.error = (e && e.error) || (e && e.status ? 'HTTP ' + e.status : 'Failed to load file');
      }
    },
    async paint() {
      if (!this.file || this.skippedHighlight || this.isImage) {
        this.disposeEditor(); // the plain-text branch owns the pane now
        this.$nextTick(() => this.scrollToLine());
        return;
      }
      let monaco = null;
      try {
        monaco = await ensureMonaco();
        this.highlighterFailed = false; // a later successful load clears the banner (JK-1953)
      } catch {
        this.highlighterFailed = true;
      }
      if (!monaco) {
        this.disposeEditor();
        await this.$nextTick(); // the plain <pre> paints; the host is hidden
        this.scrollToLine();
        return;
      }
      // Wait for editorVisible to un-hide the host: Monaco measures it at create time, and a
      // display:none host would give it a 5×5 viewport.
      await this.$nextTick();
      this.mount(monaco);
      this.scrollToLine();
    },
    /** Create the editor once, then swap models per file — creating one per file is expensive. */
    mount(monaco) {
      const host = this.$refs.editor;
      if (!host || !this.file) return;
      const options = viewerOptions({
        content: this.file.content,
        lang: this.file.lang || langFromPath(this.file.path),
        readOnly: !this.editable,
      });
      // Belt to editorVisible's braces: an editor whose DOM is no longer under the live host can
      // only paint into a detached node (a blank pane), so rebuild rather than swap a model onto it.
      if (this._editor && !host.contains(this._editor.getContainerDomNode())) this.disposeEditor();
      if (this._contentSub) {
        this._contentSub.dispose();
        this._contentSub = null;
      }
      if (!this._editor) {
        this._editor = monaco.editor.create(host, options);
        // create() can land before the host's box is measurable, and a viewport-less editor
        // reveals ?line= at the top instead of the middle. Measure now, and again after layout
        // settles (flex + tab strip can still be sizing on the first paint).
        this._editor.layout();
        requestAnimationFrame(() => {
          if (this._editor) this._editor.layout();
        });
      } else {
        const previous = this._model;
        this._model = monaco.editor.createModel(options.value, options.language);
        this._editor.setModel(this._model);
        this._editor.updateOptions({ readOnly: options.readOnly, domReadOnly: options.domReadOnly });
        if (previous) previous.dispose();
        this._editor.layout();
      }
      if (!this._model) this._model = this._editor.getModel();
      this._decorations = this._editor.createDecorationsCollection(
        lineDecorations(this.line, this.lineErr),
      );
      if (!options.readOnly && this._model) {
        this._contentSub = this._model.onDidChangeContent(() => this.recomputeDirty());
      }
      this.recomputeDirty();
    },
    scrollToLine() {
      if (this.line < 1) return;
      if (this._editor) {
        if (this._decorations) this._decorations.set(lineDecorations(this.line, this.lineErr));
        this._editor.revealLineInCenter(this.line);
        return;
      }
      const pre = this.$refs.pre;
      if (!pre || !pre.querySelectorAll) return;
      const target = pre.querySelectorAll('.code-plain-row')[this.line - 1];
      if (target && target.scrollIntoView) target.scrollIntoView({ block: 'center' });
    },
    async copy() {
      if (!this.path) return;
      try {
        await copyText(this.currentContent());
        this.copied = true;
        if (this._copiedTimer) clearTimeout(this._copiedTimer);
        this._copiedTimer = setTimeout(() => {
          this.copied = false;
          this._copiedTimer = null;
        }, 1500);
      } catch {
        // clipboard blocked
      }
    },
    async save() {
      if (!this.canSave) return;
      this.saving = true;
      this.saved = false;
      this.error = null;
      // The response must only ever apply to the file it was issued for: navigating away
      // (discard confirmed) while the PUT is in flight would otherwise stamp the OLD file's
      // content/etag onto the NEW file's state (JK-1977).
      const savedPath = this.path;
      try {
        const { put } = await import('./api.js');
        const content = this.currentContent();
        const body = {
          project: this.projectId,
          path: savedPath,
          content,
        };
        if (this._etag) body.etag = this._etag;
        // Echo the charset the file was decoded under so the engine re-encodes to the
        // original bytes instead of silently transcoding a Latin-1 file to UTF-8 (JK-1972).
        if (this.file && this.file.encoding && this.file.encoding !== 'utf-8') {
          body.encoding = this.file.encoding;
        }
        const resp = await put('/api/project/file', body);
        if (this.path !== savedPath) return; // navigated away — the write landed; drop the state
        const nextEtag = (resp && resp.etag) || null;
        if (this.file) this.file = { ...this.file, content, etag: nextEtag };
        this.setBaseline(content, nextEtag);
        this.flashSaved();
      } catch (e) {
        if (this.path !== savedPath) return; // stale failure belongs to a file no longer shown
        this.error = saveErrorMessage(e);
      } finally {
        this.saving = false;
      }
    },
    async togglePreview() {
      if (!this.previewable) return;
      if (this.previewOpen) {
        if (this._previewTimer) {
          clearTimeout(this._previewTimer);
          this._previewTimer = null;
        }
        this.previewOpen = false;
        this.previewError = null;
        this.previewHtml = '';
        this.revokeImageUrl();
        await this.$nextTick();
        if (this._editor) this._editor.layout();
        if (this.file && !this.isImage && !this.editorVisible) await this.paint();
        return;
      }
      this.previewOpen = true;
      await this.$nextTick();
      if (this._editor) this._editor.layout();
      await this.renderPreview();
    },
    async renderPreview() {
      const gen = ++this._previewGen;
      this.previewLoading = true;
      this.previewError = null;
      // Keep prior HTML visible while re-rendering (live edit); only clear image URL for image kind.
      const kind = previewKind(this.path);
      try {
        if (kind === 'image') {
          this.revokeImageUrl();
          this.previewHtml = '';
          const { getBlob } = await import('./api.js');
          const blob = await getBlob(
            '/api/project/file/raw?project=' +
              encodeURIComponent(this.projectId) +
              '&path=' +
              encodeURIComponent(this.path),
          );
          if (gen !== this._previewGen) return;
          this.previewImageUrl = URL.createObjectURL(blob);
        } else if (kind === 'markdown') {
          this.revokeImageUrl(); // drop prior blob: image srcs before re-render
          // ESM for marked/purify — no AMD park (avoids racing Monaco's markdown grammar load).
          const [marked, purify] = await Promise.all([ensureCdn('marked'), ensureCdn('purify')]);
          if (gen !== this._previewGen) return;
          const { markdown, fences } = extractMermaidFences(this.currentContent());
          const raw = markedParse(marked, markdown);
          // Keep raw HTML <img> (GitHub READMEs) and markdown images; hydrate srcs to blob: after.
          let html = purify.sanitize(raw, {
            ADD_ATTR: ['loading', 'referrerpolicy', 'decoding', 'width', 'height'],
            ALLOW_UNKNOWN_PROTOCOLS: false,
            ALLOWED_URI_REGEXP:
              /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|blob|data):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i,
          });
          // Mermaid UMD only when fenced blocks exist (noAmd park stays off the default path).
          if (fences.length) {
            const mermaid = await ensureCdn('mermaid');
            if (gen !== this._previewGen) return;
            mermaid.initialize({ startOnLoad: false, theme: 'dark', securityLevel: 'strict' });
            const svgs = [];
            for (let i = 0; i < fences.length; i++) {
              try {
                const id = 'jk-md-mmd-' + gen + '-' + i;
                const { svg } = await mermaid.render(id, fences[i]);
                svgs.push('<div class="code-preview-diagram">' + svg + '</div>');
              } catch (err) {
                const msg = (err && err.message) || 'Mermaid diagram failed';
                svgs.push('<pre class="code-preview-diagram-err">' + escapeHtml(msg) + '</pre>');
              }
              if (gen !== this._previewGen) return;
            }
            html = injectMermaidSvgs(html, svgs);
          }
          if (gen !== this._previewGen) return;
          html = await this.hydrateMarkdownImages(html, gen);
          if (gen !== this._previewGen) return;
          html = this.rewriteMarkdownLinks(html);
          if (gen !== this._previewGen) return;
          this.previewHtml = html;
        } else if (kind === 'mermaid') {
          const mermaid = await ensureCdn('mermaid');
          if (gen !== this._previewGen) return;
          mermaid.initialize({ startOnLoad: false, theme: 'dark', securityLevel: 'strict' });
          const id = 'jk-mmd-' + gen;
          const { svg } = await mermaid.render(id, this.currentContent());
          if (gen !== this._previewGen) return;
          this.previewHtml = '<div class="code-preview-diagram">' + svg + '</div>';
        } else if (kind === 'graphviz') {
          // Sanitize like markdown/asciidoc (JK-1976): .dot files from a cloned repo control
          // the SVG (URL= attrs, arbitrary markup) — one DOMPurify chokepoint for all renderers.
          const [Viz, purify] = await Promise.all([ensureCdn('viz'), ensureCdn('purify')]);
          if (gen !== this._previewGen) return;
          const viz = await Viz.instance();
          if (gen !== this._previewGen) return;
          this.previewHtml =
            '<div class="code-preview-diagram">' +
            purify.sanitize(viz.renderSVGElement(this.currentContent()).outerHTML, {
              USE_PROFILES: { svg: true, svgFilters: true },
            }) +
            '</div>';
        } else if (kind === 'asciidoc') {
          const Asciidoctor = await ensureCdn('asciidoctor');
          const purify = await ensureCdn('purify');
          if (gen !== this._previewGen) return;
          const adoc = typeof Asciidoctor === 'function' ? Asciidoctor() : Asciidoctor;
          const html = adoc.convert(this.currentContent(), { safe: 'secure', attributes: { showtitle: true } });
          this.previewHtml = purify.sanitize(html);
        } else if (kind === 'd2') {
          // WASM-heavy; dynamic ESM from unpkg. CSP/wasm may reject — surface the error.
          const mod = await import('https://unpkg.com/@terrastruct/d2@0.1.33/dist/index.js');
          if (gen !== this._previewGen) return;
          const D2 = mod.D2 || mod.default;
          const d2 = new D2();
          const result = await d2.compile(this.currentContent());
          const rendered = await d2.render(result.diagram || result);
          const purify = await ensureCdn('purify');
          if (gen !== this._previewGen) return;
          this.previewHtml =
            '<div class="code-preview-diagram">' +
            purify.sanitize(typeof rendered === 'string' ? rendered : String(rendered), {
              USE_PROFILES: { svg: true, svgFilters: true },
            }) +
            '</div>';
        } else {
          this.previewError = 'No preview for this file type';
        }
      } catch (e) {
        if (gen !== this._previewGen) return;
        this.previewError = (e && e.message) || (e && e.error) || 'Preview failed';
        this.previewHtml = '';
      } finally {
        if (gen === this._previewGen) this.previewLoading = false;
      }
    },
  },
  template: `
    <div class="code-split">
      <aside class="code-tree">
        <div class="code-filter-box">
          <jk-icon class="code-filter-ico" name="filter"></jk-icon>
          <input class="code-filter" v-model="filter" placeholder="Filter files" spellcheck="false"
                 aria-label="Filter files">
        </div>
        <p v-if="truncated" class="warn">File list truncated at 2000</p>
        <p v-if="loadingList" class="empty">Listing…</p>
        <p v-else-if="!treeRows.length" class="empty">{{ filtering ? 'No matching files' : 'No files' }}</p>
        <button v-for="row in treeRows" :key="row.path" type="button"
                class="code-row" :class="{ dir: row.dir, on: !row.dir && row.path === path }"
                :style="{ paddingLeft: (8 + row.depth * 12) + 'px' }"
                :aria-expanded="row.dir ? String(row.open) : null"
                :data-tip="row.path"
                @click="activate(row)">
          <jk-icon v-if="row.dir" class="code-twist" :name="row.open ? 'chevron-down' : 'chevron-right'"></jk-icon>
          <span v-else class="code-twist"></span>
          <jk-icon class="code-glyph" :name="row.dir ? (row.open ? 'folder-open' : 'folder') : 'file'"></jk-icon>
          <span class="code-name">{{ row.name }}</span>
        </button>
      </aside>
      <div class="code-main">
        <!-- Tab strip: open-file tab (left) + Copy / Preview / Save (right). Build stays in the header. -->
        <div class="file-tab-bar">
          <span v-if="path" class="file-name-pill" :data-tip="path" role="tab" aria-selected="true"
                :aria-label="path">
            <jk-icon name="file"></jk-icon>
            <span class="file-name-pill-label">{{ fileName(path) }}</span>
          </span>
          <span v-else class="file-tab-bar-spacer" aria-hidden="true"></span>
          <div class="file-tab-actions">
            <button type="button" class="action-cyan" :disabled="!path" @click="copy()"
                    data-tip="Copy file contents">
              <jk-icon name="copy"></jk-icon>{{ copied ? 'Copied' : 'Copy' }}
            </button>
            <button type="button" class="action-cyan"
                    :disabled="!previewable" @click="togglePreview()"
                    :data-tip="previewable ? (previewOpen ? 'Back to source' : 'Preview this file') : 'Preview not available for this file type'">
              <jk-icon name="eye"></jk-icon>{{ previewOpen ? 'Source' : 'Preview' }}
            </button>
            <button type="button" class="action-cyan" :disabled="!canSave" @click="save()"
                    :data-tip="canSave ? 'Save changes' : (saving ? 'Saving…' : (saved ? 'Saved' : 'No unsaved changes'))">
              <jk-icon name="save"></jk-icon>{{ saving ? 'Saving…' : (saved ? 'Saved' : 'Save') }}
            </button>
          </div>
        </div>
        <section class="code-pane" :class="'mode-' + paneMode">
          <div class="code-msgs">
            <p v-if="error" class="error">{{ error }}</p>
            <div v-else-if="!path" class="code-empty">
              <jk-icon name="file"></jk-icon>
              <p class="code-empty-head">Select a file from the tree.</p>
              <p class="code-empty-sub">Filter by path to jump straight to one.</p>
            </div>
            <p v-else-if="loadingFile" class="empty">Loading…</p>
            <template v-else-if="file && !previewOpen && (skippedHighlight || highlighterFailed)">
              <p v-if="skippedHighlight" class="warn">Highlighting skipped (file too large)</p>
              <p v-else class="warn">Highlighter failed to load — showing plain text</p>
              <pre class="code-pre code-plain" ref="pre">
                <div v-for="(row, i) in rows" :key="i" class="code-plain-row fail-src"
                     :class="{ 'code-line-on': line === i + 1 && !lineErr, 'code-line-err-plain': line === i + 1 && lineErr }">
                  <span class="fail-gutter">{{ i + 1 }}</span><span class="fail-gutter-rail">\u2502</span><span class="fail-src-code">{{ row }}</span>
                </div>
              </pre>
            </template>
            <p v-if="file && file.encoding && file.encoding !== 'utf-8' && file.encoding !== 'binary'" class="warn">
              Not valid UTF-8 — decoded as {{ file.encoding }}; saves keep this encoding</p>
          </div>
          <div v-show="previewVisible" class="code-preview">
            <p v-if="previewLoading && !previewHtml && !previewImageUrl" class="empty">Rendering preview…</p>
            <p v-else-if="previewError" class="error">{{ previewError }}</p>
            <img v-else-if="previewImageUrl" class="code-preview-img" :src="previewImageUrl" :alt="path">
            <div v-else-if="previewHtml" class="code-preview-body" v-html="previewHtml"></div>
          </div>
          <div v-show="editorVisible" class="code-editor" ref="editor"></div>
        </section>
      </div>
    </div>`,
};
