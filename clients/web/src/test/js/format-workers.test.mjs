// SPDX-License-Identifier: Apache-2.0
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { workerBudgetLine } from '../../main/resources/web/format.js';

const GIB = 1024 * 1024 * 1024;

test('the workers row names the budget source, the lease, and overbooking', () => {
  assert.equal(workerBudgetLine(null), '');
  assert.equal(workerBudgetLine({ workerBudgetBytes: -1 }), '');
  assert.equal(
    workerBudgetLine({
      workerBudgetBytes: 14 * GIB,
      workerLeasedBytes: 3 * GIB + GIB / 2,
      workerOverbookedBytes: 0,
      workerQueued: 2,
      workerBudgetSource: 'host',
      workerRunningJvms: 4,
      workerCpuCap: 16,
      overbookingOff: false,
    }),
    '14.0 GiB budget (host), 3.5 GiB leased, 0 MiB overbooked, 2 queued, 4/16 JVMs',
  );
  assert.equal(
    workerBudgetLine({
      workerBudgetBytes: 512 * 1024 * 1024,
      workerLeasedBytes: 64 * 1024 * 1024,
      workerOverbookedBytes: 0,
      workerQueued: 0,
      workerBudgetSource: 'cgroup',
      workerRunningJvms: 0,
      workerCpuCap: 8,
    }),
    '512 MiB budget (cgroup), 64 MiB leased, 0 MiB overbooked, 0 queued, 0/8 JVMs',
  );
  assert.equal(
    workerBudgetLine({
      workerBudgetBytes: (3 * GIB) / 2,
      workerLeasedBytes: 672 * 1024 * 1024,
      workerOverbookedBytes: 0,
      workerQueued: 3,
      workerBudgetSource: 'override',
      workerRunningJvms: 2,
      workerCpuCap: 8,
      overbookingOff: true,
    }),
    '1.5 GiB budget (override JK_WORKER_BUDGET_MB), 672 MiB leased, 0 MiB overbooked, 3 queued, 2/8 JVMs, overbooking off',
  );
  assert.equal(
    workerBudgetLine({
      workerBudgetBytes: 13 * GIB,
      workerLeasedBytes: GIB,
      workerOverbookedBytes: 0,
      workerQueued: 1,
      workerBudgetSource: 'host',
      workerRunningJvms: 0,
      workerCpuCap: 8,
      workerWaiting: 'job #7 needs 13.0 GiB (12.0 GiB free); held by build-script host 1.0 GiB (asked to exit)',
    }),
    '13.0 GiB budget (host), 1.0 GiB leased, 0 MiB overbooked, 1 queued, 0/8 JVMs; '
      + 'waiting: job #7 needs 13.0 GiB (12.0 GiB free); held by build-script host 1.0 GiB (asked to exit)',
  );
  assert.equal(
    workerBudgetLine({
      workerBudgetBytes: 13 * GIB,
      workerLeasedBytes: 12 * GIB,
      workerOverbookedBytes: 0,
      workerQueued: 0,
      workerBudgetSource: 'host',
      workerRunningJvms: 1,
      workerCpuCap: 8,
      workerOverLease: 'test JVM pid 4242 (job #7) using 12.0 GiB, leased 700 MiB',
    }),
    '13.0 GiB budget (host), 12.0 GiB leased, 0 MiB overbooked, 0 queued, 1/8 JVMs; '
      + 'over lease: test JVM pid 4242 (job #7) using 12.0 GiB, leased 700 MiB',
  );
});
