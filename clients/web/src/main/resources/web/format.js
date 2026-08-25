// SPDX-License-Identifier: Apache-2.0
// One formatter per concept, for every surface of the dashboard. A build that took an hour has to
// read the same on its card, in its sparkline tooltip and in its live counter, so there is exactly
// one duration renderer, one relative-time renderer and one byte-size renderer here and nowhere
// else. Pure functions of their arguments — `now` is passed in so the 1s clock stays reactive.

/**
 * mm:ss clock, mirroring the CLI's {@code JkManagerColor.fmtClockSeconds}: {@code 42s},
 * {@code 1m 02s}, {@code 1h 05m 09s}. Whole seconds only, so a dual clock's two faces share one
 * boundary.
 */
export function fmtClockSeconds(totalSec) {
  const s = Math.max(0, totalSec | 0);
  if (s < 60) return s + 's';
  const pad = (n) => String(n).padStart(2, '0');
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ' + pad(s % 60) + 's';
  return Math.floor(m / 60) + 'h ' + pad(m % 60) + 'm ' + pad(s % 60) + 's';
}

/**
 * How long something took: {@code 820 ms}, {@code 5.5 s}, then the clock form from ten seconds up
 * ({@code 12s}, {@code 1m 05s}, {@code 1h 00m 05s}). Tenths only matter while a step is still
 * sub-ten-second; past that the clock form is what the live counter beside it already shows.
 * {@code compact} drops the space before the unit, for the dense step chain.
 */
export function fmtDuration(millis, { compact = false } = {}) {
  if (millis == null || !Number.isFinite(Number(millis))) return '';
  const ms = Math.max(0, Number(millis));
  const gap = compact ? '' : ' ';
  if (ms < 1000) return Math.round(ms) + gap + 'ms';
  if (ms < 10_000) return (ms / 1000).toFixed(1) + gap + 's';
  return fmtClockSeconds(Math.floor(ms / 1000));
}

/** {@code just now} / {@code 5m ago} / {@code 3h ago} / {@code 2d ago}; empty for no stamp. */
export function ago(millis, now) {
  if (!millis) return '';
  const s = Math.max(0, Math.floor((now - millis) / 1000));
  if (s < 60) return 'just now';
  if (s < 3600) return Math.floor(s / 60) + 'm ago';
  if (s < 86_400) return Math.floor(s / 3600) + 'h ago';
  return Math.floor(s / 86_400) + 'd ago';
}

/**
 * The same interval spelled out to the second — {@code 1d 2h 3m 4s ago} — from the largest
 * non-zero unit down. {@code never} for no stamp, which is what the "Last built" line says.
 */
export function agoBreakdown(millis, now) {
  if (!millis) return 'never';
  let s = Math.max(0, Math.floor((now - millis) / 1000));
  const d = Math.floor(s / 86_400);
  s -= d * 86_400;
  const h = Math.floor(s / 3600);
  s -= h * 3600;
  const m = Math.floor(s / 60);
  s -= m * 60;
  const parts = [];
  if (d) parts.push(d + 'd');
  if (h || parts.length) parts.push(h + 'h');
  if (m || parts.length) parts.push(m + 'm');
  parts.push(s + 's');
  return parts.join(' ') + ' ago';
}

/** Wall clock for relative-time hovers: {@code yyyy-MM-dd hh:mm:ss} in the local zone. */
export function fmtDateTime(millis) {
  if (millis == null || !Number.isFinite(Number(millis)) || Number(millis) <= 0) return '';
  const d = new Date(Number(millis));
  if (Number.isNaN(d.getTime())) return '';
  const p = (n) => String(n).padStart(2, '0');
  return (
    d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
    + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
  );
}

/** Binary size with one significant fraction below 100: {@code 0 B}, {@code 4.5 KiB}, {@code 812 MiB}. */
export function fmtBytes(bytes) {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return Math.round(bytes) + ' B';
  const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let v = bytes;
  let u = -1;
  do {
    v /= 1024;
    u++;
  } while (v >= 1024 && u < units.length - 1);
  const n = v >= 100 ? String(Math.round(v)) : v.toFixed(1).replace(/\.0$/, '');
  return n + ' ' + units[u];
}

/**
 * A byte count pinned to MiB, for panels whose whole budget is measured in them and would otherwise
 * jump units mid-column. {@code decimals} is 1 for the incremental tier, which lives in the
 * hundreds of MiB. An em dash for absent or negative: a thin cache frame can land before the full
 * snapshot, and {@code NaN MiB} is worse than nothing.
 */
export function mib(bytes, decimals = 0) {
  if (bytes == null || bytes < 0) return '—';
  const v = bytes / 1048576;
  return (decimals > 0 ? v.toFixed(decimals) : String(Math.round(v))) + ' MiB';
}

/** Whole-host memory reads naturally in GiB. Em dash for absent, as {@link mib}. */
export function gib(bytes) {
  return bytes == null || bytes < 0 ? '—' : (bytes / 1073741824).toFixed(1) + ' GiB';
}

/** Thousands-separated integer, or an em dash when the counter has not arrived. */
export function count(n) {
  return n == null ? '—' : n.toLocaleString();
}
