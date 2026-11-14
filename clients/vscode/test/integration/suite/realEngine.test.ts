// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import type { JumpKickApi } from "../../../src/extension";

const real = process.env["JK_TEST_REAL"] === "1" && (process.env["JK_TEST_JAVA"] ?? "") === "";
const workspace = process.env["JK_TEST_WORKSPACE"] ?? "";

async function until(what: string, cond: () => boolean, ms: number): Promise<void> {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 50));
  }
}

function problemsIn(file: string): string[] {
  return vscode.languages
    .getDiagnostics(vscode.Uri.file(file))
    .map((d) => `${d.range.start.line + 1}:${d.range.start.character + 1} ${String(d.source)}`);
}

/** Runs only with JK_TEST_REAL_JK: the extension against the engine a real jk starts. */
(real ? describe : describe.skip)("the extension against a real engine", () => {
  let api: JumpKickApi;
  const app = path.join(workspace, "src", "main", "java", "dev", "example", "App.java");
  const manifest = path.join(workspace, "jk.toml");
  const goodApp = fs.existsSync(app) ? fs.readFileSync(app, "utf8") : "";
  const goodManifest = fs.existsSync(manifest) ? fs.readFileSync(manifest, "utf8") : "";

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

  it("a compile error lands in Problems at its line and column, and the green rebuild clears it", async () => {
    fs.writeFileSync(app, goodApp.replace('System.out.println("fixture");', 'int x = "s";'));
    const red = await api.run("build");
    assert.equal(red?.success, false);
    assert.deepEqual(problemsIn(app), ["6:17 javac"]);
    fs.writeFileSync(app, goodApp);
    const green = await api.run("build");
    assert.equal(green?.success, true);
    assert.deepEqual(problemsIn(app), []);
  }).timeout(300_000);

  it("a jk.toml schema error lands at its key, and the fix clears it", async () => {
    fs.writeFileSync(manifest, goodManifest.replace("java = 25", 'java = "twenty-five"'));
    const red = await api.run("build");
    assert.equal(red?.success, false);
    assert.deepEqual(problemsIn(manifest), ["4:1 jk"]);
    fs.writeFileSync(manifest, goodManifest);
    await api.run("build");
    assert.deepEqual(problemsIn(manifest), []);
  }).timeout(300_000);

  it("a lock failure lands at the dependency's declaration", async () => {
    fs.writeFileSync(
      manifest,
      goodManifest + '\n[dependencies]\nnowhere = { group = "dev.example.absent", version = "1.0.0" }\n',
    );
    const red = await api.run("lock");
    assert.equal(red?.success, false);
    assert.deepEqual(problemsIn(manifest), ["7:1 jk"]);
    fs.writeFileSync(manifest, goodManifest);
    await api.run("lock");
    assert.deepEqual(problemsIn(manifest), []);
  }).timeout(300_000);
});
