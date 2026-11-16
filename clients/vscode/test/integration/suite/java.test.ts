// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import type { JumpKickApi } from "../../../src/extension";
import type { SyncOutcome } from "../../../src/javaModel";
import { canonical } from "../../support/paths";

const java = process.env["JK_TEST_JAVA"] === "1";
const workspace = process.env["JK_TEST_WORKSPACE"] ?? "";

async function until<T>(what: string, probe: () => Promise<T | undefined> | T | undefined, ms: number): Promise<T> {
  const end = Date.now() + ms;
  let last: unknown;
  for (;;) {
    try {
      const v = await probe();
      if (v !== undefined) return v;
    } catch (e) {
      last = e;
    }
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}${last ? `: ${String(last)}` : ""}`);
    await new Promise((r) => setTimeout(r, 500));
  }
}

function javaErrors(uri: vscode.Uri): string[] {
  return vscode.languages
    .getDiagnostics(uri)
    .filter((d) => d.severity === vscode.DiagnosticSeverity.Error)
    .map((d) => `${d.range.start.line + 1}: ${d.message}`);
}

/** Where the definition of the identifier at the first occurrence of `word` in `doc` lives. */
async function definitionOf(doc: vscode.TextDocument, word: string): Promise<vscode.Uri | undefined> {
  const offset = doc.getText().indexOf(word);
  assert.ok(offset >= 0, `${word} in ${doc.uri.fsPath}`);
  const found = await vscode.commands.executeCommand<(vscode.Location | vscode.LocationLink)[]>(
    "vscode.executeDefinitionProvider",
    doc.uri,
    doc.positionAt(offset + 1),
  );
  const first = found?.[0];
  if (!first) return undefined;
  return "targetUri" in first ? first.targetUri : first.uri;
}

/**
 * Runs only with JK_TEST_REAL_JK and JK_TEST_JAVA: the extension, a real engine and redhat.java's
 * Standard server on a two-module workspace that depends on Maven Central.
 */
(java ? describe : describe.skip)("the jk model in the Java language server", () => {
  let api: JumpKickApi;
  const app = vscode.Uri.file(path.join(workspace, "app", "src", "main", "java", "dev", "example", "app", "App.java"));
  const manifest = path.join(workspace, "app", "jk.toml");
  const syncs: SyncOutcome[] = [];

  before(async function () {
    this.timeout(600_000);
    const ext = vscode.extensions.getExtension<JumpKickApi>("jumpkick.jumpkick");
    assert.ok(ext);
    api = await ext.activate();
    api.onJavaModelSync((o) => syncs.push(o));
    await until("the session to go live", () => (api.session.state.kind === "live" ? true : undefined), 120_000);
    // The window syncs by itself once the language server is up: nothing here asks it to.
    const first = await until("the window's own model sync", () => syncs[0], 480_000);
    assert.equal(first.kind, "applied", JSON.stringify(first));
  });

  it("is one Java project per module", async () => {
    const projects = await until(
      "the language server's projects",
      async () => {
        const uris = await vscode.commands.executeCommand<string[]>("java.execute.workspaceCommand", "java.project.getAll");
        const dirs = (uris ?? []).map((u) => canonical(vscode.Uri.parse(u).fsPath)).sort();
        return dirs.length >= 2 ? dirs : undefined;
      },
      120_000,
    );
    assert.deepEqual(projects, [path.join(workspace, "app"), path.join(workspace, "lib")].map((d) => canonical(d)).sort());
  }).timeout(180_000);

  it("resolves a sibling module's class and a Central library's, with its sources attached", async () => {
    const doc = await vscode.workspace.openTextDocument(app);
    await vscode.window.showTextDocument(doc);
    await until("App.java to compile clean", () => (javaErrors(app).length === 0 ? true : undefined), 180_000);

    const greeter = await until("Greeter's definition", () => definitionOf(doc, "Greeter;"), 120_000);
    assert.equal(canonical(greeter.fsPath), canonical(path.join(workspace, "lib", "src", "main", "java", "dev", "example", "lib", "Greeter.java")));

    const list = await until("ImmutableList's definition", () => definitionOf(doc, "ImmutableList;"), 120_000);
    assert.equal(list.scheme, "jdt", String(list));
    assert.match(decodeURIComponent(list.toString()), /guava-33\.8\.0-jre\.jar/);
    const source = await vscode.workspace.openTextDocument(list);
    // The sources jar's licence header, which a decompiled class would not have.
    assert.match(source.getText(), /The Guava Authors/);
  }).timeout(600_000);

  it("writes no project files into the user's tree", () => {
    for (const dir of [workspace, path.join(workspace, "app"), path.join(workspace, "lib")]) {
      for (const f of [".project", ".classpath", ".settings", ".factorypath", ".vscode"]) {
        assert.equal(fs.existsSync(path.join(dir, f)), false, path.join(dir, f));
      }
    }
  });

  it("picks up a dependency added to jk.toml without a window reload", async () => {
    const doc = await vscode.workspace.openTextDocument(app);
    const edit = new vscode.WorkspaceEdit();
    edit.insert(app, new vscode.Position(2, 0), "import org.apache.commons.lang3.StringUtils;\n");
    const end = doc.getText().lastIndexOf("}");
    edit.insert(app, doc.positionAt(end), "    static boolean blank(String s) { return StringUtils.isBlank(s); }\n");
    assert.ok(await vscode.workspace.applyEdit(edit));
    await doc.save();
    await until("the missing library to be an error", () => (javaErrors(app).length > 0 ? true : undefined), 180_000);

    const before = syncs.length;
    fs.writeFileSync(
      manifest,
      fs.readFileSync(manifest, "utf8") + 'commons-lang3 = { group = "org.apache.commons", version = "3.22.0" }\n',
    );
    const applied = await until(
      "the watcher's sync",
      () => syncs.slice(before).find((s) => s.kind === "applied" || s.kind === "failed" || s.kind === "lock-failed"),
      180_000,
    );
    assert.equal(applied.kind, "applied", JSON.stringify(applied));
    await until("App.java to compile clean again", () => (javaErrors(app).length === 0 ? true : undefined), 180_000);
  }).timeout(600_000);
});
