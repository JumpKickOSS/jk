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
};

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

/**
 * Hash routes:
 *   #project/<id>
 *   #project/<id>/files
 *   #project/<id>/files/<rel/path>?line=<n>
 */
export function routeFromHash(hash = typeof location !== 'undefined' ? location.hash : '') {
  const h = hash || '';
  const q = parseHashQuery(h);
  const pathPart = h.indexOf('?') >= 0 ? h.slice(0, h.indexOf('?')) : h;
  if (pathPart === '#projects') {
    return { view: 'projects', projectId: null, files: false, path: null, line: 0 };
  }
  if (pathPart === '#status') {
    return { view: 'status', projectId: null, files: false, path: null, line: 0 };
  }
  if (pathPart.startsWith('#project/')) {
    const segs = pathPart.slice('#project/'.length).split('/');
    const id = decodeComp(segs[0] || '');
    if (!id) return { view: 'activity', projectId: null, files: false, path: null, line: 0 };
    if (segs[1] === 'files') {
      const rel = [];
      for (const raw of segs.slice(2)) {
        if (raw === '') continue;
        const s = decodeComp(raw);
        if (s == null || s === '' || s === '.' || s === '..') {
          return { view: 'project', projectId: id, files: true, path: null, line: parseLine(q.line) };
        }
        rel.push(s);
      }
      return {
        view: 'project',
        projectId: id,
        files: true,
        path: rel.length ? rel.join('/') : null,
        line: parseLine(q.line),
      };
    }
    return { view: 'project', projectId: id, files: false, path: null, line: 0 };
  }
  return { view: 'activity', projectId: null, files: false, path: null, line: 0 };
}

