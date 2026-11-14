// SPDX-License-Identifier: Apache-2.0

/** How long manifests must stay unchanged before a relock: the IntelliJ plugin's quiet window. */
export const QUIET_MS = 2_000;

/** The language server's workspace command that applies a model (the jk-jdtls bundle's). */
export const APPLY_COMMAND = "jk.jdtls.applyModel";

/**
 * Runs `fire` once `touch` has not been called for `ms`. A touch while `fire` runs schedules one
 * more run after it, so a change made during a relock is never lost.
 */
export class QuietWindow {
  private timer: NodeJS.Timeout | undefined;
  private running: Promise<void> | undefined;
  private again = false;
  private disposed = false;

  constructor(
    private readonly ms: number,
    private readonly fire: () => Promise<void>,
  ) {}

  touch(): void {
    if (this.disposed) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = undefined;
      this.run();
    }, this.ms);
  }

  /** Run now (once any run in flight finishes): the first sync when a window opens. */
  now(): Promise<void> {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    return this.run();
  }

  dispose(): void {
    this.disposed = true;
    if (this.timer) clearTimeout(this.timer);
  }

  private run(): Promise<void> {
    if (this.running) {
      this.again = true;
      return this.running;
    }
    this.running = this.fire()
      .catch(() => undefined)
      .finally(() => {
        this.running = undefined;
        if (this.again && !this.disposed) {
          this.again = false;
          void this.run();
        }
      });
    return this.running;
  }
}

/** What a sync did. */
export type SyncOutcome =
  | { readonly kind: "applied"; readonly projects: readonly string[] }
  | { readonly kind: "unchanged" }
  | { readonly kind: "lock-failed" }
  | { readonly kind: "failed"; readonly reason: string };

export interface ModelSyncDeps {
  /** Relock through the engine (`POST /api/build` kind=lock); resolves with its success. */
  relock(): Promise<boolean>;
  /** `GET /api/project/model` for the project folder. */
  fetchModel(): Promise<unknown>;
  /** Hand the model, as JSON, to the language server; resolves with the projects it applied. */
  apply(json: string): Promise<unknown>;
}

/**
 * Keeps the Java language server's projects equal to the engine's model: relock, read the model,
 * and apply it when it differs from the last one applied. A failed lock applies nothing; its
 * problems are on the manifest already.
 */
export class ModelSync {
  private last = "";

  constructor(private readonly deps: ModelSyncDeps) {}

  /** Forget what was applied, so the next sync applies whatever it reads. */
  invalidate(): void {
    this.last = "";
  }

  async sync(): Promise<SyncOutcome> {
    try {
      if (!(await this.deps.relock())) return { kind: "lock-failed" };
      const json = JSON.stringify(await this.deps.fetchModel());
      if (json === this.last) return { kind: "unchanged" };
      const result = await this.deps.apply(json);
      this.last = json;
      return { kind: "applied", projects: projectsOf(result) };
    } catch (e) {
      return { kind: "failed", reason: e instanceof Error ? e.message : String(e) };
    }
  }
}

/** A manifest or lockfile, outside any `target` tree. */
export function isManifest(fsPath: string): boolean {
  const parts = fsPath.split(/[\\/]/);
  const name = parts[parts.length - 1];
  if (name !== "jk.toml" && name !== "jk-lock.toml") return false;
  return !parts.slice(0, -1).includes("target");
}

function projectsOf(result: unknown): string[] {
  if (typeof result !== "object" || result === null) return [];
  const projects = (result as Record<string, unknown>)["projects"];
  return Array.isArray(projects) ? projects.map(String) : [];
}
