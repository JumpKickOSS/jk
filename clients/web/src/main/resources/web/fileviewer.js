// SPDX-License-Identifier: Apache-2.0
// `<file-viewer>`: one workspace file over the page, read-only, as the console opens it from an
// `edited N files` line. The content comes from GET /api/project/file for the checkout the edit was
// seen in; Monaco shows it with the Code tab's options and theme, and a file too large to
// highlight, or a highlighter that fails to load, shows as plain text the same way the Code tab
// does. Nothing here writes.

import { get } from './api.js';
import { canHighlight, copyText, ensureMonaco, viewerOptions } from './monaco.js';
import { isImagePath, langFromPath } from './paths.js';
import { buildProjectHash } from './route.js';
import { plainRows } from './tree.js';

/** What the dialog says for a read the engine refused: `{ status, error }` from api.js. */
export function viewerMessage(err) {
  const status = err && err.status;
  if (status === 404) return 'This file is not there any more: it was moved or deleted after this edit.';
  if (status === 413) return 'This file is too large to show here.';
  if (status === 415) return 'This is a binary file, so there is no text to show.';
  if (err && err.error) return 'The file could not be read: ' + err.error;
  return status ? 'The file could not be read (HTTP ' + status + ').' : 'The file could not be read.';
}

export const FileViewer = {
  props: {
    projectId: { type: String, required: true },
    /** The checkout the path is relative to; the engine needs it when the id has several. */
    dir: { type: String, default: null },
    path: { type: String, required: true },
    /** `gone` when the edit deleted the file: there is nothing to read. */
    mark: { type: String, default: '' },
  },
  emits: ['close'],
  data() {
    return { file: null, loading: false, message: '', highlighterFailed: false, copied: false };
  },
  computed: {
    plain() {
      return !!(this.file && (!canHighlight(this.file) || this.highlighterFailed));
    },
    rows() {
      return this.file ? plainRows(this.file.content) : [];
    },
    codeHref() {
      return buildProjectHash({ projectId: this.projectId, dir: this.dir, path: this.path });
    },
  },
  mounted() {
    // Capture phase: Monaco stops Escape while it has focus. Its find box keeps Escape to close itself.
    this._onKey = (e) => {
      if (e.key !== 'Escape') return;
      const t = e.target;
      if (t && t.closest && t.closest('.find-widget')) return;
      this.$emit('close');
    };
    window.addEventListener('keydown', this._onKey, true);
    this.$nextTick(() => this.$refs.close && this.$refs.close.focus());
    this.load();
  },
  unmounted() {
    window.removeEventListener('keydown', this._onKey, true);
    clearTimeout(this._copiedTimer);
    if (this._abort) this._abort.abort();
    if (this._editor) this._editor.dispose();
    this._editor = null;
  },
  methods: {
    async load() {
      if (this.mark === 'gone') {
        this.message = 'This edit deleted the file, so there is nothing to show.';
        return;
      }
      if (isImagePath(this.path)) {
        this.message = 'This is an image. Open it in the Code tab to preview it.';
        return;
      }
      const ac = new AbortController();
      this._abort = ac;
      this.loading = true;
      try {
        const q =
          'project=' +
          encodeURIComponent(this.projectId) +
          (this.dir ? '&dir=' + encodeURIComponent(this.dir) : '') +
          '&path=' +
          encodeURIComponent(this.path);
        const data = await get('/api/project/file?' + q, { signal: ac.signal });
        if (ac.signal.aborted) return;
        this.file = data;
      } catch (e) {
        if (ac.signal.aborted || (e && e.name === 'AbortError')) return;
        this.message = viewerMessage(e);
        return;
      } finally {
        if (!ac.signal.aborted) this.loading = false;
      }
      await this.paint();
    },
    async paint() {
      if (!this.file || this.plain) return;
      let monaco;
      try {
        monaco = await ensureMonaco();
      } catch {
        this.highlighterFailed = true;
        return;
      }
      await this.$nextTick(); // the host shows once loading is over; Monaco measures it at create
      const host = this.$refs.editor;
      if (!host || !this.file || this._editor) return;
      this._editor = monaco.editor.create(
        host,
        viewerOptions({
          content: this.file.content,
          lang: this.file.lang || langFromPath(this.path),
          readOnly: true,
        }),
      );
      this._editor.layout();
    },
    async copyPath() {
      try {
        await copyText(this.path);
        this.copied = true;
        clearTimeout(this._copiedTimer);
        this._copiedTimer = setTimeout(() => (this.copied = false), 1500);
      } catch {
        // the clipboard refused; the path is on screen to select by hand
      }
    },
    openInCode() {
      this.$emit('close');
      location.hash = this.codeHref;
    },
  },
  template: `
    <div class="modal-bg file-viewer-bg" role="presentation" @click.self="$emit('close')">
      <div class="modal file-viewer" :class="{ 'file-viewer-full': file && !message }" role="dialog" aria-modal="true" :aria-label="'File ' + path">
        <div class="modal-head">
          <jk-icon name="file"></jk-icon>
          <span class="file-viewer-path" :data-tip="path"><bdi dir="ltr">{{ path }}</bdi></span>
          <span class="chip file-viewer-ro" data-tip="Opened read-only: nothing you do here changes the file">read-only</span>
          <span class="spacer"></span>
          <button type="button" class="action-cyan" @click="copyPath()" data-tip="Copy the path">
            <jk-icon name="copy"></jk-icon>{{ copied ? 'Copied' : 'Copy path' }}</button>
          <a v-if="mark !== 'gone'" class="action-cyan file-viewer-code" :href="codeHref" @click.prevent="openInCode()"
             data-tip="Open this file in the project's Code tab">Open in Code</a>
          <button type="button" class="icon-btn" ref="close" @click="$emit('close')" aria-label="Close" data-tip="Close (Esc)">
            <jk-icon name="x"></jk-icon></button>
        </div>
        <p v-if="loading" class="empty file-viewer-msg">Loading…</p>
        <p v-else-if="message" class="file-viewer-msg" :class="mark === 'gone' ? 'dim' : 'error'">{{ message }}</p>
        <template v-else-if="plain">
          <p class="warn file-viewer-msg">{{ highlighterFailed ? 'Highlighter failed to load; showing plain text' : 'Highlighting skipped (file too large)' }}</p>
          <pre class="code-pre code-plain file-viewer-plain modal-scroll"><div v-for="(row, i) in rows" :key="i" class="code-plain-row fail-src"><span class="fail-gutter">{{ i + 1 }}</span><span class="fail-gutter-rail">│</span><span class="fail-src-code">{{ row }}</span></div></pre>
        </template>
        <div v-show="file && !loading && !message && !plain" class="file-viewer-editor" ref="editor"></div>
      </div>
    </div>`,
};
