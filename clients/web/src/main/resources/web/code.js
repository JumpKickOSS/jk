// SPDX-License-Identifier: Apache-2.0
// The files pane: `<code-view>`, the component behind #project/<id>/files/<rel>. Owns the tree, the
// Monaco instance, the dirty/save state machine and the preview split; the pure helpers it drives
// live in paths.js, route.js, tree.js, monaco.js and preview.js.

import { canHighlight, copyText, ensureMonaco, lineDecorations, viewerOptions } from './monaco.js';
import {
  baseFileName,
  isImagePath,
  isPreviewable,
  isTextWritableLang,
  langFromPath,
  saveErrorMessage,
} from './paths.js';
import { previewMethods } from './preview.js';
import { ancestorDirs, buildFileTree, defaultFilePath, plainRows, visibleRows } from './tree.js';

export const CodeView = {
  props: {
    projectId: { type: String, default: null },
    /** The checkout to list and edit; the engine requires it when the id has several live ones. */
    dir: { type: String, default: null },
    path: { type: String, default: null },
    line: { type: Number, default: 0 },
    /** 1-based column from {@code ?col=}; 0 means line-only. */
    col: { type: Number, default: 0 },
    /** True when the hash carried {@code err=true} (fail-report / OSC-8 jump). */
    lineErr: { type: Boolean, default: false },
    /** Compiler / failure note from {@code ?msg=} — shown on hover. */
    msg: { type: String, default: '' },
  },
  emits: ['navigate', 'build', 'saved'],
  data: () => ({
    loadingList: false,
    loadingFile: false,
    error: null,
    notice: null,
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
    saveConfirmOpen: false,
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
    col() {
      this.$nextTick(() => this.scrollToLine());
    },
    msg() {
      this.$nextTick(() => this.applyLineDecorations());
    },
    // A buffer going clean releases an epoch reload the user declined while dirty.
    dirty(next) {
      if (!next && this._api) this._api.releaseDeferredEpochReload();
    },
    lineErr() {
      this.$nextTick(() => this.applyLineDecorations());
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
    // Browser-level loss guards: warn on tab close/F5 with unsaved edits, and let the
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
    // Capture phase so Ctrl/Cmd+S wins over Monaco and the browser "Save page" default.
    this._onSaveKey = (e) => {
      if (e.defaultPrevented) return;
      if (!(e.key === 's' || e.key === 'S')) return;
      if (!(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return;
      e.preventDefault();
      this.requestSave();
    };
    window.addEventListener('keydown', this._onSaveKey, true);
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
      if (this._onSaveKey) {
        window.removeEventListener('keydown', this._onSaveKey, true);
        this._onSaveKey = null;
      }
      if (this._api && this._dirtyProbe) {
        this._api.unregisterDirtyGuard(this._dirtyProbe);
        this._dirtyProbe = null;
      }
      this.settleSaveConfirm(false, { restoreFocus: false });
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
    ...previewMethods,
    confirmDiscard() {
      if (typeof window === 'undefined' || !window.confirm) return true;
      return window.confirm('Discard unsaved changes?');
    },
    /** In-page Save confirm. Resolves true only on Save. */
    confirmSave() {
      if (this.saveConfirmOpen) return this._saveConfirmPromise || Promise.resolve(false);
      this.saveConfirmOpen = true;
      this.bindSaveConfirmKeys();
      this._saveConfirmPromise = new Promise((resolve) => {
        this._saveConfirmResolve = resolve;
      });
      this.$nextTick(() => {
        const btn = this.$refs.saveConfirmOk;
        if (btn && typeof btn.focus === 'function') btn.focus();
      });
      return this._saveConfirmPromise;
    },
    settleSaveConfirm(ok, opts) {
      if (!this.saveConfirmOpen && !this._saveConfirmResolve) return;
      this.saveConfirmOpen = false;
      this.unbindSaveConfirmKeys();
      const resolve = this._saveConfirmResolve;
      this._saveConfirmResolve = null;
      this._saveConfirmPromise = null;
      if (!opts || opts.restoreFocus !== false) {
        this.$nextTick(() => {
          if (this._editor && typeof this._editor.focus === 'function') this._editor.focus();
        });
      }
      if (resolve) resolve(!!ok);
    },
    bindSaveConfirmKeys() {
      if (this._onSaveConfirmKey) return;
      this._onSaveConfirmKey = (e) => this.onSaveConfirmKey(e);
      window.addEventListener('keydown', this._onSaveConfirmKey, true);
    },
    unbindSaveConfirmKeys() {
      if (!this._onSaveConfirmKey) return;
      window.removeEventListener('keydown', this._onSaveConfirmKey, true);
      this._onSaveConfirmKey = null;
    },
    onSaveConfirmKey(e) {
      if (!this.saveConfirmOpen) return;
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        this.settleSaveConfirm(false);
        return;
      }
      if (e.key === 'Tab') {
        const root = this.$refs.saveConfirmDialog;
        if (!root) return;
        const list = root.querySelectorAll('button');
        if (!list.length) return;
        const first = list[0];
        const last = list[list.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
        return;
      }
      if (e.key === 'Enter' && !e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
        if (e.target && e.target.closest && e.target.closest('.confirm-modal button')) return;
        e.preventDefault();
        e.stopPropagation();
        this.settleSaveConfirm(true);
      }
    },
    /** Save button + Ctrl/Cmd+S: confirm, then PUT when the buffer is dirty and writable. */
    async requestSave() {
      if (!this.canSave || this._savePromptOpen) return;
      this._savePromptOpen = true;
      try {
        if (!(await this.confirmSave())) return;
        await this.save();
      } finally {
        this._savePromptOpen = false;
      }
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
    /** `project=<id>[&dir=<checkout>]` — the identity every file request carries. */
    projectQuery() {
      return (
        'project=' + encodeURIComponent(this.projectId) + (this.dir ? '&dir=' + encodeURIComponent(this.dir) : '')
      );
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
        const data = await get('/api/project/files?' + this.projectQuery(), {
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
      this.notice = null;
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
          '/api/project/file?' + this.projectQuery() + '&path=' + encodeURIComponent(this.path),
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
        this.highlighterFailed = false; // a later successful load clears the banner
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
        // Monaco-local binding so Ctrl/Cmd+S still works when the editor owns focus.
        this._editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
          this.requestSave();
        });
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
      this._decorations = this._editor.createDecorationsCollection(this.lineDecorationSpecs());
      if (!options.readOnly && this._model) {
        this._contentSub = this._model.onDidChangeContent(() => this.recomputeDirty());
      }
      this.recomputeDirty();
    },
    lineDecorationSpecs() {
      let text = '';
      if (this._model && this.line > 0) {
        try {
          text = this._model.getLineContent(this.line) || '';
        } catch {
          text = '';
        }
      }
      return lineDecorations(this.line, this.lineErr, this.col, text, this.msg);
    },
    applyLineDecorations() {
      if (this._decorations) this._decorations.set(this.lineDecorationSpecs());
    },
    scrollToLine() {
      if (this.line < 1) return;
      if (this._editor) {
        this.applyLineDecorations();
        const col = this.col > 0 ? this.col : 1;
        this._editor.setPosition({ lineNumber: this.line, column: col });
        this._editor.revealPositionInCenter({ lineNumber: this.line, column: col });
        if (this.msg) {
          // Land with the compiler / failure note open, not only after the user hunts for hover.
          requestAnimationFrame(() => {
            if (this._editor && this.msg) this._editor.trigger('jk', 'editor.action.showHover', {});
          });
        }
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
      this.notice = null;
      // The response must only ever apply to the file it was issued for: navigating away
      // (discard confirmed) while the PUT is in flight would otherwise stamp the OLD file's
      // content/etag onto the NEW file's state.
      const savedPath = this.path;
      try {
        const { put } = await import('./api.js');
        const content = this.currentContent();
        const body = {
          project: this.projectId,
          path: savedPath,
          content,
        };
        if (this.dir) body.dir = this.dir;
        if (this._etag) body.etag = this._etag;
        // Echo the charset the file was decoded under so the engine re-encodes to the
        // original bytes instead of silently transcoding a Latin-1 file to UTF-8.
        if (this.file && this.file.encoding && this.file.encoding !== 'utf-8') {
          body.encoding = this.file.encoding;
        }
        const resp = await put('/api/project/file', body);
        // The dashboard's snippet cache (app.js SOURCE_CACHE) memoizes this file's lines —
        // stale lines silently degrade the next compile-failure context window to a single
        // embedded row until a full reload. Announce the save; app.js evicts.
        try {
          window.dispatchEvent(
            new CustomEvent('jk:file-saved', { detail: { project: this.projectId, path: savedPath } }),
          );
        } catch {
          /* non-browser test env */
        }
        if (this.path !== savedPath) return; // navigated away — the write landed; drop the state
        const nextEtag = (resp && resp.etag) || null;
        if (this.file) this.file = { ...this.file, content, etag: nextEtag };
        this.setBaseline(content, nextEtag);
        if (resp && resp.lockStale) {
          this.notice = 'Manifest saved — the lock is now stale; the next build will re-resolve dependencies';
        }
        this.flashSaved();
        // The parent refreshes project meta on manifest saves (header coord/description).
        this.$emit('saved', { path: savedPath });
      } catch (e) {
        if (this.path !== savedPath) return; // stale failure belongs to a file no longer shown
        this.error = saveErrorMessage(e);
      } finally {
        this.saving = false;
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
        <p v-if="truncated" class="warn">File list truncated at 5000</p>
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
            <button type="button" class="action-cyan" :disabled="!canSave" @click="requestSave()"
                    :data-tip="canSave ? 'Save changes (Ctrl/⌘S)' : (saving ? 'Saving…' : (saved ? 'Saved' : 'No unsaved changes'))">
              <jk-icon name="save"></jk-icon>{{ saving ? 'Saving…' : (saved ? 'Saved' : 'Save') }}
            </button>
          </div>
        </div>
        <section class="code-pane" :class="'mode-' + paneMode">
          <div class="code-msgs">
            <p v-if="error" class="error">{{ error }}</p>
            <p v-if="notice" class="warn">{{ notice }}</p>
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
                     :class="{ 'code-line-on': line === i + 1 && !lineErr, 'code-line-err-plain': line === i + 1 && lineErr }"
                     :title="line === i + 1 && msg ? msg : undefined">
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
      <teleport to="body">
        <div v-if="saveConfirmOpen" class="modal-bg confirm-modal-bg" role="presentation"
             @click.self="settleSaveConfirm(false)">
          <div class="modal confirm-modal" role="alertdialog" aria-modal="true"
               aria-labelledby="save-confirm-title" aria-describedby="save-confirm-desc"
               tabindex="-1" ref="saveConfirmDialog">
            <div class="modal-head">
              <jk-icon name="save"></jk-icon>
              <span id="save-confirm-title" class="lbl">Save file</span>
            </div>
            <p id="save-confirm-desc" class="confirm-body">
              Are you sure you want to save
              <span class="confirm-file" :data-tip="path || undefined">{{ fileName(path) }}</span>?
            </p>
            <div class="modal-foot confirm-actions">
              <button type="button" class="ghost" @click="settleSaveConfirm(false)">Cancel</button>
              <button type="button" class="primary" ref="saveConfirmOk" @click="settleSaveConfirm(true)">
                <jk-icon name="save"></jk-icon>
                Save
              </button>
            </div>
          </div>
        </div>
      </teleport>
    </div>`,
};
