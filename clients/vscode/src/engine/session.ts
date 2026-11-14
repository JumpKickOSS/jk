// SPDX-License-Identifier: Apache-2.0
import { EngineApi } from "./api";
import { discover, type EngineEndpoint } from "./connect";
import { HttpError } from "./http";
import { EventStream, type EngineEvent } from "./stream";

/** Where the session stands, for the status bar. */
export type SessionState =
  | { readonly kind: "connecting" }
  | { readonly kind: "live"; readonly engine: string }
  | { readonly kind: "offline"; readonly reason: string }
  | { readonly kind: "failed"; readonly reason: string };

export interface SessionOptions {
  /** Finds the engine; `discover` (runs `jk web -O jsonl`) unless a test supplies its own. */
  readonly discover?: () => Promise<EngineEndpoint>;
  readonly bin?: string;
  readonly cwd?: string;
  /** Delay before the stream reconnects to the same engine, doubled up to the cap. */
  readonly retryMs?: number;
  readonly retryCapMs?: number;
  /** How long a refused connection is retried before the session calls the engine stopped. */
  readonly refusedGiveUpMs?: number;
}

type Listener<T> = (value: T) => void;

/**
 * The one connection a window holds to its engine: the endpoint jk named, the API client and the
 * event stream. The stream reconnects to the same address by itself (an engine that is replaced
 * keeps its port and token); a refused connection means no engine is serving, and the session goes
 * offline rather than starting one behind the user's back. {@link connect} — every command, the
 * status bar's click — asks jk again, which starts the engine when none is running.
 */
export class EngineSession {
  private api: EngineApi | undefined;
  private stream: EventStream | undefined;
  private lastEventId = "";
  private disposed = false;
  private connecting: Promise<EngineApi> | undefined;
  private current: SessionState = { kind: "connecting" };
  private readonly stateListeners = new Set<Listener<SessionState>>();
  private readonly eventListeners = new Set<Listener<EngineEvent>>();
  private readonly findEngine: () => Promise<EngineEndpoint>;
  private readonly retryMs: number;
  private readonly retryCapMs: number;
  private readonly refusedGiveUpMs: number;

  constructor(options: SessionOptions) {
    const bin = options.bin ?? "jk";
    const cwd = options.cwd ?? process.cwd();
    this.findEngine = options.discover ?? (() => discover(bin, cwd));
    this.retryMs = options.retryMs ?? 500;
    this.retryCapMs = options.retryCapMs ?? 5_000;
    this.refusedGiveUpMs = options.refusedGiveUpMs ?? 15_000;
  }

  get state(): SessionState {
    return this.current;
  }

  onState(l: Listener<SessionState>): { dispose(): void } {
    this.stateListeners.add(l);
    return { dispose: () => this.stateListeners.delete(l) };
  }

  onEvent(l: Listener<EngineEvent>): { dispose(): void } {
    this.eventListeners.add(l);
    return { dispose: () => this.eventListeners.delete(l) };
  }

  /** The live API, connecting first (and starting the engine through jk) when there is none. */
  async connect(): Promise<EngineApi> {
    if (this.api && this.current.kind === "live") return this.api;
    if (!this.connecting) {
      this.connecting = this.establish().finally(() => {
        this.connecting = undefined;
      });
    }
    return this.connecting;
  }

  dispose(): void {
    this.disposed = true;
    this.stream?.close();
    this.stateListeners.clear();
    this.eventListeners.clear();
  }

  private async establish(): Promise<EngineApi> {
    this.setState({ kind: "connecting" });
    this.stream?.close();
    try {
      const endpoint = await this.findEngine();
      const api = new EngineApi(endpoint);
      const identity = await api.identity();
      if (endpoint.engineId !== "" && identity.id !== endpoint.engineId) {
        throw new Error(`another engine answers at ${endpoint.url} (${identity.label})`);
      }
      await api.status();
      this.api = api;
      // Return once the stream is attached, so a job started next cannot finish unseen.
      await this.runStream(api, identity.label || endpoint.url, identity.id);
      return api;
    } catch (e) {
      const reason = e instanceof Error ? e.message : String(e);
      this.setState({ kind: "failed", reason });
      throw e;
    }
  }

  /**
   * Stream until disposed or the engine is gone; reconnect with backoff in between. Resolves when
   * the first connection is live, rejects when it could not be made.
   */
  private runStream(api: EngineApi, engine: string, engineId: string): Promise<void> {
    const stream = new EventStream(api, (event) => {
      for (const l of this.eventListeners) l(event);
    });
    this.stream = stream;
    let attached: (() => void) | undefined;
    let refused: ((e: Error) => void) | undefined;
    const first = new Promise<void>((resolve, reject) => {
      attached = resolve;
      refused = reject;
    });
    void (async () => {
      let delay = this.retryMs;
      let refusedSince: number | undefined;
      while (!this.disposed && this.stream === stream) {
        const { end, lastEventId } = await stream.open(this.lastEventId, () => {
          delay = this.retryMs;
          this.setState({ kind: "live", engine });
          attached?.();
          attached = undefined;
        });
        this.lastEventId = lastEventId;
        if (attached) {
          // The first connection never went live: the caller hears why.
          refused?.(new Error(end.kind === "status" ? `the engine's event stream answered ${end.status}` : "the engine's event stream did not open"));
          if (this.stream === stream) this.stream = undefined;
          return;
        }
        if (this.disposed || this.stream !== stream) return;
        if (end.kind === "unauthorized") {
          // The token was rotated: jk reads the new one.
          this.api = undefined;
          void this.connect().catch(() => undefined);
          return;
        }
        if (end.kind === "refused") {
          // A replacement engine takes the port over within moments; nothing at all for longer
          // means the engine was stopped.
          refusedSince ??= Date.now();
          if (Date.now() - refusedSince > this.refusedGiveUpMs) {
            this.api = undefined;
            this.setState({ kind: "offline", reason: "the engine is not running" });
            return;
          }
        } else {
          refusedSince = undefined;
        }
        this.setState({ kind: "offline", reason: end.kind === "status" ? `the engine answered ${end.status}` : "reconnecting" });
        await sleep(delay);
        delay = Math.min(delay * 2, this.retryCapMs);
        if (this.disposed || this.stream !== stream) return;
        // Another engine on this address (another home's, or a guest's) is not ours to show.
        const now = await api.identity().catch(() => undefined);
        if (now && engineId !== "" && now.id !== engineId) {
          this.api = undefined;
          void this.connect().catch(() => undefined);
          return;
        }
        // A replacement engine has a new generation: latch it before the next call names it.
        await api.status().catch((e: unknown) => {
          if (e instanceof HttpError && e.status === 401) this.api = undefined;
        });
      }
    })();
    return first;
  }

  private setState(state: SessionState): void {
    this.current = state;
    for (const l of this.stateListeners) l(state);
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
