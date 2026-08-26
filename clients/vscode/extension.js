// SPDX-License-Identifier: Apache-2.0
"use strict";

const vscode = require("vscode");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");

/** @type {vscode.OutputChannel} */
let output;
/** @type {vscode.StatusBarItem} */
let status;
/** @type {vscode.Disposable[]} */
const disposables = [];

/**
 * @param {vscode.ExtensionContext} context
 */
function activate(context) {
  output = vscode.window.createOutputChannel("JumpKick");
  status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  status.command = "jk.showOutput";
  status.text = "$(package) jk";
  status.tooltip = "JumpKick — click for output";
  status.show();

  disposables.push(output, status);

  disposables.push(
    vscode.commands.registerCommand("jk.showOutput", () => output.show(true)),
    vscode.commands.registerCommand("jk.bspInstall", () => runJk(["bsp", "install"])),
    vscode.commands.registerCommand("jk.sync", () => runJk(["sync"])),
    vscode.commands.registerCommand("jk.build", () => runJk(["build"])),
    vscode.commands.registerCommand("jk.test", () => runJk(["test"])),
    vscode.commands.registerCommand("jk.lock", () => runJk(["lock"])),
    vscode.tasks.registerTaskProvider("jk", {
      provideTasks: () => defaultTasks(),
      resolveTask: (task) => task,
    })
  );

  maybeOfferBspInstall();
  context.subscriptions.push(...disposables);
}

function deactivate() {
  for (const d of disposables) {
    try {
      d.dispose();
    } catch (_) {
      /* ignore */
    }
  }
}

/** @returns {vscode.Task[]} */
function defaultTasks() {
  const folder = workspaceFolder();
  if (!folder) return [];
  const verbs = ["lock", "sync", "build", "test", "assemble"];
  return verbs.map((verb) => {
    const def = { type: "jk", task: verb };
    const exec = new vscode.ShellExecution(jkBin(), [verb], { cwd: folder.uri.fsPath });
    const task = new vscode.Task(def, folder, `jk ${verb}`, "jk", exec);
    task.group = verb === "build" || verb === "assemble" ? vscode.TaskGroup.Build : undefined;
    task.presentationOptions = { reveal: vscode.TaskRevealKind.Always, panel: vscode.TaskPanelKind.Shared };
    return task;
  });
}

/** @returns {vscode.WorkspaceFolder | undefined} */
function workspaceFolder() {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) return undefined;
  // Prefer a folder that has jk.toml.
  for (const f of folders) {
    if (fs.existsSync(path.join(f.uri.fsPath, "jk.toml"))) return f;
  }
  return folders[0];
}

function jkBin() {
  return vscode.workspace.getConfiguration("jk").get("path", "jk") || "jk";
}

/**
 * Run a jk CLI command in the workspace root. Wire-only: never loads engine jars.
 * @param {string[]} args
 */
function runJk(args) {
  const folder = workspaceFolder();
  if (!folder) {
    vscode.window.showErrorMessage("JumpKick: open a folder containing jk.toml");
    return Promise.resolve(1);
  }
  const cwd = folder.uri.fsPath;
  if (!fs.existsSync(path.join(cwd, "jk.toml"))) {
    vscode.window.showErrorMessage("JumpKick: no jk.toml in " + cwd);
    return Promise.resolve(1);
  }

  const bin = jkBin();
  const label = `jk ${args.join(" ")}`;
  status.text = `$(sync~spin) ${label}`;
  output.appendLine(`$ ${bin} ${args.join(" ")}`);
  output.appendLine(`  cwd: ${cwd}`);

  return new Promise((resolve) => {
    const child = spawn(bin, args, {
      cwd,
      // The OutputChannel is a parser/pane, not a terminal: jk keeps ANSI on pipes, so ask for
      // plain text (same rationale as the IntelliJ client's NO_COLOR line).
      env: { ...process.env, NO_COLOR: "1" },
      shell: false,
    });
    child.stdout.on("data", (buf) => output.append(buf.toString()));
    child.stderr.on("data", (buf) => output.append(buf.toString()));
    child.on("error", (err) => {
      status.text = "$(error) jk";
      const msg =
        err.code === "ENOENT"
          ? `JumpKick: '${bin}' not found on PATH. Install jk and set jk.path if needed.`
          : `JumpKick: failed to start ${bin}: ${err.message}`;
      vscode.window.showErrorMessage(msg);
      output.appendLine(msg);
      resolve(1);
    });
    child.on("close", (code) => {
      const exit = code ?? 1;
      if (exit === 0) {
        status.text = "$(check) jk";
        status.tooltip = `${label} ok`;
      } else {
        status.text = "$(error) jk";
        status.tooltip = `${label} exited ${exit}`;
        output.show(true);
        vscode.window.showErrorMessage(`JumpKick: ${label} exited ${exit}`);
      }
      resolve(exit);
    });
  });
}

function maybeOfferBspInstall() {
  const folder = workspaceFolder();
  if (!folder) return;
  const cwd = folder.uri.fsPath;
  if (!fs.existsSync(path.join(cwd, "jk.toml"))) return;
  const bspJson = path.join(cwd, ".bsp", "jk.json");
  if (fs.existsSync(bspJson)) return;

  const auto = vscode.workspace.getConfiguration("jk").get("autoBspInstall", true);
  if (!auto) return;

  vscode.window
    .showInformationMessage(
      "JumpKick: install BSP connection so the IDE can import this project?",
      "Install",
      "Not now"
    )
    .then((choice) => {
      if (choice === "Install") runJk(["bsp", "install"]);
    });
}

module.exports = { activate, deactivate };
