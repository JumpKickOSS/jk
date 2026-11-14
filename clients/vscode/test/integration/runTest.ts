// SPDX-License-Identifier: Apache-2.0
import * as fs from "node:fs";
import * as path from "node:path";
import { downloadAndUnzipVSCode, runTests } from "@vscode/test-electron";

/**
 * Launches VS Code with this extension under development against a copy of the fixture workspace
 * and a fake `jk` (user settings name it as `jk.path`). The fake prints the connect line the suite
 * writes once its stub engine listens, so no real engine and no network is involved.
 *
 * VSCODE_EXECUTABLE names an installed VS Code to run; without it the current stable build is
 * downloaded (CI). On Linux without a display, run under `xvfb-run -a`.
 *
 * JK_TEST_REAL_JK names a real jk instead: the suite then runs only its real-engine cases, against
 * the engine that jk starts (give it a private JK_HOME in the environment).
 */
async function main(): Promise<void> {
  const root = path.resolve(__dirname, "..", "..", "..");
  const scratch = path.join(root, ".vscode-test", "run");
  fs.rmSync(scratch, { recursive: true, force: true });
  const workspace = path.join(scratch, "workspace");
  fs.cpSync(path.join(root, "test", "fixtures", "workspace"), workspace, { recursive: true });
  const connectFile = path.join(scratch, "connect.json");
  const realJk = process.env["JK_TEST_REAL_JK"] ?? "";
  const bin = realJk || writeFakeJk(path.join(scratch, "bin"), connectFile);
  const userData = path.join(scratch, "user-data");
  fs.mkdirSync(path.join(userData, "User"), { recursive: true });
  fs.writeFileSync(
    path.join(userData, "User", "settings.json"),
    JSON.stringify({ "jk.path": bin, "security.workspace.trust.enabled": false }, null, 2),
  );
  const vscodeExecutablePath = process.env["VSCODE_EXECUTABLE"] || (await downloadAndUnzipVSCode("stable"));
  await runTests({
    vscodeExecutablePath,
    extensionDevelopmentPath: root,
    extensionTestsPath: path.join(__dirname, "suite", "index"),
    launchArgs: [
      workspace,
      "--user-data-dir",
      userData,
      "--extensions-dir",
      path.join(scratch, "extensions"),
      "--disable-extensions",
      "--disable-workspace-trust",
      "--skip-welcome",
      "--skip-release-notes",
    ],
    extensionTestsEnv: {
      JK_TEST_CONNECT_FILE: connectFile,
      JK_TEST_WORKSPACE: workspace,
      JK_TEST_REAL: realJk ? "1" : "",
    },
  });
}

/** A `jk` that waits for the suite's connect line and prints it, as `jk web -O jsonl` would. */
function writeFakeJk(dir: string, connectFile: string): string {
  fs.mkdirSync(dir, { recursive: true });
  const script = path.join(dir, "fake-jk.js");
  fs.writeFileSync(
    script,
    `const fs = require("fs");
const file = ${JSON.stringify(connectFile)};
const args = process.argv.slice(2).join(" ");
if (args !== "web -O jsonl") { console.error("fake jk: unexpected arguments: " + args); process.exit(2); }
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
