// SPDX-License-Identifier: Apache-2.0
import * as fs from "node:fs";
import * as path from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";
import { downloadAndUnzipVSCode, resolveCliArgsFromVSCodeExecutablePath, runTests } from "@vscode/test-electron";

/**
 * Launches VS Code with this extension under development against a copy of the fixture workspace
 * and a fake `jk` (user settings name it as `jk.path`). The fake prints the connect line the suite
 * writes once its stub engine listens, so no real engine and no network is involved.
 *
 * VSCODE_EXECUTABLE names an installed VS Code to run; without it the current stable build is
 * downloaded (CI). On Linux without a display, run under `xvfb-run -a`.
 *
 * JK_TEST_REAL_JK names a real jk instead: the suite then runs only its real-engine cases, against
 * the engine that jk starts (give it a private JK_HOME in the environment). With JK_TEST_JAVA=1 as
 * well it runs the Java language server cases instead, on a two-module workspace that depends on
 * Maven Central, with the full (Standard) server; JK_TEST_JAVA=single runs them on a single-module
 * project instead.
 *
 * Every run installs the redhat.java build jdtls/deps.json pins into the scratch profile, since the
 * extension depends on it; the stub runs keep it in LightWeight mode, where the model sync waits.
 * Its language server runs on JAVA_HOME's JDK.
 */
async function main(): Promise<void> {
  const root = path.resolve(__dirname, "..", "..", "..");
  const scratch = path.join(root, ".vscode-test", "run");
  fs.rmSync(scratch, { recursive: true, force: true });
  const workspace = path.join(scratch, "workspace");
  const realJk = process.env["JK_TEST_REAL_JK"] ?? "";
  const javaMode = realJk === "" ? "" : (process.env["JK_TEST_JAVA"] ?? "");
  const java = javaMode === "1" || javaMode === "single";
  const fixture = javaMode === "single" ? "java-single" : java ? "java-workspace" : "workspace";
  fs.cpSync(path.join(root, "test", "fixtures", fixture), workspace, { recursive: true });
  const connectFile = path.join(scratch, "connect.json");
  const bin = realJk || writeFakeJk(path.join(scratch, "bin"), connectFile);
  const userData = path.join(scratch, "user-data");
  fs.mkdirSync(path.join(userData, "User"), { recursive: true });
  const settings: Record<string, unknown> = {
    "jk.path": bin,
    "security.workspace.trust.enabled": false,
    "extensions.autoUpdate": false,
    "extensions.autoCheckUpdates": false,
    "redhat.telemetry.enabled": false,
    "java.server.launchMode": java ? "Standard" : "LightWeight",
  };
  if (process.env["JAVA_HOME"]) settings["java.jdt.ls.java.home"] = process.env["JAVA_HOME"];
  fs.writeFileSync(path.join(userData, "User", "settings.json"), JSON.stringify(settings, null, 2));
  const vscodeExecutablePath = process.env["VSCODE_EXECUTABLE"] || (await downloadAndUnzipVSCode("stable"));
  const extensions = path.join(scratch, "extensions");
  await installRedhatJava(root, vscodeExecutablePath, extensions, userData);
  await runTests({
    vscodeExecutablePath,
    extensionDevelopmentPath: root,
    extensionTestsPath: path.join(__dirname, "suite", "index"),
    launchArgs: [
      workspace,
      "--user-data-dir",
      userData,
      "--extensions-dir",
      extensions,
      "--disable-workspace-trust",
      "--skip-welcome",
      "--skip-release-notes",
    ],
    extensionTestsEnv: {
      JK_TEST_CONNECT_FILE: connectFile,
      JK_TEST_WORKSPACE: workspace,
      JK_TEST_REAL: realJk ? "1" : "",
      JK_TEST_JAVA: java ? javaMode : "",
    },
  });
}

/** The pinned redhat.java VSIX (downloaded and checked by jdtls/build.mjs), installed with VS Code's CLI. */
async function installRedhatJava(root: string, vscode: string, extensions: string, userData: string): Promise<void> {
  const build = (await import(pathToFileURL(path.join(root, "jdtls", "build.mjs")).href)) as {
    redhatJava(): Promise<{ vsix: string }>;
  };
  const { vsix } = await build.redhatJava();
  const [cli, ...args] = resolveCliArgsFromVSCodeExecutablePath(vscode, { reuseMachineInstall: true });
  const r = spawnSync(
    cli ?? "code",
    [...args, "--extensions-dir", extensions, "--user-data-dir", userData, "--install-extension", vsix, "--force"],
    { stdio: "inherit", shell: process.platform === "win32" },
  );
  if (r.status !== 0) throw new Error(`installing redhat.java exited ${r.status}`);
}

/** A `jk` that waits for the suite's connect line and prints it, as `jk web -O jsonl --with-token` would. */
function writeFakeJk(dir: string, connectFile: string): string {
  fs.mkdirSync(dir, { recursive: true });
  const script = path.join(dir, "fake-jk.js");
  fs.writeFileSync(
    script,
    `const fs = require("fs");
const file = ${JSON.stringify(connectFile)};
const args = process.argv.slice(2).join(" ");
if (args !== "web -O jsonl --with-token") { console.error("fake jk: unexpected arguments: " + args); process.exit(2); }
const deadline = Date.now() + 30000;
(function poll() {
  if (fs.existsSync(file)) { process.stdout.write(fs.readFileSync(file, "utf8") + "\\n"); return; }
  if (Date.now() > deadline) { console.log(JSON.stringify({ error: "no stub engine" })); process.exit(1); }
  setTimeout(poll, 50);
})();
`,
  );
  if (process.platform === "win32") {
    const cmd = path.join(dir, "jk.cmd");
    fs.writeFileSync(cmd, `@"${process.execPath}" "${script}" %*\r\n`);
    return cmd;
  }
  const sh = path.join(dir, "jk");
  fs.writeFileSync(sh, `#!/bin/sh\nexec "${process.execPath}" "${script}" "$@"\n`, { mode: 0o755 });
  return sh;
}

main().catch((e: unknown) => {
  console.error(e);
  process.exit(1);
});
