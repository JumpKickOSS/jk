// SPDX-License-Identifier: Apache-2.0
// Failure diagnostics → the report models the fail-report component renders: the AssertJ
// expected/actual split, the javac/kotlinc/groovyc block parse, and the source snippet window.
// CLI parity, so a test failure reads identically in the terminal and in the browser.

import { shortTestLabel, simpleTypeName } from './label.js';

/**
 * Parse AssertJ-style messages into { desc, expected, actual } or null.
 * Matches CLI {@code tryPaintAssertJ}.
 */
export function parseAssertJMessage(message) {
  if (!message) return null;
  let rest = String(message).trim();
  let desc = null;
  if (rest.startsWith('[')) {
    const close = rest.indexOf(']');
    if (close > 0) {
      desc = rest.slice(1, close).trim();
      rest = rest.slice(close + 1).trim();
    }
  }
  let m = rest.match(/^expected:\s*([^\n]+?)\s*\n\s*but was:\s*([^\n]+?)\s*$/i);
  if (!m) {
    m = rest.match(/^expected:\s*(.+?)\s+but was:\s*(.+?)\s*$/i);
  }
  if (!m) return null;
  return { desc, expected: stripValueQuotes(m[1].trim()), actual: stripValueQuotes(m[2].trim()) };
}

function stripValueQuotes(v) {
  if (!v) return '';
  const s = v.trim();
  if (s.length >= 2) {
    const a = s[0];
    const b = s[s.length - 1];
    if ((a === '"' && b === '"') || (a === "'" && b === "'")) return s.slice(1, -1);
    if (a === '<' && b === '>') return s.slice(1, -1);
  }
  return s;
}

/**
 * Build a structured report model for a test-failure diagnostic (CLI flat report, no rail).
 * Non-test-failure diags return null — callers keep the legacy one-line render.
 *
 * @param {object} d normalized diagnostic
 * @param {{ count?: number, showHeader?: boolean }} [opts]
 *  {@code count} — total test failures in the module (header "N test failed").
 *  {@code showHeader} — false for subsequent failures in the same module so only the first
 *  report carries {@code ✘ Test failure in … › N tests failed} (CLI multi-failure parity).
 */
export function testFailureReport(d, opts) {
  if (!d || d.code !== 'test-failure') return null;
  const count = opts && opts.count > 0 ? opts.count : 1;
  const showHeader = !opts || opts.showHeader !== false;
  const assertj = parseAssertJMessage(d.message);
  const simpleEx = simpleTypeName(d.exceptionClass);
  const label = shortTestLabel(d);
  const snippet = Array.isArray(d.snippet) ? d.snippet : [];
  const start = d.snippetStart > 0 ? d.snippetStart : 1;
  const errorLine = d.line > 0 ? d.line : 0;
  let maxCode = 0;
  for (const line of snippet) maxCode = Math.max(maxCode, String(line).length);
  // Gutter sizes to the widest line number — a fixed 4ch overflows into the rail at
  // five digits (large generated test files), where the CLI's %4s widens naturally.
  const gutter = Math.max(4, String(start + Math.max(0, snippet.length - 1)).length);
  const rows = snippet.map((code, i) => {
    const num = start + i;
    const text = String(code);
    const pad = Math.max(0, maxCode - text.length);
    return {
      num,
      gutter: String(num).padStart(gutter, ' '),
      error: errorLine > 0 && num === errorLine,
      code: text,
      pad,
    };
  });
  return {
    module: d.module || '',
    count,
    showHeader,
    label,
    assertj,
    message: d.message || '',
    file: d.file || '',
    line: errorLine,
    exceptionClass: simpleEx,
    rows,
    // CLI paints `at …` frames when there is no snippet; hide them when source is present.
    frames: d.file && snippet.length ? [] : stackFrameLines(d.stack),
  };
}

