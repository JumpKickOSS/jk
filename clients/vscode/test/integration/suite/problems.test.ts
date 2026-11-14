// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import type { JumpKickApi } from "../../../src/extension";
import { scriptJob, StubEngine, type StubJob } from "../../support/stubEngine";

const connectFile = process.env["JK_TEST_CONNECT_FILE"] ?? "";
const workspace = process.env["JK_TEST_WORKSPACE"] ?? "";
const real = process.env["JK_TEST_REAL"] === "1";

async function until(what: string, cond: () => boolean, ms = 15_000): Promise<void> {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 25));
  }
}

/** What the Problems panel holds for `file`, as `line:col source message` rows. */
function problemsIn(file: string): string[] {
  return vscode.languages
    .getDiagnostics(vscode.Uri.file(file))
    .map((d) => `${d.range.start.line + 1}:${d.range.start.character + 1} ${String(d.source)} ${d.message}`);
}

(real ? describe.skip : describe)("the Problems panel, from a stub engine's run records", () => {
  const engine = new StubEngine();
  let api: JumpKickApi;
  const app = path.join(workspace, "src", "main", "java", "dev", "example", "App.java");
  const manifest = path.join(workspace, "jk.toml");

  /** The next job finishes with these diagnostics on its journal row. */
  function next(success: boolean, diagnostics: Record<string, unknown>[]): void {
    engine.onJob = (job: StubJob) => {
      engine.history = [{ requestId: job.jid, kind: job.kind, dir: job.dir, success, diagnostics }];
      scriptJob(engine, job, success);
    };
  }

  before(async () => {
    await engine.start();
    fs.writeFileSync(connectFile, engine.connectLine());
    const ext = vscode.extensions.getExtension<JumpKickApi>("jumpkick.jumpkick");
    assert.ok(ext);
    api = await ext.activate();
    // The stub suite that ran first may have left the session on another stub: point it here.
    await vscode.commands.executeCommand("jk.reconnect");
    await until("the session to go live on this engine", () => api.session.state.kind === "live" && engine.streamCount === 1).catch(
      (e: unknown) => {
        throw new Error(`${String(e)}; state ${JSON.stringify(api.session.state)}, streams ${engine.streamCount}, requests ${engine.requests.join(",")}`);
      },
    );
  });

  after(() => engine.stop());

  it("a compile error appears at its file and line, and the next green build clears it", async () => {
    next(false, [
      {
        severity: "error",
        dir: workspace,
        code: "javac",
        key: "compiler.err.cant.resolve",
        message: `${app}:6:9: error: cannot find symbol`,
        file: app,
        line: 6,
        col: 9,
      },
    ]);
    await api.run("build");
    assert.deepEqual(problemsIn(app), ["6:9 javac cannot find symbol"]);

    next(true, []);
    await api.run("build");
    assert.deepEqual(problemsIn(app), []);
  });

  it("a jk.toml error and a lock failure appear at their manifest lines; a green lock clears only those", async () => {
    next(false, [{ severity: "error", dir: "", code: "javac", message: "cannot find symbol", file: app, line: 3, col: 1 }]);
    await api.run("build");
    next(false, [
      { severity: "error", dir: "", code: "run-error", message: "java must be an integer", file: manifest, line: 4, col: 1 },
      { severity: "error", dir: "", code: "lock", message: "nothing has a:b", file: manifest, line: 7, col: 1 },
    ]);
    await api.run("lock");
    assert.deepEqual(problemsIn(manifest), ["4:1 jk java must be an integer", "7:1 lock nothing has a:b"]);
    assert.equal(problemsIn(app).length, 1, "a lock leaves the compile's problems alone");

    next(true, []);
    await api.run("lock");
    assert.deepEqual(problemsIn(manifest), []);
    assert.equal(problemsIn(app).length, 1);
  });
});
