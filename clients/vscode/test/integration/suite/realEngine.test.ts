// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import * as vscode from "vscode";
import type { JumpKickApi } from "../../../src/extension";

const real = process.env["JK_TEST_REAL"] === "1";

async function until(what: string, cond: () => boolean, ms: number): Promise<void> {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 50));
  }
}

/** Runs only with JK_TEST_REAL_JK: the extension against the engine a real jk starts. */
(real ? describe : describe.skip)("the extension against a real engine", () => {
  let api: JumpKickApi;

  before(async function () {
    this.timeout(180_000);
    const ext = vscode.extensions.getExtension<JumpKickApi>("jumpkick.jumpkick");
    assert.ok(ext);
    api = await ext.activate();
    await until("the session to go live", () => api.session.state.kind === "live", 120_000);
  });

  it("builds the fixture through the engine's API and follows it to a green finish", async () => {
    const run = await api.run("build");
    assert.equal(run?.success, true, "the fixture builds");
  }).timeout(300_000);
});
