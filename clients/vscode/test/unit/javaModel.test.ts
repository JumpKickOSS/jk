// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import { test } from "node:test";
import { isManifest, ModelSync, QuietWindow } from "../../src/javaModel";

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

test("a burst of manifest changes relocks once, after the window has been quiet", async () => {
  let fired = 0;
  const w = new QuietWindow(60, async () => {
    fired++;
  });
  for (let i = 0; i < 5; i++) {
    w.touch();
    await sleep(20);
  }
  assert.equal(fired, 0, "nothing runs while changes keep coming");
  await sleep(120);
  assert.equal(fired, 1);
  w.dispose();
});

test("a change during a sync runs one more sync after it, never two at once", async () => {
  let running = 0;
  let overlap = false;
  let fired = 0;
  let release: (() => void) | undefined;
  const w = new QuietWindow(10, async () => {
    running++;
    overlap ||= running > 1;
    fired++;
    if (fired === 1) await new Promise<void>((r) => (release = r));
    running--;
  });
  const first = w.now();
  w.touch();
  await sleep(40);
  w.touch();
  await sleep(40);
  release?.();
  await first;
  await sleep(40);
  assert.equal(overlap, false);
  assert.equal(fired, 2, "the changes made during the first sync are covered by exactly one more");
  w.dispose();
});

test("the model is applied only when the lock succeeded and the model changed", async () => {
  const calls: string[] = [];
  let lockOk = true;
  let model: unknown = { root: "/w", projects: [{ name: "app" }] };
  const sync = new ModelSync({
    relock: async () => {
      calls.push("lock");
      return lockOk;
    },
    fetchModel: async () => {
      calls.push("model");
      return model;
    },
    apply: async (json) => {
      calls.push(`apply ${json}`);
      return { projects: ["app"], removed: [] };
    },
  });

  assert.deepEqual(await sync.sync(), { kind: "applied", projects: ["app"] });
  assert.deepEqual(await sync.sync(), { kind: "unchanged" });
  lockOk = false;
  assert.deepEqual(await sync.sync(), { kind: "lock-failed" });
  lockOk = true;
  model = { root: "/w", projects: [{ name: "app" }, { name: "lib" }] };
  assert.equal((await sync.sync()).kind, "applied");
  sync.invalidate();
  assert.equal((await sync.sync()).kind, "applied", "a new engine applies the model again");
  assert.deepEqual(calls, [
    "lock",
    "model",
    'apply {"root":"/w","projects":[{"name":"app"}]}',
    "lock",
    "model",
    "lock",
    "lock",
    "model",
    'apply {"root":"/w","projects":[{"name":"app"},{"name":"lib"}]}',
    "lock",
    "model",
    'apply {"root":"/w","projects":[{"name":"app"},{"name":"lib"}]}',
  ]);
});

test("a failed apply is retried by the next sync and says why", async () => {
  let fail = true;
  const sync = new ModelSync({
    relock: async () => true,
    fetchModel: async () => ({ projects: [] }),
    apply: async () => {
      if (fail) throw new Error("no delegate command handler for jk.jdtls.applyModel");
      return { projects: [] };
    },
  });
  assert.deepEqual(await sync.sync(), { kind: "failed", reason: "no delegate command handler for jk.jdtls.applyModel" });
  fail = false;
  assert.equal((await sync.sync()).kind, "applied");
});

test("manifests and lockfiles count, anything under target does not", () => {
  assert.equal(isManifest("/w/jk.toml"), true);
  assert.equal(isManifest("/w/app/jk-lock.toml"), true);
  assert.equal(isManifest("C:\\w\\app\\jk.toml"), true);
  assert.equal(isManifest("/w/app/target/tmp/jk.toml"), false);
  assert.equal(isManifest("/w/app/jk.toml.bak"), false);
});
