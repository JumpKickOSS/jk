// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import type { JumpKickApi } from "../../../src/extension";

const single = process.env["JK_TEST_JAVA"] === "single";
const workspace = process.env["JK_TEST_WORKSPACE"] ?? "";

async function until<T>(what: string, probe: () => Promise<T | undefined> | T | undefined, ms: number): Promise<T> {
  const end = Date.now() + ms;
  for (;;) {
    const v = await Promise.resolve(probe()).catch(() => undefined);
    if (v !== undefined) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 500));
  }
}

/** Runs only with JK_TEST_REAL_JK and JK_TEST_JAVA=single: a project that is not a workspace. */
(single ? describe : describe.skip)("the jk model of a single-module project in the Java language server", () => {
  const file = vscode.Uri.file(path.join(workspace, "src", "main", "java", "dev", "example", "Single.java"));

  before(async function () {
    this.timeout(600_000);
    const ext = vscode.extensions.getExtension<JumpKickApi>("jumpkick.jumpkick");
    assert.ok(ext);
    const api = await ext.activate();
    await until(
      "the first model sync",
      async () => {
        const o = await api.syncJavaModel();
        return o.kind === "applied" || o.kind === "unchanged" ? o : undefined;
      },
      480_000,
    );
  });

  it("is one Java project at the folder, compiling clean against its Central library", async () => {
    const uris = await vscode.commands.executeCommand<string[]>("java.execute.workspaceCommand", "java.project.getAll");
    assert.deepEqual((uris ?? []).map((u) => fs.realpathSync(vscode.Uri.parse(u).fsPath)), [fs.realpathSync(workspace)]);
    const doc = await vscode.workspace.openTextDocument(file);
    await vscode.window.showTextDocument(doc);
    await until(
      "Single.java to compile clean",
      () => (vscode.languages.getDiagnostics(file).some((d) => d.severity === vscode.DiagnosticSeverity.Error) ? undefined : true),
      180_000,
    );
    const at = doc.positionAt(doc.getText().indexOf("ImmutableList;") + 1);
    const target = await until(
      "ImmutableList's definition",
      async () => {
        const found = await vscode.commands.executeCommand<(vscode.Location | vscode.LocationLink)[]>(
          "vscode.executeDefinitionProvider",
          file,
          at,
        );
        const first = found?.[0];
        return first ? ("targetUri" in first ? first.targetUri : first.uri) : undefined;
      },
      120_000,
    );
    assert.match(decodeURIComponent(target.toString()), /^jdt:.*guava-33\.8\.0-jre\.jar/);
    for (const f of [".project", ".classpath", ".settings", ".vscode"]) {
      assert.equal(fs.existsSync(path.join(workspace, f)), false, f);
    }
  }).timeout(300_000);
});
