// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as vscode from "vscode";
import type { JumpKickApi } from "../../../src/extension";
import { scriptJob, StubEngine } from "../../support/stubEngine";

const connectFile = process.env["JK_TEST_CONNECT_FILE"] ?? "";
const workspace = process.env["JK_TEST_WORKSPACE"] ?? "";

async function until(what: string, cond: () => boolean, ms = 15_000): Promise<void> {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 25));
  }
}

const real = process.env["JK_TEST_REAL"] === "1";

(real ? describe.skip : describe)("the extension against a stub engine", () => {
  const engine = new StubEngine();
  let api: JumpKickApi;

  before(async () => {
    await engine.start();
    fs.writeFileSync(connectFile, engine.connectLine());
    const ext = vscode.extensions.getExtension<JumpKickApi>("jumpkick.jumpkick");
    assert.ok(ext, "the extension under development is installed");
    api = await ext.activate();
    await until("the session to go live", () => api.session.state.kind === "live");
  });

  after(() => engine.stop());

  it("activates on a jk.toml folder and attaches to the engine's event stream", () => {
    assert.equal(engine.streamCount, 1);
    assert.ok(engine.requests.includes("GET /api/identity"));
    assert.ok(engine.requests.includes("GET /api/status"));
  });

  it("JumpKick: Build starts a build job in the jk.toml folder and follows it to its finish", async () => {
    engine.onJob = (job) => scriptJob(engine, job, true);
    const run = await api.run("build");
    assert.equal(run?.success, true);
    const job = engine.jobs.at(-1);
    assert.equal(job?.kind, "build");
    assert.equal(fs.realpathSync(job?.dir ?? ""), fs.realpathSync(workspace));
  });

  it("the Test command runs the test job and reports a failure", async () => {
    engine.onJob = (job) => scriptJob(engine, job, false);
    await vscode.commands.executeCommand("jk.test");
    assert.equal(engine.jobs.at(-1)?.kind, "test");
  });

  it("a job started from a terminal in this folder is followed too", async () => {
    const seen: string[] = [];
    const sub = api.runs.onDidChange((r) => seen.push(`${r.jid}:${r.state}`));
    engine.emit("request-start", { jid: 900, kind: "test", dir: workspace, trigger: "cli" });
    engine.emit("request-finish", { jid: 900, success: true });
    engine.emit("request-start", { jid: 901, kind: "test", dir: "/somewhere/else", trigger: "cli" });
    await until("the terminal job to finish", () => seen.includes("900:finished"));
    sub.dispose();
    assert.ok(!seen.some((s) => s.startsWith("901:")));
  });

  it("goes offline when the engine stops, and Reconnect finds the next one", async () => {
    await engine.stop();
    await until("offline", () => api.session.state.kind === "offline", 30_000);
    await engine.start();
    fs.writeFileSync(connectFile, engine.connectLine());
    await vscode.commands.executeCommand("jk.reconnect");
    await until("live again", () => api.session.state.kind === "live");
    assert.equal(engine.streamCount, 1);
  }).timeout(60_000);
});
