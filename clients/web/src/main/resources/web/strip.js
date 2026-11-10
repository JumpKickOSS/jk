// SPDX-License-Identifier: Apache-2.0
// A run reduced to what a timeline line and the run page need: what it is doing right now, one
// cell per module coloured by state, and which modules deserve to open by default (the failed and
// the slow). Pure functions over a folded card (fold.js), so the suite runs them headlessly.

import { detailSegments, liveStepDetail } from './label.js';
import { orderedModules, outcomeOf, phaseChainOf } from './outcome.js';

/** The verb a running phase reads as on the one-line live build: `testing backend/service`. */
const PHASE_VERB = Object.freeze({
  resolve: 'resolving',
  generate: 'generating',
  compile: 'compiling',
  test: 'testing',
  package: 'packaging',
  publish: 'publishing',
  native: 'building native',
  image: 'building image',
});

/** A module row's name: the artifact of its coord (`core`), else its directory's tail. */
export function moduleName(m) {
  if (!m) return '';
  if (m.coord) {
    const i = m.coord.lastIndexOf(':');
    return i >= 0 ? m.coord.slice(i + 1) : m.coord;
  }
  const parts = String(m.dir || '').split(/[\\/]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : 'project';
}

/** A module's state as a cell paints it: failed, running, cancelled, waiting or done. */
export function cellState(m) {
  if (!m) return 'waiting';
  if (m.state === 'failed' || (m.diagnostics && m.diagnostics.length)) return 'failed';
  if (m.state === 'running') return 'running';
  if (m.state === 'cancelled') return 'cancelled';
  return 'done';
}

/** The phase a module is in (the running one), else the last it reached; '' with no steps yet. */
function currentPhase(m) {
  const phases = phaseChainOf(m);
  const running = phases.filter((p) => p.state === 'running').pop();
  const p = running || phases[phases.length - 1];
  return p ? p.label : '';
}

/**
 * One cell per module, in plan order: `{ key, name, state, phase, tip }`. A record that knows the
 * workspace's module count (`moduleCount`) pads the strip with waiting cells for the modules the
 * run has not reached, so a 55-module workspace is 55 cells from its first frame.
 */
export function moduleCells(card) {
  const mods = (card && card.modules) || [];
  const cells = mods.map((m) => {
    const name = moduleName(m);
    const phase = currentPhase(m);
    const state = cellState(m);
    return { key: m.dir || '.', name, state, phase, tip: phase ? name + ' · ' + phase : name };
  });
  const total = card && typeof card.moduleCount === 'number' ? card.moduleCount : 0;
  for (let i = cells.length; i < total; i++) {
    cells.push({ key: 'waiting-' + i, name: '', state: 'waiting', phase: '', tip: 'not started' });
  }
  return cells;
}

/**
 * What a running build is doing, as one line: the most recently active running module, its phase
 * as a verb and the step's live detail (`testing service · QuoteServiceIT.weekendStay()`). With
 * several modules running, the count of the others follows. '' before the first module starts.
 */
export function doingLine(card) {
  return doingSegments(card)
    .map((s) => s.text)
    .join('');
}

/**
 * {@link doingLine} as colour segments `{ text, cls }`, in the CLI's `colorDetail` roles: the verb
 * and separators mid-gray, the module plain, the detail through {@link detailSegments} (the class
 * gold, the method purple) and the count of other running modules as a number. [] when the run
 * is not running.
 */
export function doingSegments(card) {
  if (!card || outcomeOf(card) !== 'running') return [];
  const running = orderedModules((card.modules || []).filter((m) => m.state === 'running'));
  const m = running[0];
  if (!m) return [{ text: card.modules && card.modules.length ? 'finishing' : 'starting', cls: 'det-mid' }];
  const phases = phaseChainOf(m);
  const p = phases.filter((x) => x.state === 'running').pop();
  const verb = p ? PHASE_VERB[p.phase] || p.label.toLowerCase() : 'working on';
  const detail = liveStepDetail(m.coord || '', m.steps);
  const segs = [
    { text: verb + ' ', cls: 'det-mid' },
    { text: moduleName(m), cls: 'live-mod' },
  ];
  if (detail) segs.push({ text: ' · ', cls: 'det-dim' }, ...detailSegments(detail));
  if (running.length > 1) {
    segs.push({ text: ' (+', cls: 'det-mid' }, { text: String(running.length - 1), cls: 'det-num' }, { text: ' more)', cls: 'det-mid' });
  }
  return segs;
}

/** The pane never reserves more worker rows than this, whatever the build's width. */
export const MAX_LANES = 4;

/** Per-card lane memory (sticky slots + row count); a WeakMap so a render never writes reactive state. */
const laneMemory = new WeakMap();

/**
 * The fixed worker rows of a running build: exactly {@link #laneSize} entries, each either
 * `{ idle: true }` or `{ key, module, op, segs, tip }` (the module's artifactId, the running
 * phase's label, and the live detail's colour segments without the trailing `[wN]`).
 *
 * The rows never come and go while the build runs. The row count is seeded from the workspace's
 * module count (at most {@link MAX_LANES}) and can only grow, up to the most modules ever seen
 * running at once; a module keeps its row for as long as it runs, and a freed row shows `idle`.
 * [] when the run is not running.
 */
export function workerLanes(card) {
  if (!card || outcomeOf(card) !== 'running') return [];
  const mem = laneMemory.get(card) || { slots: [], size: 0 };
  laneMemory.set(card, mem);
  const running = orderedModules((card.modules || []).filter((m) => m.state === 'running'));
  const seed = Math.min(MAX_LANES, Math.max(1, typeof card.moduleCount === 'number' ? card.moduleCount : 1));
  mem.size = Math.min(MAX_LANES, Math.max(mem.size, seed, running.length));
  const live = new Set(running.map((m) => m.dir));
  mem.slots = mem.slots.slice(0, mem.size).map((d) => (d != null && live.has(d) ? d : null));
  while (mem.slots.length < mem.size) mem.slots.push(null);
  for (const m of running) {
    if (mem.slots.includes(m.dir)) continue;
    const free = mem.slots.indexOf(null);
    if (free >= 0) mem.slots[free] = m.dir;
  }
  return mem.slots.map((dir) => {
    const m = dir == null ? null : running.find((x) => x.dir === dir);
    return m ? laneOf(m) : { idle: true };
  });
}

function laneOf(m) {
  const p = phaseChainOf(m).filter((x) => x.state === 'running').pop();
  const detail = liveStepDetail(m.coord || '', m.steps);
  const segs = detailSegments(detail).filter((x) => !x.text.startsWith('  [w'));
  const module = moduleName(m);
  const op = p ? p.label : 'Working';
  return { key: m.dir || '.', module, op, segs, tip: [module, op, segs.map((x) => x.text).join('')].filter(Boolean).join(' › ') };
}

/** A module is slow when its wall is at least this multiple of the run's median module wall… */
export const SLOW_FACTOR = 2;
/** …and at least this long, so a 40 ms module beside 10 ms ones is not called slow. */
export const SLOW_MIN_MILLIS = 5_000;
/** At most this many slow modules open by default; the rest stay in the strip. */
export const SLOW_MAX = 3;

function median(xs) {
  if (!xs.length) return 0;
  const s = xs.slice().sort((a, b) => a - b);
  const mid = s.length >> 1;
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

/**
 * The run page's module split: `expanded` holds the failed modules, then the slow ones (the
 * slowest first, at most {@link SLOW_MAX}), each `{ module, reason }`; `rest` holds every other
 * module in display order. A one-module run that failed expands; one that passed does not.
 */
export function moduleFocus(card) {
  const mods = (card && card.modules) || [];
  const failed = mods.filter((m) => cellState(m) === 'failed');
  const timed = mods.filter((m) => cellState(m) === 'done' && typeof m.millis === 'number');
  const bar = Math.max(SLOW_MIN_MILLIS, SLOW_FACTOR * median(timed.map((m) => m.millis)));
  const slow =
    mods.length > 1
      ? timed
          .filter((m) => m.millis >= bar)
          .sort((a, b) => b.millis - a.millis)
          .slice(0, SLOW_MAX)
      : [];
  const open = new Set([...failed, ...slow]);
  return {
    expanded: [
      ...orderedModules(failed).map((module) => ({ module, reason: 'failed' })),
      ...slow.map((module) => ({ module, reason: 'slow' })),
    ],
    rest: orderedModules(mods.filter((m) => !open.has(m))),
  };
}
