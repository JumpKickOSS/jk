// SPDX-License-Identifier: Apache-2.0
import * as http from "node:http";

/** A non-2xx answer: the status and the engine's `error` text when the body carried one. */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly body: Readonly<Record<string, unknown>>,
  ) {
    super(typeof body["error"] === "string" ? body["error"] : `HTTP ${status}`);
  }
}

export interface JsonRequest {
  readonly method: "GET" | "POST" | "PUT" | "DELETE";
  readonly url: URL;
  readonly headers: Readonly<Record<string, string>>;
  readonly body?: unknown;
  readonly timeoutMs?: number;
}

/**
 * One JSON request over plain HTTP (the engine binds loopback only). Resolves with the parsed body
 * of a 2xx answer, rejects with {@link HttpError} for any other status and with the socket's own
 * error (`ECONNREFUSED`, a timeout) when there was no answer.
 */
export function requestJson(req: JsonRequest): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const payload = req.body === undefined ? undefined : Buffer.from(JSON.stringify(req.body), "utf8");
    const headers: Record<string, string> = { Accept: "application/json", ...req.headers };
    if (payload) {
      headers["Content-Type"] = "application/json";
      headers["Content-Length"] = String(payload.length);
    }
    const r = http.request(req.url, { method: req.method, headers }, (res) => {
      const chunks: Buffer[] = [];
      res.on("data", (c: Buffer) => chunks.push(c));
      res.on("error", reject);
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        let json: unknown = {};
        if (text.trim() !== "") {
          try {
            json = JSON.parse(text);
          } catch {
            json = { error: text.trim() };
          }
        }
        const status = res.statusCode ?? 0;
        if (status >= 200 && status < 300) resolve(json);
        else reject(new HttpError(status, isRecord(json) ? json : {}));
      });
    });
    r.setTimeout(req.timeoutMs ?? 30_000, () => r.destroy(new Error(`timed out: ${req.method} ${req.url.pathname}`)));
    r.on("error", reject);
    if (payload) r.write(payload);
    r.end();
  });
}

export function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}
