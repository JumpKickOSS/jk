// SPDX-License-Identifier: Apache-2.0
import * as path from "node:path";
import { isRecord } from "./engine/http";

/** One diagnostic of a run, placed: what the Problems panel shows for it. */
export interface Problem {
  /** Absolute path of the file it is in. */
  readonly file: string;
  /** 0-based, as the editor counts. */
  readonly line: number;
  readonly column: number;
  readonly severity: "error" | "warning" | "info";
  readonly message: string;
  /** The tool that said it (`javac`, `lock`, `jk`). */
  readonly source: string;
  /** The tool's own name for it (`compiler.err.cant.resolve.location`), `""` when none. */
  readonly code: string;
}

/**
 * Which problems a finished run replaces. A lock or sync reads only the manifests and the lock, so
 * it settles their problems and leaves a compile's alone; every other job parses, locks and
 * compiles, so it settles both.
 */
export type ProblemFamily = "manifest" | "compile";

export function familiesSettledBy(kind: string): readonly ProblemFamily[] {
  return kind === "lock" || kind === "sync" || kind === "update" ? ["manifest"] : ["manifest", "compile"];
}

/** A manifest file's problem belongs to the manifest family; everything else to the compile one. */
export function familyOf(problem: Problem): ProblemFamily {
  const base = path.basename(problem.file);
  return base === "jk.toml" || base === "jk-lock.toml" || base === "jk-libs.toml" ? "manifest" : "compile";
}

/**
 * The problems in a run's journal row (`GET /api/history`): every diagnostic that names a file,
 * placed at its line and column. The engine records them structured; the message loses the
 * `file:line:col:` header the compiler put in front, which the panel shows on its own.
 */
export function problemsOf(row: Readonly<Record<string, unknown>>): Problem[] {
  const diagnostics = Array.isArray(row["diagnostics"]) ? row["diagnostics"] : [];
  const rowDir = typeof row["dir"] === "string" ? row["dir"] : "";
  const out: Problem[] = [];
  for (const d of diagnostics) {
    if (!isRecord(d)) continue;
    const rawFile = typeof d["file"] === "string" ? d["file"] : "";
    if (rawFile === "") continue;
    const dir = typeof d["dir"] === "string" && d["dir"] !== "" ? d["dir"] : rowDir;
    const file = path.isAbsolute(rawFile) ? rawFile : path.resolve(dir, rawFile);
    const line = positive(d["line"]);
    const column = positive(d["col"]);
    const severity = d["severity"] === "error" ? "error" : d["severity"] === "warning" ? "warning" : "info";
    const code = typeof d["code"] === "string" ? d["code"] : "";
    out.push({
      file,
      line: Math.max(0, line - 1),
      column: Math.max(0, column - 1),
      severity,
      message: withoutHeader(typeof d["message"] === "string" ? d["message"] : "", rawFile, line, column),
      source: code === "run-error" || code === "verbatim" || code === "" ? "jk" : code,
      code: typeof d["key"] === "string" ? d["key"] : "",
    });
  }
  return out;
}

/** `message` without a leading `file:line[:col]: [error|warning]: ` naming this very spot. */
function withoutHeader(message: string, file: string, line: number, column: number): string {
  const heads = [`${file}:${line}:${column}: `, `${file}:${line}: `];
  for (const head of heads) {
    if (line > 0 && message.startsWith(head)) {
      return message.slice(head.length).replace(/^(error|warning|note): /, "");
    }
  }
  return message;
}

function positive(v: unknown): number {
  return typeof v === "number" && Number.isFinite(v) && v > 0 ? Math.floor(v) : 0;
}
