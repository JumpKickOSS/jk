// SPDX-License-Identifier: Apache-2.0
// `<fail-report>`: one body for a test or compile failure, shared by the compact card and the
// per-module branch. The two inline template copies it replaced had already drifted apart. Also
// owns the memoized workspace-source fetch the snippet gutter reads from.

import { get } from './api.js';
import { snippetWindow } from './failure.js';
import { detailSegments } from './label.js';
import { codePathForFailure } from './paths.js';
import { buildProjectHash, locusLabel } from './route.js';


// One fail-report body shared by the compact-card and workspace-module branches — the two
// inline template copies drifted once already; a single component cannot.
export const FailReport = {
  props: {
    rep: { type: Object, required: true },
    projectId: { type: String, default: null },
    checkoutDir: { type: String, default: null },
    moduleDir: { type: String, default: null },
  },
  data: () => ({ fetchedLines: null }),
  computed: {
    isCompile() {
      return !!(this.rep && this.rep.kind === 'compile');
    },
    headerLabel() {
      return this.isCompile ? 'Compile failure' : 'Test failure';
    },
    codePath() {
      return codePathForFailure({
        checkoutDir: this.checkoutDir,
        moduleDir: this.moduleDir,
        file: this.rep.file,
      });
    },
    canOpen() {
      return !!(this.projectId && this.codePath);
    },
    displayRows() {
      const r = this.rep;
      if (this.isCompile && this.fetchedLines && r.line > 0 && r.line <= this.fetchedLines.length) {
        const current = this.fetchedLines[r.line - 1];
        const embedded = r.rows && r.rows.length === 1 ? r.rows[0].code : null;
        if (embedded != null && current !== embedded) return r.rows;
        return snippetWindow(this.fetchedLines, r.line);
      }
      return (r && r.rows) || [];
    },
    /** Path plus line/col so a copied fail-report still names the jump. */
    fileLocus() {
      const r = this.rep;
      if (!r) return '';
      const path = this.codePath || r.file || '';
      if (!path) return '';
      return locusLabel(path, r.line || 0, r.column || r.col || 0);
    },
    /** Real hash deep link into the Monaco files pane (copyable, middle-clickable). */
    deepLink() {
      if (!this.canOpen) return undefined;
      return buildProjectHash({
        projectId: this.projectId,
        files: true,
        path: this.codePath,
        line: this.rep.line || 0,
        col: this.rep.column || this.rep.col || 0,
        err: true, // red error-line highlight on the fail jump
        msg: this.hoverNote,
      });
    },
    /** Assertion / exception / compiler note for the Monaco hover ({@code ?msg=}). */
    hoverNote() {
      const r = this.rep;
      if (!r) return '';
      if (r.kind === 'compile') {
        return (r.kvs || []).map((kv) => kv.key + ': ' + (kv.value || '')).join('\n');
      }
      if (r.assertj) {
        const lines = [];
        if (r.assertj.desc) lines.push(String(r.assertj.desc));
        lines.push('Expected: ' + (r.assertj.expected ?? ''));
        lines.push('But was: ' + (r.assertj.actual ?? ''));
        return lines.join('\n');
      }
      const lines = [];
      if (r.exceptionClass) lines.push(String(r.exceptionClass));
      if (r.message) lines.push(String(r.message));
      return lines.join('\n');
    },
  },
  watch: {
    codePath: { immediate: true, handler() { this.loadSource(); } },
    projectId() { this.loadSource(); },
  },
  methods: {
    failLabelSegs(label) {
      return detailSegments(label);
    },
    async loadSource() {
      if (!this.isCompile || !this.projectId || !this.codePath) {
        this.fetchedLines = null;
        return;
      }
      const gen = (this._srcGen = (this._srcGen || 0) + 1);
      const lines = await loadProjectFileLines(this.projectId, this.codePath);
      if (gen !== this._srcGen) return;
      this.fetchedLines = lines;
    },
  },
  template: `
    <template v-if="rep.showHeader">
      <div class="fail-head">
        <span class="console-err">\u2718</span>
        <span class="fail-mid">\u00a0{{ headerLabel }}</span>
        <template v-if="rep.module">
          <span class="fail-mid"> in </span><span class="det-coord">{{ rep.module }}</span>
        </template>
        <template v-if="!isCompile">
          <span class="console-sep"> \u203a </span>
          <span class="det-focus">{{ rep.count }}</span>
          <span class="fail-mid"> test{{ rep.count === 1 ? '' : 's' }} failed</span>
        </template>
      </div>
      <div class="fail-blank"></div>
    </template>
    <template v-if="isCompile">
      <div v-for="(kv, ki) in (rep.kvs || [])" :key="'kv'+ki" class="fail-line">
        <span class="fail-mid">{{ kv.key }}</span><span class="fail-dim">: </span><span class="fail-kv">{{ kv.value }}</span>
      </div>
      <div v-for="(ex, ei) in (rep.extras || [])" :key="'ex'+ei" class="fail-line fail-mid">{{ ex }}</div>
    </template>
    <template v-else>
      <div class="fail-line">
        <span class="fail-failed">FAILED&nbsp;</span><template v-for="(seg, si) in failLabelSegs(rep.label)" :key="si">
          <span :class="seg.cls">{{ seg.text }}</span>
        </template>
      </div>
      <div class="fail-blank"></div>
      <template v-if="rep.assertj">
        <div v-if="rep.assertj.desc" class="fail-line">
          <span class="fail-dim">"</span><span class="fail-desc">{{ rep.assertj.desc }}</span><span class="fail-dim">"</span>
        </div>
        <!-- expected/actual bodies stay uncolored, matching the CLI (d8dd7760) —
             values are data, not verdicts; the FAILED chip already carries the verdict. -->
        <div class="fail-line">
          <span class="fail-mid">&nbsp;Expected:&nbsp;</span><span>{{ rep.assertj.expected }}</span>
        </div>
        <div class="fail-line">
          <span class="fail-mid">&nbsp;&nbsp;But Was:&nbsp;</span><span>{{ rep.assertj.actual }}</span>
        </div>
      </template>
      <template v-else-if="rep.message">
        <div class="fail-line fail-mid" v-for="(ml, mi) in rep.message.split('\\n')" :key="'m'+mi">{{ ml }}</div>
      </template>
    </template>
    <template v-if="rep.file || displayRows.length">
      <div class="fail-blank"></div>
      <component
        v-if="fileLocus"
        :is="canOpen ? 'a' : 'div'"
        class="fail-line fail-path"
        :class="{ link: canOpen }"
        :href="deepLink"
      >{{ fileLocus }}</component>
      <div
        v-for="(row, ri) in displayRows"
        :key="'s'+ri"
        class="fail-src"
        :class="{ 'fail-src-err': row.error }"
      >
        <span class="fail-gutter" :class="{ 'fail-gutter-err': row.error }">{{ row.gutter }}</span><span class="fail-gutter-rail">\u2502</span><span class="fail-src-code">{{ row.code }}{{ ' '.repeat(row.pad) }}</span>
      </div>
      <div v-if="!isCompile && rep.exceptionClass" class="fail-line fail-thrown">
        <span class="det-type">{{ rep.exceptionClass }}</span><span class="fail-mid"> thrown at line </span><span class="det-focus">{{ rep.line }}</span>
      </div>
    </template>
    <template v-else-if="!isCompile">
      <div v-if="rep.exceptionClass" class="fail-line fail-thrown">
        <span class="det-type">{{ rep.exceptionClass }}</span>
      </div>
      <div v-for="(fr, fi) in (rep.frames || [])" :key="'st'+fi" class="fail-line fail-stack">{{ fr }}</div>
    </template>
  `,
};

