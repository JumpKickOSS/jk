// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import * as path from "node:path";
import { test } from "node:test";
import { isWithin, RunTracker, type Run } from "../../src/runs";

const root = path.resolve("/work/app");
const member = path.join(root, "core");
const elsewhere = path.resolve("/work/other");

test("a job in the window's folder is followed from queue to finish", () => {
  const tracker = new RunTracker([root]);
  const seen: Run[] = [];
  tracker.onDidChange((r) => seen.push(r));
  tracker.accept({ type: "request-queued", data: { jid: 4, kind: "test", dir: member } });
  tracker.accept({ type: "request-start", data: { jid: 4, kind: "test", dir: member, trigger: "cli", startedAt: 10 } });
  tracker.accept({ type: "label", data: { jid: 4, progress: 40 } });
  tracker.accept({ type: "label", data: { jid: 4, progress: 40 } });
  tracker.accept({ type: "request-finish", data: { jid: 4, success: false, cancelled: false, progress: 61 } });
  assert.deepEqual(
    seen.map((r) => [r.state, r.progress]),
    [
      ["queued", undefined],
      ["running", undefined],
      ["running", 40],
      ["finished", 61],
    ],
  );
  assert.equal(seen[3]?.success, false);
  assert.equal(seen[3]?.trigger, "cli");
  assert.deepEqual(tracker.active(), []);
});

test("jobs in other checkouts are not this window's", () => {
  const tracker = new RunTracker([root]);
  tracker.accept({ type: "request-start", data: { jid: 1, kind: "build", dir: elsewhere } });
  tracker.accept({ type: "request-finish", data: { jid: 1, success: true } });
  assert.equal(tracker.get(1), undefined);
});

test("a mid-build connect's snapshot starts the run with its progress", () => {
  const tracker = new RunTracker([root]);
  tracker.accept({ type: "run-snapshot", data: { jid: 9, kind: "build", dir: root, progress: 72.5, running: true } });
  assert.equal(tracker.active()[0]?.progress, 72.5);
});

test("isWithin: the root itself and paths below it, never a sibling with a shared prefix", () => {
  assert.ok(isWithin(root, root));
  assert.ok(isWithin(root, member));
  assert.ok(!isWithin(root, root + "-other"));
  assert.ok(!isWithin(root, path.dirname(root)));
  assert.ok(!isWithin(root, ""));
});
