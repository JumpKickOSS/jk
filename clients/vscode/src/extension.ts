// SPDX-License-Identifier: Apache-2.0
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { EngineSession, type SessionState } from "./engine/session";
import { runJk, stopAll } from "./engine/jkProcess";
import type { EngineEvent } from "./engine/stream";
import { RunTracker, type Run } from "./runs";

/** What the extension hands to `vscode.extensions.getExtension(...).exports`; the tests read it. */
export interface JumpKickApi {
  readonly session: EngineSession;
  readonly runs: RunTracker;
  /** Start a job in the window's jk.toml folder; resolves with the finished run. */
  run(kind: string): Promise<Run | undefined>;
}

let session: EngineSession | undefined;

export function activate(context: vscode.ExtensionContext): JumpKickApi {
  const output = vscode.window.createOutputChannel("JumpKick");
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  const runs = new RunTracker(folderPaths());
  const s = new EngineSession({ bin: jkBin(), cwd: projectDir() ?? process.cwd() });
  session = s;

  const finished = new Map<number, (run: Run) => void>();
  /** Runs that finished lately, for a job whose finish arrives before its admission answer. */
  const recent = new Map<number, Run>();
  let lastResult: Run | undefined;

  const render = (): void => renderStatus(status, s.state, runs.active(), lastResult);
  context.subscriptions.push(
    output,
    status,
    { dispose: () => s.dispose() },
    s.onState(render),
    s.onEvent((event) => {
      runs.accept(event);
      echo(output, runs, event);
    }),
    runs.onDidChange((run) => {
      if (run.state === "finished") {
        lastResult = run;
        recent.set(run.jid, run);
        if (recent.size > 64) recent.delete(recent.keys().next().value ?? run.jid);
        finished.get(run.jid)?.(run);
        finished.delete(run.jid);
      }
      render();
    }),
    vscode.workspace.onDidChangeWorkspaceFolders(() => runs.setRoots(folderPaths())),
  );
  render();
  status.show();

  /** Start `kind` in the jk.toml folder and follow it to its finish. */
  const startJob = async (kind: string): Promise<Run | undefined> => {
    const dir = projectDir();
    if (!dir) {
      void vscode.window.showErrorMessage("JumpKick: open a folder containing jk.toml");
      return undefined;
    }
    if (!vscode.workspace.isTrusted) {
      void vscode.window.showWarningMessage("JumpKick: trust this folder to build it.");
      return undefined;
    }
    try {
      const api = await s.connect();
      const admission = await api.startJob(dir, kind);
      if (admission.kind === "already-running") {
        void vscode.window.showInformationMessage(`JumpKick: ${admission.message}`);
      }
      const jid = admission.jid;
      const done = new Promise<Run>((resolve) => {
        const early = recent.get(jid);
        if (early) resolve(early);
        else finished.set(jid, resolve);
      });
      const run = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Window, title: `jk ${kind}` },
        async (progress) => {
          let shown = 0;
          const sub = runs.onDidChange((r) => {
            if (r.jid !== jid || r.progress === undefined || r.progress <= shown) return;
            progress.report({ increment: r.progress - shown, message: `${Math.floor(r.progress)}%` });
            shown = r.progress;
          });
          try {
            return await done;
          } finally {
            sub.dispose();
          }
        },
      );
      if (run.success === false && !run.cancelled) {
        void vscode.window.showErrorMessage(`JumpKick: jk ${kind} failed`, "Show output").then((choice) => {
          if (choice) output.show(true);
        });
      }
      return run;
    } catch (e) {
      const why = e instanceof Error ? e.message : String(e);
      output.appendLine(`jk ${kind}: ${why}`);
      void vscode.window.showErrorMessage(`JumpKick: ${why}`);
      return undefined;
    }
  };

  context.subscriptions.push(
    vscode.commands.registerCommand("jk.build", () => startJob("build")),
    vscode.commands.registerCommand("jk.test", () => startJob("test")),
    vscode.commands.registerCommand("jk.lock", () => startJob("lock")),
    vscode.commands.registerCommand("jk.sync", () => startJob("sync")),
    vscode.commands.registerCommand("jk.cancel", async () => {
      const api = await s.connect();
      for (const run of runs.active()) await api.cancel(run.jid);
    }),
    vscode.commands.registerCommand("jk.reconnect", () => s.connect().catch(() => undefined)),
    vscode.commands.registerCommand("jk.showOutput", () => output.show(true)),
    vscode.commands.registerCommand("jk.bspInstall", () => bspInstall(output)),
    vscode.tasks.registerTaskProvider("jk", {
      provideTasks: () => defaultTasks(),
      resolveTask: (task) => resolveJkTask(task),
    }),
  );

  void s.connect().catch((e: unknown) => {
    output.appendLine(`JumpKick: ${e instanceof Error ? e.message : String(e)}`);
  });
  return { session: s, runs, run: startJob };
}

export function deactivate(): void {
  session?.dispose();
  session = undefined;
  stopAll();
}