const SOURCE_CACHE = new Map();
const MAX_SNIPPET_FILE = 1 << 20;
const MAX_SOURCE_CACHE = 64;

// An editor save makes the memoized lines stale: the next failure's displayRows would compare
// the fresh embedded snippet against old lines, mismatch, and silently degrade to the 1-row
// snippet until a full page reload. code.js announces saves; evict here.
if (typeof window !== 'undefined') {
  window.addEventListener('jk:file-saved', (e) => {
    const d = (e && e.detail) || {};
    SOURCE_CACHE.delete(String(d.project) + '\0' + String(d.path));
  });
}

/** Workspace file as lines, memoized per {@code projectId + path}. Failed reads are not cached. */
function loadProjectFileLines(projectId, path) {
  const key = String(projectId) + '\0' + String(path);
  if (SOURCE_CACHE.has(key)) return SOURCE_CACHE.get(key);
  // Clear-on-overflow bound: one entry per distinct failing file ever viewed.
  if (SOURCE_CACHE.size >= MAX_SOURCE_CACHE) SOURCE_CACHE.clear();
  const p = get(
    '/api/project/file?project=' + encodeURIComponent(projectId) + '&path=' + encodeURIComponent(path),
  )
    .then((data) => {
      const content = data && typeof data.content === 'string' ? data.content : '';
      if (!content || content.length > MAX_SNIPPET_FILE) return null;
      return content.split('\n');
    })
    .catch(() => {
      SOURCE_CACHE.delete(key);
      return null;
    });
  SOURCE_CACHE.set(key, p);
  return p;
}
