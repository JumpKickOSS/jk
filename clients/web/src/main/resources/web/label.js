// SPDX-License-Identifier: Apache-2.0
// Text the dashboard paints next to a step: the live tick detail, and the segment lists that give
// a Java type, member or JDK-download label its colour classes. This is a port of the CLI's
// renderers, so both surfaces shorten the same string the same way (see docs/webclient.md).

import { phaseChainOf } from './outcome.js';

/**
 * Live detail after the running phase node (CLI tree-row parity). Strips a leading
 * {@code module :: } prefix when the engine embeds the coordinate in test labels, and
 * shortens any package FQCNs so the UI never paints wire-shaped type names.
 */
export function detailForDisplay(module, message) {
  if (message == null || message === '') return '';
  let msg = String(message).trim();
  const mod = module == null ? '' : String(module).trim();
  if (mod && msg.startsWith(mod + ' :: ')) {
    msg = msg.slice(mod.length + 4).trim();
  }
  return shortDisplayLabel(msg);
}

/**
 * The live message of the rightmost running phase's running step (or ''), after
 * {@link detailForDisplay}. {@code module} is the coord used to strip redundant prefixes.
 */
export function liveStepDetail(module, steps) {
  const phases = phaseChainOf({ steps: steps || [] });
  for (let i = phases.length - 1; i >= 0; i--) {
    const p = phases[i];
    if (p.state !== 'running') continue;
    for (let j = p.steps.length - 1; j >= 0; j--) {
      const s = p.steps[j];
      if (s.state === 'running' && s.message) {
        return detailForDisplay(module, s.message);
      }
    }
  }
  return '';
}

/** {@code FooTest}, {@code FooTest.bar()}, or {@code FooTest.bar(Path)} — not free prose. */
const JAVA_MEMBER = /^[A-Z][\w$]*(?:\.[A-Za-z_][\w$]*(?:\([^)]*\))?)?$/;

export function looksLikeJavaMember(s) {
  return !!(s && JAVA_MEMBER.test(s));
}

/**
 * Color segments for a live step detail (CLI {@code colorDetail} roles).
 * Each segment is {@code { text, cls }} with cls in:
 * {@code det-type | det-fn | det-num | det-path | det-coord | det-mid | det-dim}.
 * FQCNs in the text are shortened before segmentation.
 */
export function detailSegments(detail) {
  if (detail == null || detail === '') return [];
  let body = shortDisplayLabel(String(detail));
  let worker = '';
  // progressLabel appends " [w2]" — keep it outside the Java highlighter.
  const w = body.lastIndexOf('  [w');
  if (w > 0 && body.endsWith(']')) {
    worker = body.slice(w);
    body = body.slice(0, w);
  }
  const jdk = jdkProgressSegments(body);
  const segs = jdk || (looksLikeJavaMember(body) ? javaMemberSegments(body) : proseSegments(body));
  if (worker) segs.push({ text: worker, cls: 'det-mid' });
  return segs;
}

/** {@code downloading Temurin 25 ▰▰▰▰▰▱▱▱▱▱ 50%} — CLI JdkProgressLabel / colorJdkProgressDetail. */
const JDK_PROGRESS =
  /^(downloading|installing) (.+?)(?: ([▰▱]+) (\d+)%)?$/;

export function jdkProgressSegments(detail) {
  if (detail == null || detail === '') return null;
  const m = String(detail).trim().match(JDK_PROGRESS);
  if (!m) return null;
  const segs = [
    { text: m[1], cls: 'det-mid' },
    { text: ' ', cls: 'det-mid' },
    { text: m[2], cls: 'det-jdk' },
  ];
  if (m[3]) {
    segs.push({ text: ' ', cls: 'det-mid' });
    for (const ch of m[3]) {
      segs.push({ text: ch, cls: ch === '▰' ? 'det-bar-fill' : 'det-bar-empty' });
    }
    segs.push({ text: ' ', cls: 'det-mid' });
    segs.push({ text: `${m[4]}%`, cls: 'det-mid' });
  }
  return segs;
}

/** Capitalized id → type; lower id before `(` → function; else mid-gray. */
function javaMemberSegments(s) {
  const segs = [];
  let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (/[A-Z]/.test(c)) {
      let j = i + 1;
      while (j < s.length && /[\w$]/.test(s[j])) j++;
      segs.push({ text: s.slice(i, j), cls: 'det-type' });
      i = j;
      continue;
    }
    if (/[a-z_]/.test(c)) {
      let j = i + 1;
      while (j < s.length && /[\w$]/.test(s[j])) j++;
      let k = j;
      while (k < s.length && s[k] === ' ') k++;
      const cls = k < s.length && s[k] === '(' ? 'det-fn' : 'det-mid';
      segs.push({ text: s.slice(i, j), cls });
      i = j;
      continue;
    }
    segs.push({ text: c, cls: 'det-mid' });
    i++;
  }
  return segs;
}

/**
 * Prose detail: mid-gray body with numbers, path-like tokens, and g:a coords picked out
 * (simplified CLI {@code colorProseDetail}).
 */
function proseSegments(text) {
  const segs = [];
  const re =
    /(\b\d+(?:\.\d+)?\b)|((?:~\/|\/|\.\/|\.\.\/)[\w./+\-]+|[\w.-]+\.(?:jar|war|ear|zip|class|kt|java|groovy)\b)|(\b[\w.-]+:[\w.-]+(?::[\w.-]+)?\b)|([^\s]+)|(\s+)/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    if (m[1] != null) segs.push({ text: m[1], cls: 'det-num' });
    else if (m[2] != null) segs.push({ text: m[2], cls: 'det-path' });
    else if (m[3] != null && m[3].includes(':')) segs.push({ text: m[3], cls: 'det-coord' });
    else if (m[4] != null) segs.push({ text: m[4], cls: 'det-mid' });
    else if (m[5] != null) segs.push({ text: m[5], cls: 'det-mid' });
  }
  return segs;
}