function renderStatus(item: vscode.StatusBarItem, state: SessionState, active: Run[], last: Run | undefined): void {
  item.command = "jk.showOutput";
  if (state.kind === "connecting") {
    item.text = "$(sync~spin) jk";
    item.tooltip = "JumpKick: connecting to the engine";
    return;
  }
  if (state.kind === "offline" || state.kind === "failed") {
    item.text = "$(debug-disconnect) jk";
    item.tooltip = `JumpKick: ${state.reason}. Click to reconnect.`;
    item.command = "jk.reconnect";
    return;
  }
  const run = active[active.length - 1];
  if (run) {
    const pct = run.progress === undefined ? "" : ` ${Math.floor(run.progress)}%`;
    item.text = run.state === "queued" ? `$(clock) jk ${run.kind}` : `$(sync~spin) jk ${run.kind}${pct}`;
    item.tooltip = `JumpKick: ${run.kind} in ${run.dir}${run.state === "queued" ? " (queued)" : ""}`;
    return;
  }
  if (last) {
    const ok = last.success === true;
    item.text = `${ok ? "$(check)" : last.cancelled ? "$(circle-slash)" : "$(error)"} jk`;
    item.tooltip = `JumpKick: ${last.kind} ${ok ? "succeeded" : last.cancelled ? "was cancelled" : "failed"} · ${state.engine}`;
    return;
  }
  item.text = "$(package) jk";
  item.tooltip = `JumpKick: ${state.engine}`;
}

/** Write a job's output and diagnostics, as the engine streams them, to the output channel. */
function echo(output: vscode.OutputChannel, runs: RunTracker, event: EngineEvent): void {
  const d = event.data;
  const jid = typeof d["jid"] === "number" ? d["jid"] : -1;
  const run = runs.get(jid);
  if (event.type === "request-start" && run) {
    output.appendLine(`▶ jk ${run.kind} · ${run.dir}${run.trigger ? ` (${run.trigger})` : ""}`);
    return;
  }
  if (!run) return;
  if (event.type === "output" && typeof d["line"] === "string") output.appendLine(d["line"]);
  else if (event.type === "error" && typeof d["message"] === "string") output.appendLine(d["message"]);
  else if (event.type === "request-finish") {
    const verdict = d["success"] === true ? "✔ succeeded" : d["cancelled"] === true ? "◼ cancelled" : "✘ failed";
    output.appendLine(`${verdict} · jk ${run.kind}`);
  }
}

function folderPaths(): string[] {
  return (vscode.workspace.workspaceFolders ?? []).map((f) => f.uri.fsPath);
}

/** The first workspace folder holding a jk.toml: the project every command runs in. */
function projectDir(): string | undefined {
  return folderPaths().find((p) => fs.existsSync(path.join(p, "jk.toml")));
}

function jkBin(): string {
  return vscode.workspace.getConfiguration("jk").get<string>("path", "jk") || "jk";
}

async function bspInstall(output: vscode.OutputChannel): Promise<void> {
  const dir = projectDir();
  if (!dir || !vscode.workspace.isTrusted) return;
  try {
    const r = await runJk(jkBin(), ["bsp", "install"], dir);
    output.append(r.stdout + r.stderr);
    if (r.code !== 0) void vscode.window.showErrorMessage(`JumpKick: jk bsp install exited ${r.code}`, "Show output");
  } catch (e) {
    void vscode.window.showErrorMessage(`JumpKick: ${e instanceof Error ? e.message : String(e)}`);
  }
}

function defaultTasks(): vscode.Task[] {
  const dir = projectDir();
  const folder = vscode.workspace.workspaceFolders?.find((f) => f.uri.fsPath === dir);
  if (!dir || !folder) return [];
  return ["lock", "sync", "build", "test", "assemble"].map((verb) => {
    const exec = new vscode.ShellExecution(jkBin(), [verb], { cwd: dir });
    const task = new vscode.Task({ type: "jk", task: verb }, folder, `jk ${verb}`, "jk", exec);
    if (verb === "build" || verb === "assemble") task.group = vscode.TaskGroup.Build;
    task.presentationOptions = { reveal: vscode.TaskRevealKind.Always, panel: vscode.TaskPanelKind.Shared };
    return task;
  });
}

function resolveJkTask(task: vscode.Task): vscode.Task | undefined {
  const def = task.definition as { type?: string; task?: unknown; args?: unknown };
  if (def.type !== "jk" || typeof def.task !== "string") return undefined;
  const folder =
    typeof task.scope === "object" && task.scope !== null && "uri" in task.scope
      ? task.scope
      : vscode.workspace.workspaceFolders?.[0];
  if (!folder) return undefined;
  const extra = Array.isArray(def.args) ? def.args.map(String) : [];
  const exec = new vscode.ShellExecution(jkBin(), [def.task, ...extra], { cwd: folder.uri.fsPath });
  return new vscode.Task(task.definition, folder, task.name || `jk ${def.task}`, "jk", exec);
}