export function buildProjectHash({ projectId, files, path, line } = {}) {
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
  if (line > 0) h += '?line=' + line;
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

export function codePathForFailure({ checkoutDir, moduleDir, file } = {}) {
  if (!file) return null;
  const isAbs = file.startsWith('/') || /^[A-Za-z]:[\\/]/.test(file);
  if (isAbs) {
    const rel = relativizeUnder(checkoutDir, file);
    if (rel == null || rel.split('/').includes('..')) return null;
    return rel;
  }
  if (!file.includes('/')) return null;
  if (!checkoutDir || !moduleDir) return null;
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
 * `?line=` as a whole-line decoration — plain IRange objects, so this stays testable without a
 * monaco global. Empty for line 0: the editor then draws no highlight at all.
 */
export function lineDecorations(line) {
  const n = Number(line) || 0;
  if (n < 1) return [];
  return [
    {
      range: { startLineNumber: n, startColumn: 1, endLineNumber: n, endColumn: 1 },
      options: { isWholeLine: true, className: 'code-line-hl', linesDecorationsClassName: 'code-line-hl-gutter' },
    },
  ];
}

/**
 * Options for `monaco.editor.create` — a read-only viewer, not an editor: no context menu, no
 * suggestions, and `renderLineHighlight: 'none'` so the only highlighted row is the `?line=` one.
 * Content rides `value` (a model string Monaco tokenizes), never innerHTML.
 */
export function viewerOptions({ content, lang } = {}) {
  return {
    value: content == null ? '' : String(content),
    language: monacoLang(lang),
    theme: MONACO_THEME,
    readOnly: true,
    domReadOnly: true,
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

function loadScript({ src, integrity }) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.integrity = integrity;
    s.crossOrigin = 'anonymous';
    s.onload = () => resolve();
    s.onerror = () => {
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
  },
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
    _copiedTimer: null,
    _listAbort: null,
    _fileAbort: null,
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
    /**
     * The editor host stays mounted and is only hidden (v-show), never v-if'd: a `v-if` would rip
     * the host out of the DOM on every `file = null` between loads, leaving the live editor
     * attached to a detached node — a blank pane on the next file.
     */
    editorVisible() {
      return !!this.file && !this.error && !this.loadingFile && !this.skippedHighlight && !this.highlighterFailed;
    },
  },
  watch: {
    projectId() {
      this.loadList();
    },
    path() {
      // Landing back on a bare /files (the Browse control) re-arms the default file.
      if (!this.path) this.openDefaultFile();
      this.expandTo(this.path);
      this.loadFile();
    },
    line() {
      this.$nextTick(() => this.scrollToLine());
    },
  },
  async mounted() {
    await this.loadList();
    await this.loadFile();
  },
  beforeUnmount() {
    this.teardown();
  },
  methods: {
    teardown() {
      if (this._listAbort) this._listAbort.abort();
      if (this._fileAbort) this._fileAbort.abort();
      if (this._copiedTimer) clearTimeout(this._copiedTimer);
      this.disposeEditor();
    },
    disposeEditor() {
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
    fileName(p) {
      const parts = String(p || '').split('/');
      return parts[parts.length - 1] || p;
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
      this.file = null;
      if (!this.projectId || !this.path) {
        this.loadingFile = false;
        return;
      }
      const ac = new AbortController();
      this._fileAbort = ac;
      this.loadingFile = true;
      this.error = null;
      try {
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
        this.loadingFile = false;
        await this.$nextTick();
        await this.paint();
      } catch (e) {
        if (e && e.name === 'AbortError') return;
        if (ac.signal.aborted) return;
        this.loadingFile = false;
        this.error = (e && e.error) || (e && e.status ? 'HTTP ' + e.status : 'Failed to load file');
      }
    },
    async paint() {
      if (!this.file || this.skippedHighlight) {
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
      });
      // Belt to editorVisible's braces: an editor whose DOM is no longer under the live host can
      // only paint into a detached node (a blank pane), so rebuild rather than swap a model onto it.
      if (this._editor && !host.contains(this._editor.getContainerDomNode())) this.disposeEditor();
      if (!this._editor) {
        this._editor = monaco.editor.create(host, options);
        // create() can land before the host's box is measurable, and a viewport-less editor
        // reveals ?line= at the top instead of the middle. Measure now, once.
        this._editor.layout();
      } else {
        const previous = this._model;
        this._model = monaco.editor.createModel(options.value, options.language);
        this._editor.setModel(this._model);
        if (previous) previous.dispose();
      }
      if (!this._model) this._model = this._editor.getModel();
      this._decorations = this._editor.createDecorationsCollection(lineDecorations(this.line));
    },
    scrollToLine() {
      if (this.line < 1) return;
      if (this._editor) {
        if (this._decorations) this._decorations.set(lineDecorations(this.line));
        this._editor.revealLineInCenter(this.line);
        return;
      }
      const pre = this.$refs.pre;
      if (!pre || !pre.querySelectorAll) return;
      const target = pre.querySelectorAll('.code-plain-row')[this.line - 1];
      if (target && target.scrollIntoView) target.scrollIntoView({ block: 'center' });
    },
    async copy() {
      if (!this.file) return;
      try {
        await copyText(this.file.content);
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
      <section class="code-pane">
        <div class="code-msgs">
          <p v-if="error" class="error">{{ error }}</p>
          <div v-else-if="!path" class="code-empty">
            <jk-icon name="file"></jk-icon>
            <p class="code-empty-head">Select a file from the tree.</p>
            <p class="code-empty-sub">Filter by path to jump straight to one.</p>
          </div>
          <p v-else-if="loadingFile" class="empty">Loading…</p>
          <template v-else-if="file && (skippedHighlight || highlighterFailed)">
            <p v-if="skippedHighlight" class="warn">Highlighting skipped (file too large)</p>
            <p v-else class="warn">Highlighter failed to load — showing plain text</p>
            <pre class="code-pre code-plain" ref="pre">
              <div v-for="(row, i) in rows" :key="i" class="code-plain-row fail-src"
                   :class="{ 'code-line-on': line === i + 1 }">
                <span class="fail-gutter">{{ i + 1 }}</span><span class="fail-gutter-rail">\u2502</span><span class="fail-src-code">{{ row }}</span>
              </div>
            </pre>
          </template>
          <p v-if="file && file.encoding && file.encoding !== 'utf-8'" class="warn">
            Not valid UTF-8 — decoded as {{ file.encoding }}</p>
        </div>
        <div v-show="editorVisible" class="code-editor" ref="editor"></div>
      </section>
    </div>`,
};