/** {@code at …} / {@code ... N more} lines from a printStackTrace string. */
export function stackFrameLines(stack) {
  if (!stack) return [];
  const out = [];
  for (const line of String(stack).split('\n')) {
    const t = line.trimStart();
    if (t.startsWith('at ') || t.startsWith('...')) out.push(line);
  }
  return out;
}

/** True when the diagnostic should use the rich test-failure report. */
export function isTestFailureDiag(d) {
  return !!(d && d.code === 'test-failure');
}

/** javac / kotlinc / groovyc — same set as CLI {@code ConsoleSpec.isCompilerCode}. */
export function isCompilerDiag(d) {
  const c = d && d.code;
  return c === 'javac' || c === 'kotlinc' || c === 'groovyc';
}

/**
 * {@code path.ext:line[:col]:rest} — same shape as {@code CompilerLocus.HEADER}. The optional
 * space after the first colon is groovyc's shape ({@code /w/Foo.groovy: 5: …}).
 */
const COMPILER_HEADER =
  /^(?<file>.+?\.(?:java|kt|kts|groovy|gvy|gy)): ?(?<line>\d+)(?::(?<col>\d+))?:(?<rest>.*)$/;
/** groovyc's column trailer: {@code … @ line 5, column 1.} (no inline col in the header). */
const GROOVY_TRAILER = /@ line \d+, column (\d+)\.?\s*$/;
const COMPILER_CARET = /^\s*\^\s*$/;
const COMPILER_KV = /^\s*([^:]+):(.*)$/;

/**
 * Split a compiler block into units (one per header). Empty when the message has no locus header.
 *
 * @param {string} raw
 * @param {string} [severity]
 * @returns {Array<{file:string,line:number,col:number,kvs:Array<{key:string,value:string}>,extras:string[],snippet:string|null}>}
 */
export function parseCompilerBlock(raw, severity = 'error') {
  if (!raw) return [];
  const lines = String(raw).split('\n');
  const headers = [];
  for (let i = 0; i < lines.length; i++) {
    if (COMPILER_HEADER.test(lines[i])) headers.push(i);
  }
  if (!headers.length) return [];
  const units = [];
  for (let h = 0; h < headers.length; h++) {
    const start = headers[h];
    const end = h + 1 < headers.length ? headers[h + 1] : lines.length;
    units.push(parseCompilerUnit(lines, start, end, severity));
  }
  return units;
}

function parseCompilerUnit(lines, start, end, severity) {
  const m = COMPILER_HEADER.exec(lines[start]);
  if (!m) {
    return { file: '', line: 0, col: 0, kvs: [], extras: [], snippet: null };
  }
  const file = m.groups.file;
  const line = parsePositiveInt(m.groups.line);
  let col = parsePositiveInt(m.groups.col);
  const rest = (m.groups.rest || '').trim();
  if (col <= 0) {
    const tr = GROOVY_TRAILER.exec(rest);
    if (tr) col = parsePositiveInt(tr[1]);
  }
  const kvs = [];
  if (rest) kvs.push(splitCompilerKv(rest, severity));
  let snippet = null;
  const extras = [];
  for (let i = start + 1; i < end; i++) {
    const row = lines[i];
    if (COMPILER_CARET.test(row)) {
      const at = row.indexOf('^');
      if (at >= 0 && col <= 0) col = at + 1;
      continue;
    }
    if (i + 1 < end && COMPILER_CARET.test(lines[i + 1])) {
      snippet = row;
      continue;
    }
    const kv = COMPILER_KV.exec(row);
    if (kv && looksLikeCompilerTrailer(kv[1])) {
      kvs.push({ key: kv[1].trim(), value: (kv[2] || '').trim() });
      continue;
    }
    if (row != null && row.trim()) extras.push(row);
  }
  return { file, line, col, kvs, extras, snippet };
}

