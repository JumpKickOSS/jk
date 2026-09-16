// SPDX-License-Identifier: Apache-2.0

import { fmtDuration } from './format.js';

// The per-iteration strip under a run: what changed since the run before it from the same origin
// (the engine's `delta` on the journal record — the same facts `jk-results.md` prints under
// "Since the previous run" and MCP `jk_results` returns as `delta`). One line of chips, counts
// first: files changed, diagnostics that appeared / went away, tests that flipped, and the wall
// against the previous attempt. Each chip's tooltip lists the rows the engine kept (a bounded
// head, `+N more` beyond it); clicking the strip opens them inline.

/** `count` of a rows object, 0 when the comparison is absent. */
const n = (rows) => (rows && typeof rows.count === 'number' ? rows.count : 0);

/** The rows' shown head plus the `+N more` tail, one per line, for a tooltip or the open list. */
export function rowLines(rows) {
  if (!rows || !Array.isArray(rows.shown)) return [];
  const lines = rows.shown.slice();
  const more = n(rows) - lines.length;
  if (more > 0) lines.push('+' + more + ' more');
  return lines;
}

/** `−2.3s` / `+410ms` / `±0`, the sign first so a faster attempt reads at a glance. */
export function signedDuration(deltaMillis) {
  if (!deltaMillis) return '±0';
  return (deltaMillis < 0 ? '−' : '+') + fmtDuration(Math.abs(deltaMillis), { compact: true });
}

/**
 * The strip's chips for `delta` (a record's `delta` object) and the run's own `millis`. Each chip:
 * `key`, `text`, `cls` (`good` / `bad` / '' for neutral) and `lines` (its tooltip and open list).
 * The `since` label names the previous attempt. Null when there is no delta to show.
 */
export function deltaSummary(delta, millis) {
  if (!delta || typeof delta !== 'object') return null;
  const since =
    'since #' +
    (delta.previousBuildNumber || '?') +
    ' · ' +
    (delta.previousSuccess ? 'ok' : 'failed') +
    ' · ' +
    fmtDuration(delta.previousMillis || 0, { compact: true });
  const chips = [];
  if (delta.files) {
    const c = n(delta.files);
    chips.push({ key: 'files', text: c + (c === 1 ? ' file' : ' files'), cls: '', lines: rowLines(delta.files) });
  }
  const appeared = n(delta.appeared);
  const gone = n(delta.gone);
  chips.push({
    key: 'diagnostics',
    text: '+' + appeared + ' / −' + gone + ' diagnostics',
    cls: appeared > 0 ? 'bad' : gone > 0 ? 'good' : '',
    lines: rowLines(delta.appeared)
      .map((l) => 'appeared: ' + l)
      .concat(rowLines(delta.gone).map((l) => 'gone: ' + l)),
  });
  if (delta.broke && delta.fixed && delta.added && delta.dropped) {
    const broke = n(delta.broke);
    const fixed = n(delta.fixed);
    const added = n(delta.added);
    const dropped = n(delta.dropped);
    const parts = [];
    if (fixed) parts.push(fixed + ' fixed');
    if (broke) parts.push(broke + ' broke');
    if (added) parts.push(added + ' new');
    if (dropped) parts.push(dropped + ' gone');
    chips.push({
      key: 'tests',
      text: parts.length ? 'tests: ' + parts.join(' · ') : 'same tests',
      cls: broke > 0 ? 'bad' : fixed > 0 ? 'good' : '',
      lines: rowLines(delta.fixed)
        .map((l) => 'fixed: ' + l)
        .concat(rowLines(delta.broke).map((l) => 'broke: ' + l))
        .concat(rowLines(delta.added).map((l) => 'new: ' + l))
        .concat(rowLines(delta.dropped).map((l) => 'gone: ' + l)),
    });
  }
  if (typeof millis === 'number' && typeof delta.previousMillis === 'number') {
    const d = millis - delta.previousMillis;
    chips.push({ key: 'wall', text: signedDuration(d), cls: d < 0 ? 'good' : d > 0 ? 'bad' : '', lines: [] });
  }
  return { since, chips };
}

export const RunDelta = {
  props: {
    delta: { type: Object, required: true },
    millis: { type: Number, default: null },
  },
  data: () => ({ open: false }),
  template: `
    <div class="run-delta" :class="{ open }">
      <button type="button" class="run-delta-strip" :aria-expanded="String(open)" @click="open = !open"
              data-tip="what changed since the previous attempt from this origin">
        <span class="run-delta-since">{{ summary.since }}</span>
        <template v-for="c in summary.chips" :key="c.key">
          <span class="run-delta-sep" aria-hidden="true">·</span>
          <span class="run-delta-chip" :class="c.cls" :data-tip="c.lines.length ? c.lines.join('\\n') : undefined">{{ c.text }}</span>
        </template>
      </button>
      <ul v-if="open && openLines.length" class="run-delta-rows mono">
        <li v-for="(l, i) in openLines" :key="i">{{ l }}</li>
      </ul>
    </div>`,
  computed: {
    summary() {
      return deltaSummary(this.delta, this.millis) || { since: '', chips: [] };
    },
    openLines() {
      const out = [];
      for (const c of this.summary.chips) for (const l of c.lines) out.push(c.key + ' · ' + l);
      return out;
    },
  },
};
