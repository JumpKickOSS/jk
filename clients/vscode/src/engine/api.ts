// SPDX-License-Identifier: Apache-2.0
import type { EngineEndpoint } from "./connect";
import { HttpError, isRecord, requestJson } from "./http";

/** The engine's identity as `GET /api/identity` answers it (docs/contributors/http.md). */
export interface EngineIdentity {
  readonly id: string;
  readonly label: string;
  readonly version: string;
}

/** A job `POST /api/build` admitted, or the one already running for that checkout. */
export type Admission =
  | { readonly kind: "started"; readonly jid: number }
  | { readonly kind: "already-running"; readonly jid: number; readonly message: string };

const EPOCH_HEADER = "X-Jk-Engine-Epoch";

/**
 * The engine's `/api` as the dashboard's `api.js` speaks it: a bearer token on every call, and the
 * engine generation (`engineEpoch`, latched from `GET /api/status`) on every call but the status
 * read itself. A 409 `engine-epoch-mismatch` means the engine was replaced under the same address:
 * the new epoch is latched and the call retried once.
 */
export class EngineApi {
  private epoch = "";

  constructor(readonly endpoint: EngineEndpoint) {}

  get engineEpoch(): string {
    return this.epoch;
  }

  url(path: string, query?: Readonly<Record<string, string | number>>): URL {
    const u = new URL(path.replace(/^\//, ""), this.endpoint.url);
    for (const [k, v] of Object.entries(query ?? {})) u.searchParams.set(k, String(v));
    return u;
  }

  authHeaders(): Record<string, string> {
    return { Authorization: `Bearer ${this.endpoint.token}` };
  }

  async identity(): Promise<EngineIdentity> {
    const body = await requestJson({ method: "GET", url: this.url("/api/identity"), headers: {}, timeoutMs: 5_000 });
    const o = isRecord(body) ? body : {};
    return { id: str(o["id"]), label: str(o["label"]), version: str(o["version"]) };
  }

  /** `GET /api/status`, latching the engine generation every later call names. */
  async status(): Promise<Record<string, unknown>> {
    const body = await requestJson({ method: "GET", url: this.url("/api/status"), headers: this.authHeaders(), timeoutMs: 10_000 });
    const o = isRecord(body) ? body : {};
    if (typeof o["engineEpoch"] === "string") this.epoch = o["engineEpoch"];
    return o;
  }

  get(path: string, query?: Readonly<Record<string, string | number>>): Promise<unknown> {
    return this.call("GET", path, query, undefined);
  }

  post(path: string, body: unknown): Promise<unknown> {
    return this.call("POST", path, undefined, body);
  }

  /** Start a `kind` job (`build`, `test`, `lock`, `sync`, …) in `dir`: the CLI's own request. */
  async startJob(dir: string, kind: string): Promise<Admission> {
    try {
      const body = await this.post("/api/build", { dir, kind });
      const jid = isRecord(body) && typeof body["jid"] === "number" ? body["jid"] : -1;
      return { kind: "started", jid };
    } catch (e) {
      if (e instanceof HttpError && e.status === 409 && typeof e.body["jid"] === "number") {
        return { kind: "already-running", jid: e.body["jid"], message: e.message };
      }
      throw e;
    }
  }

  async cancel(jid: number): Promise<void> {
    await this.post("/api/cancel", { jid });
  }

  private async call(
    method: "GET" | "POST",
    path: string,
    query: Readonly<Record<string, string | number>> | undefined,
    body: unknown,
    retried = false,
  ): Promise<unknown> {
    if (this.epoch === "") await this.status();
    const headers = { ...this.authHeaders(), [EPOCH_HEADER]: this.epoch };
    try {
      return await requestJson({ method, url: this.url(path, query), headers, body });
    } catch (e) {
      if (!retried && e instanceof HttpError && e.status === 409 && e.body["error"] === "engine-epoch-mismatch") {
        this.epoch = typeof e.body["engineEpoch"] === "string" ? e.body["engineEpoch"] : "";
        return this.call(method, path, query, body, true);
      }
      throw e;
    }
  }
}

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}
