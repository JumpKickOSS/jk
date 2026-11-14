// SPDX-License-Identifier: Apache-2.0
import * as path from "node:path";
import type { EngineEvent } from "./engine/stream";

/** One engine job in a checkout this window shows, folded from the event stream. */
export interface Run {
  readonly jid: number;
  readonly kind: string;
  readonly dir: string;
  /** Who asked: `cli`, `mcp`, `web`, `bsp`, … as the engine's `request-start` names it. */
  readonly trigger: string;
  readonly state: "queued" | "running" | "finished";
  /** Aggregate percent, `undefined` until the engine has sent one. */
  readonly progress: number | undefined;
  readonly success: boolean | undefined;
  readonly cancelled: boolean;
  /** Engine wall clock when the job started, `0` when unknown. */
  readonly startedAt: number;
}

type Listener = (run: Run) => void;

/**
 * Folds `request-queued`, `request-start`, `run-snapshot`, progress-bearing frames and
 * `request-finish` into one {@link Run} per job, for the checkouts under this window's folders
 * only. Every frame of a job carries the run's aggregate `progress` (SsePublisher.frame), so any
 * frame with a known `jid` moves the bar.
 */
export class RunTracker {
  private readonly runs = new Map<number, Run>();
  private readonly listeners = new Set<Listener>();

  constructor(private roots: readonly string[]) {}

  /** Forget every job: the engine they ran on is gone. */
  reset(): void {
    this.runs.clear();
  }

  setRoots(roots: readonly string[]): void {
    this.roots = roots;
  }

  onDidChange(l: Listener): { dispose(): void } {
    this.listeners.add(l);
    return { dispose: () => this.listeners.delete(l) };
  }

  get(jid: number): Run | undefined {
    return this.runs.get(jid);
  }

  /** Jobs not yet finished, oldest first. */
  active(): Run[] {
    return [...this.runs.values()].filter((r) => r.state !== "finished");
  }

  /** Whether `dir` is one of this window's folders or lies under one. */
  covers(dir: string): boolean {
    return this.roots.some((root) => isWithin(root, dir));
  }

  accept(event: EngineEvent): void {
    const d = event.data;
    const jid = typeof d["jid"] === "number" ? d["jid"] : undefined;
    if (jid === undefined) return;
    const known = this.runs.get(jid);
    switch (event.type) {
      case "request-queued":
      case "request-start":
      case "run-snapshot": {
        const dir = typeof d["dir"] === "string" ? d["dir"] : "";
        if (!this.covers(dir)) return;
        this.put({
          jid,
          kind: str(d["kind"]) || known?.kind || "build",
          dir,
          trigger: str(d["trigger"]) || known?.trigger || "",
          state: event.type === "request-queued" ? "queued" : "running",
          progress: num(d["progress"]) ?? known?.progress,
          success: undefined,
          cancelled: false,
          startedAt: num(d["startedAt"]) ?? known?.startedAt ?? 0,
        });
        return;
      }
      case "request-finish": {
        if (!known) return;
        this.put({
          ...known,
          state: "finished",
          progress: num(d["progress"]) ?? known.progress,
          success: d["success"] === true,
          cancelled: d["cancelled"] === true,
        });
        // A finished run is announced once; the tracker keeps only live ones.
        this.runs.delete(jid);
        return;
      }
      default: {
        if (!known || known.state === "finished") return;
        const progress = num(d["progress"]);
        if (progress === undefined || progress === known.progress) return;
        this.put({ ...known, state: "running", progress });
      }
    }
  }

  private put(run: Run): void {
    this.runs.set(run.jid, run);
    for (const l of this.listeners) l(run);
  }
}

/** Whether `child` is `root` or under it; case-insensitive on Windows, as its paths are. */
export function isWithin(root: string, child: string): boolean {
  if (root === "" || child === "") return false;
  const norm = (p: string): string => {
    const n = path.resolve(p);
    return process.platform === "win32" ? n.toLowerCase() : n;
  };
  const rel = path.relative(norm(root), norm(child));
  return rel === "" || (rel !== ".." && !rel.startsWith(".." + path.sep) && !path.isAbsolute(rel));
}

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

function num(v: unknown): number | undefined {
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
}