// ---- test-failure report (CLI TestFailureHighlight parity, no thick rail) ----

/** Simple class name from FQCN. */
export function simpleTypeName(fqcn) {
  if (!fqcn) return '';
  const s = String(fqcn);
  const d = s.lastIndexOf('.');
  return d >= 0 ? s.slice(d + 1) : s;
}

/**
 * Human-facing member label: drop package FQCNs so the UI never paints wire-shaped names.
 * {@code cc.jumpkick.FooTest.bar(java.nio.file.Path)} → {@code FooTest.bar(Path)}.
 * Preserves a trailing {@code [wN]} worker tag when present. Leaves ordinary prose, versions,
 * and jar names untouched.
 */
export function shortDisplayLabel(raw) {
  if (raw == null || raw === '') return raw == null ? '' : raw;
  let worker = '';
  let body = String(raw).trim();
  const w = body.lastIndexOf('  [w');
  if (w > 0 && body.endsWith(']')) {
    worker = body.slice(w);
    body = body.slice(0, w).trim();
  }
  if (!looksLikeJavaishLabel(body)) return worker ? body + worker : body;
  body = simplifyMethodParams(body);
  const paren = body.indexOf('(');
  const searchEnd = paren >= 0 ? paren : body.length;
  const dot = body.lastIndexOf('.', searchEnd - 1);
  if (dot > 0 && dot < body.length - 1) {
    const after = body.slice(dot + 1, searchEnd);
    if (after && (/^[a-z_]/.test(after) || after.startsWith('<'))) {
      const cls = simpleTypeName(body.slice(0, dot));
      const method = simplifyMethodParams(body.slice(dot + 1));
      body = cls ? cls + '.' + method : method;
    } else {
      body = simpleTypeName(body.slice(0, searchEnd)) + body.slice(searchEnd);
    }
  }
  return worker ? body + worker : body;
}

/** True for Java member / type labels we may shorten — not versions, jar names, or free prose. */
export function looksLikeJavaishLabel(body) {
  if (!body) return false;
  // Spaces only allowed inside a trailing param list: Foo.bar(A, B).
  const open = body.indexOf('(');
  const head = open >= 0 ? body.slice(0, open) : body;
  if (head.indexOf(' ') >= 0) return false;
  if (open >= 0) {
    const close = body.lastIndexOf(')');
    if (close < open) return false;
    if (close + 1 < body.length && body.slice(close + 1).indexOf(' ') >= 0) return false;
  }
  const c0 = body[0];
  if (!/[A-Za-z_$]/.test(c0)) return false;
  if (open >= 0) {
    return body.indexOf('.') >= 0 || /[A-Z]/.test(c0);
  }
  if (body.indexOf('.') < 0) return /[A-Z]/.test(c0);
  return /^(?:[a-z][\w$]*\.)*[A-Z][\w$]*(?:\.[A-Za-z_][\w$]*)?$/.test(body);
}

/** {@code SimpleClass.method()} / {@code SimpleClass.method(Path)} — package stripped, params simplified. */
export function shortTestLabel(d) {
  let cls = simpleTypeName(d.className || '');
  let method = (d.method || d.test || '').trim();
  const gt = method.lastIndexOf(' > ');
  if (gt >= 0) method = method.slice(gt + 3).trim();
  method = simplifyMethodParams(method);
  // method may still look like Class.method / pkg.Class.method — peel the class segment.
  const paren = method.indexOf('(');
  let dot = method.lastIndexOf('.');
  if (paren >= 0 && dot > paren) dot = method.lastIndexOf('.', paren);
  if (dot > 0 && dot < method.length - 1) {
    const after = method.slice(dot + 1, paren >= 0 ? paren : method.length);
    if (after && (/^[a-z_]/.test(after) || after.startsWith('<'))) {
      if (!cls) cls = simpleTypeName(method.slice(0, dot));
      method = method.slice(dot + 1);
    }
  }
  method = simplifyMethodParams(method);
  if (method && method.indexOf('(') < 0 && method !== '(test run)') method += '()';
  if (!cls && !method) return shortDisplayLabel(d.test || '') || '?';
  let label = !cls ? shortDisplayLabel(method) : !method ? cls : shortDisplayLabel(cls + '.' + method);
  const worker = typeof d.worker === 'number' ? d.worker : 0;
  if (worker > 0 && !label.includes('  [w')) label = label + '  [w' + worker + ']';
  return label;
}

/** Keep {@code (…)} but strip package prefixes inside params. */
export function simplifyMethodParams(method) {
  if (!method) return '';
  const open = method.indexOf('(');
  const close = method.lastIndexOf(')');
  if (open < 0 || close <= open) return String(method).trim();
  const name = method.slice(0, open).trim();
  const inside = method.slice(open + 1, close).trim();
  const suffix = method.slice(close + 1);
  if (!inside) return name + '()' + suffix;
  const parts = inside.split(',').map((raw) => {
    let p = raw.trim();
    let suffix = '';
    while (p.endsWith('...') || p.endsWith('[]')) {
      if (p.endsWith('...')) {
        suffix = '...' + suffix;
        p = p.slice(0, -3).trim();
      } else {
        suffix = '[]' + suffix;
        p = p.slice(0, -2).trim();
      }
    }
    const d = p.lastIndexOf('.');
    if (d >= 0) p = p.slice(d + 1);
    return p + suffix;
  });
  return name + '(' + parts.join(', ') + ')' + suffix;
}
