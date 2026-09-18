// SPDX-License-Identifier: Apache-2.0
// The Coverage block on a run: the record's `coverage[]` rows become the same figures the results
// file prints (percent, counts, signed points against the previous coverage run) and a link that
// opens the HTML report the engine serves.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const { coverageSummary, coverageChip, previousCoverage, reportHref, signed, pct, RunCoverage } =
  await spa('coverage.js');
const { historyCard, seedFromHistory } = await spa('fold.js');

const lib = { dir: '/ws/lib', label: 'g:lib', linesCovered: 120, linesMissed: 30, branchesCovered: 19, branchesMissed: 21, html: '/ws/lib/target/reports/coverage/index.html' };
const app = { dir: '/ws/app', label: 'g:app', linesCovered: 10, linesMissed: 0, branchesCovered: 0, branchesMissed: 0, html: '/ws/app/target/reports/coverage/index.html' };
const before = [{ ...lib, linesCovered: 100, linesMissed: 50, branchesCovered: 20, branchesMissed: 20 }];

const rec = (over) => ({
  id: 'r14',
  buildNumber: 14,
  kind: 'test',
  dir: '/ws',
  success: true,
  running: false,
  startedAt: 14_000,
  finishedAt: 20_000,
  millis: 6000,
  trigger: 'cli',
  coverage: [lib, app],
  ...over,
});

test('percentages and signed points are spelled as the results file spells them', () => {
  assert.equal(pct(120, 30), '80.0%');
  assert.equal(pct(0, 0), '100.0%');
  assert.equal(signed(1.26), '+1.3');
  assert.equal(signed(-0.04), '±0.0');
  assert.equal(signed(-0.5), '−0.5');
});

test('the summary carries one row per module, an all row for a workspace, and deltas by module dir', () => {
  const s = coverageSummary([lib, app], { buildNumber: 3, coverage: before });
  assert.equal(s.since, 'Δ vs run #3');
  assert.deepEqual(
    s.rows.map((r) => [r.label, r.lines, r.linesDelta, r.branches, r.branchesDelta]),
    [
      ['g:lib', '80.0% (120/150)', '+13.3', '47.5% (19/40)', '−2.5'],
      ['g:app', '100.0% (10/10)', 'new', '100.0% (0/0)', 'new'],
    ],
  );
  assert.deepEqual([s.all.label, s.all.lines, s.all.linesDelta, s.all.branches, s.all.branchesDelta], [
    'all',
    '81.3% (130/160)',
    '+14.6',
    '47.5% (19/40)',
    '−2.5',
  ]);
  assert.equal(s.headline, '81.3% lines · 47.5% branches · 2 modules');

  const single = coverageSummary([lib], null);
  assert.equal(single.since, null);
  assert.equal(single.all, null);
  assert.equal(single.rows[0].linesDelta, null);
  assert.equal(single.headline, '80.0% lines · 47.5% branches');
  assert.equal(coverageSummary([], null), null);
});

test('the previous coverage run is the nearest older record of the same project that measured anything', () => {
  const current = rec({});
  const records = [
    current,
    rec({ id: 'r13', buildNumber: 13, coverage: [] }),
    rec({ id: 'r12', buildNumber: 12, dir: '/other', coverage: before }),
    rec({ id: 'r11', buildNumber: 11, coverage: before }),
    rec({ id: 'r10', buildNumber: 10, coverage: [app] }),
  ];
  assert.equal(previousCoverage(records, current).id, 'r11');
  assert.equal(previousCoverage(records, rec({ id: 'r11', buildNumber: 11, coverage: before })).id, 'r10');
  assert.equal(previousCoverage(records, rec({ id: 'r10', buildNumber: 10 })), null);
  assert.equal(previousCoverage([], current), null);
});

test('the run card carries the rows, and a live card picks them up on reconcile', () => {
  assert.deepEqual(historyCard(rec({})).coverage, [lib, app]);
  assert.deepEqual(historyCard(rec({ coverage: undefined })).coverage, []);
  const live = { ...historyCard(rec({ coverage: undefined })), id: 77, historyId: null };
  seedFromHistory([live], [rec({})]);
  assert.deepEqual(live.coverage, [lib, app]);
});

test('the report link is the engine route for this run, tokenized in the path so its pages resolve', () => {
  assert.equal(reportHref('r14', 'tok-1'), '/report/tok-1/r14/');
  const chip = coverageChip([lib, app]);
  assert.equal(chip.text, '81.3% lines');
  assert.equal(chip.tip, '81.3% lines · 47.5% branches · 2 modules');
  assert.equal(coverageChip([]), null);
});

test('the component renders the headline, the table and the link', () => {
  const summary = RunCoverage.computed.summary.call({ coverage: [lib, app], previous: { buildNumber: 3, coverage: before } });
  assert.equal(summary.rows.length, 2);
  assert.equal(RunCoverage.template.includes('run-coverage-row'), true);
  assert.equal(RunCoverage.template.includes('href'), true);
});