function splitCompilerKv(rest, severity) {
  const s = String(rest).trim();
  const colon = s.indexOf(':');
  if (colon <= 0) return { key: severity || 'error', value: s };
  return { key: s.slice(0, colon).trim(), value: s.slice(colon + 1).trim() };
}

function looksLikeCompilerTrailer(rawKey) {
  if (!rawKey) return false;
  const k = rawKey.trim();
  if (!k || k.length > 24) return false;
  return /^[A-Za-z][A-Za-z0-9_-]*$/.test(k);
}

function parsePositiveInt(raw) {
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/**
 * Editor-style window around {@code errorLine} (1-based): two lines of context each side, or a
 * single numbered row when only the compiler's one-line snippet is available.
 *
 * @param {string[]} fileLines
 * @param {number} errorLine
 */
export function snippetWindow(fileLines, errorLine) {
  const n = fileLines == null ? 0 : fileLines.length;
  if (n === 0) return [];
  const err = Math.max(1, errorLine || 1);
  const snippetOnly = n === 1 && err > 1;
  const lo = snippetOnly ? 1 : Math.max(1, err - 2);
  const hi = snippetOnly ? 1 : Math.min(n, err + 2);
  const slice = [];
  let maxCode = 0;
  for (let line = lo; line <= hi; line++) {
    const raw = fileLines[line - 1] == null ? '' : String(fileLines[line - 1]);
    slice.push(raw);
    maxCode = Math.max(maxCode, raw.length);
  }
  const gutter = Math.max(4, String(snippetOnly ? err : hi).length);
  return slice.map((text, i) => {
    const num = snippetOnly ? err : lo + i;
    return {
      num,
      gutter: String(num).padStart(gutter, ' '),
      error: snippetOnly || num === err,
      code: text,
      pad: Math.max(0, maxCode - text.length),
    };
  });
}

/**
 * Structured compile-failure report (CLI CompilerDiagnostic body, web test-failure chrome).
 * Returns one report per header in the message. Non-compiler diags return [].
 *
 * @param {object} d normalized diagnostic
 * @param {{ showHeader?: boolean, module?: string }} [opts]
 */
export function compilerFailureReports(d, opts) {
  if (!isCompilerDiag(d)) return [];
  const showHeader = !opts || opts.showHeader !== false;
  const module = (opts && opts.module) || d.module || '';
  const units = parseCompilerBlock(d.message || '', 'error');
  if (!units.length) {
    const rest = String(d.message || '').trim();
    return [
      makeCompilerReport({
        showHeader,
        module,
        file: d.file || '',
        line: d.line || 0,
        col: d.col || 0,
        kvs: rest ? [{ key: 'error', value: rest }] : [],
        extras: [],
        snippet: null,
      }),
    ];
  }
  return units.map((unit, i) =>
    makeCompilerReport({
      showHeader: showHeader && i === 0,
      module,
      // Diag-level file applies to the FIRST unit only (same rule as line/col): a multi-header
      // blob labeled every later unit with the first file — wrong locus, wrong deep link, and
      // loadSource fetched the wrong file for the context window. CLI parity:
      // CompilerDiagnostic.paintUnit uses each unit's own file.
      file: i === 0 ? d.file || unit.file || '' : unit.file || d.file || '',
      line: d.line > 0 && i === 0 ? d.line : unit.line,
      col: d.col > 0 && i === 0 ? d.col : unit.col,
      kvs: unit.kvs,
      extras: unit.extras,
      snippet: unit.snippet,
    }),
  );
}

function makeCompilerReport({ showHeader, module, file, line, col, kvs, extras, snippet }) {
  let rows = [];
  if (snippet != null && snippet !== '') {
    rows = snippetWindow([snippet], line > 0 ? line : 1);
  }
  return {
    kind: 'compile',
    showHeader,
    headerLabel: 'Compile failure',
    module,
    kvs: kvs || [],
    extras: extras || [],
    file: file || '',
    line: line || 0,
    col: col || 0,
    rows,
  };
}
