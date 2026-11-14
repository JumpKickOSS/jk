// SPDX-License-Identifier: Apache-2.0
import * as vscode from "vscode";
import type { EngineApi } from "./engine/api";
import { isRecord } from "./engine/http";
import { familiesSettledBy, familyOf, problemsOf, type Problem, type ProblemFamily } from "./problems";
import type { Run } from "./runs";

/**
 * The Problems panel's JumpKick entries: each finished job's diagnostics, read from its journal
 * row, replace the ones of the families it settles for its checkout, so a green run clears what
 * the last red one marked.
 */
export class ProblemsPanel implements vscode.Disposable {
  private readonly collection = vscode.languages.createDiagnosticCollection("jumpkick");
  private readonly byDir = new Map<string, Map<ProblemFamily, Problem[]>>();

  dispose(): void {
    this.collection.dispose();
  }

  /** Read `run`'s journal row from the engine and show its problems. */
  async settle(api: EngineApi, run: Run): Promise<Problem[]> {
    const rows = await api.get("/api/history", run.startedAt > 0 ? { since: run.startedAt - 1 } : {});
    const row = Array.isArray(rows) ? rows.find((r) => isRecord(r) && r["requestId"] === run.jid) : undefined;
    if (!isRecord(row)) return [];
    const problems = problemsOf(row);
    this.apply(run.dir, run.kind, problems);
    return problems;
  }

  apply(dir: string, kind: string, problems: readonly Problem[]): void {
    const families = this.byDir.get(dir) ?? new Map<ProblemFamily, Problem[]>();
    for (const family of familiesSettledBy(kind)) families.set(family, problems.filter((p) => familyOf(p) === family));
    this.byDir.set(dir, families);
    this.render();
  }

  private render(): void {
    const byFile = new Map<string, vscode.Diagnostic[]>();
    for (const families of this.byDir.values()) {
      for (const problems of families.values()) {
        for (const p of problems) {
          const at = new vscode.Position(p.line, p.column);
          const d = new vscode.Diagnostic(new vscode.Range(at, at), p.message, severity(p));
          d.source = p.source;
          if (p.code) d.code = p.code;
          const list = byFile.get(p.file) ?? [];
          list.push(d);
          byFile.set(p.file, list);
        }
      }
    }
    this.collection.clear();
    for (const [file, list] of byFile) this.collection.set(vscode.Uri.file(file), list);
  }
}

function severity(p: Problem): vscode.DiagnosticSeverity {
  if (p.severity === "error") return vscode.DiagnosticSeverity.Error;
  if (p.severity === "warning") return vscode.DiagnosticSeverity.Warning;
  return vscode.DiagnosticSeverity.Information;
}
