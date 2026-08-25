// SPDX-License-Identifier: Apache-2.0
// Monaco and the preview CDNs: the version pins, the subresource-integrity hashes, the AMD-define
// parking those UMD bundles need, and the editor options/decorations built against the loaded
// namespace. One file so a pin can never drift from the loader that fetches it.

import { clipHashMsg } from './route.js';

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

/**
 * Monaco language ids for the langs above. Monaco ships no Groovy, TOML, or Java properties
 * grammar, so those fall back to the closest one it has: Java (C-like), and ini (`[section]`,
 * `key = value`, `#` comments). Anything unknown tokenizes as plain text rather than handing
 * Monaco an unregistered id.
 */
const MONACO_LANG = {
  java: 'java',
  kotlin: 'kotlin',
  groovy: 'java',
  scala: 'scala',
  toml: 'ini',
  xml: 'xml',
  yaml: 'yaml',
  json: 'json',
  sql: 'sql',
  properties: 'ini',
  shell: 'shell',
  markdown: 'markdown',
  mermaid: 'plaintext',
  graphviz: 'plaintext',
  asciidoc: 'plaintext',
  d2: 'plaintext',
  image: 'plaintext',
};

/**
 * Monaco {@code hoverMessage} payload for a decoration. Wrapped as a text fence so
 * identifiers like {@code List<T>} don't go through Markdown emphasis.
 */
export function hoverMessage(msg) {
  const t = clipHashMsg(msg);
  if (!t) return undefined;
  return { value: '```text\n' + t.replace(/```/g, "'''") + '\n```' };
}

/** Server lang (or {@link langFromPath}) → a language id Monaco actually has registered. */
export function monacoLang(lang) {
  return MONACO_LANG[String(lang || '')] || 'plaintext';
}

/**
 * 1-based [start, end) span covering the identifier at {@code col}, or a single column
 * when the character is not an identifier. Used so {@code ?col=} paints the token, not a sliver.
 */
export function columnSpan(text, col) {
  const n = Number(col) || 0;
  if (n < 1) return null;
  const s = text == null ? '' : String(text);
  if (!s.length) return { start: n, end: n + 1 };
  const i = Math.min(Math.max(n, 1), s.length + 1) - 1;
  const isId = (ch) => ch != null && /[A-Za-z0-9_$]/.test(ch);
  if (i >= s.length || !isId(s[i])) {
    return { start: i + 1, end: i + 2 };
  }
  let start = i;
  while (start > 0 && isId(s[start - 1])) start--;
  let end = i + 1;
  while (end < s.length && isId(s[end])) end++;
  return { start: start + 1, end: end + 1 };
}

/**
 * `?line=` whole-line decoration — plain IRange objects, testable without a monaco global.
 * Empty for line 0. Neutral (cyan/soft) by default; {@code err: true} uses the fail-report red wash
 * ({@code ?line=N&err=true} from Activity / OSC-8).
 *
 * When {@code col} is set, a second inline decoration marks that column (identifier span when
 * {@code lineText} is supplied). {@code err} paints a red squiggle; otherwise a cyan underline.
 * {@code msg} is a hover on the whole-line decoration only — attaching it to both stacks the
 * same note twice when the cursor sits on the token.
 */
export function lineDecorations(line, err = false, col = 0, lineText = '', msg = '') {
  const n = Number(line) || 0;
  if (n < 1) return [];
  const error = !!err;
  const hover = hoverMessage(msg);
  const opts = {
    isWholeLine: true,
    className: error ? 'code-line-err' : 'code-line-hl',
    linesDecorationsClassName: error ? 'code-line-err-gutter' : 'code-line-hl-gutter',
  };
  if (hover) opts.hoverMessage = hover;
  if (error) {
    // Stick the mark in the overview/minimap so a long file still shows where the jump landed.
    opts.overviewRuler = { color: 'rgba(255, 51, 102, 0.85)', position: 1 };
    opts.minimap = { color: 'rgba(255, 51, 102, 0.85)', position: 1 };
  }
  const out = [
    {
      range: { startLineNumber: n, startColumn: 1, endLineNumber: n, endColumn: 1 },
      options: opts,
    },
  ];
  const span = columnSpan(lineText, col);
  if (span) {
    const colOpts = {
      inlineClassName: error ? 'code-col-err' : 'code-col-hl',
      overviewRuler: error
        ? { color: 'rgba(255, 51, 102, 0.95)', position: 1 }
        : { color: 'rgba(0, 240, 255, 0.85)', position: 1 },
    };
    out.push({
      range: {
        startLineNumber: n,
        startColumn: span.start,
        endLineNumber: n,
        endColumn: span.end,
      },
      options: colOpts,
    });
  }
  return out;
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

export async function ensureCdn(name) {
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
      s.remove(); // retries append a fresh tag; don't accumulate dead ones
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
    // A partial-failure retry (loader OK, editor.main rejected) finds the AMD loader already
    // installed — re-injecting loader.js would append a duplicate tag and redefine
    // require/define, so only load it when require is absent.
    if (!window.require || typeof window.require.config !== 'function') {
      await loadScript(MONACO_LOADER);
    }
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
  //: drop the memo on rejection so the next file open retries the load.
  monacoPromise.catch(() => { monacoPromise = null; });
  return monacoPromise;
}

export function canHighlight(file) {
  if (!file) return false;
  const bytes = file.bytes != null ? file.bytes : (file.content || '').length;
  const lines = file.lines != null ? file.lines : 0;
  return bytes <= HIGHLIGHT_MAX_BYTES && lines <= HIGHLIGHT_MAX_LINES;
}
