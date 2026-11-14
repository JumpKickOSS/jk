// SPDX-License-Identifier: Apache-2.0
import * as http from "node:http";
import type { EngineApi } from "./api";
import { isRecord } from "./http";
import { SseParser } from "./sse";

/** One engine event: the SSE event name and its JSON payload. */
export interface EngineEvent {
  readonly type: string;
  readonly data: Readonly<Record<string, unknown>>;
}

/** Why a stream ended, for the caller to decide between retrying and rediscovering. */
export type StreamEnd =
  | { readonly kind: "closed" }
  | { readonly kind: "refused"; readonly message: string }
  | { readonly kind: "unauthorized" }
  | { readonly kind: "status"; readonly status: number };

/**
 * One connection to `GET /api/events`. The token rides in the `Authorization` header (Node can send
 * it, unlike a browser's EventSource), and a reconnect names the last frame it saw as
 * `Last-Event-ID`, so the engine replays what was missed instead of sending a snapshot.
 */
export class EventStream {
  private request: http.ClientRequest | undefined;
  private closed = false;

  constructor(
    private readonly api: EngineApi,
    private readonly onEvent: (event: EngineEvent) => void,
  ) {}

  /** Connect and stream until the connection ends; resolves with why it ended. `lastEventId` resumes. */
  open(lastEventId: string, onLive: () => void): Promise<{ end: StreamEnd; lastEventId: string }> {
    const parser = new SseParser((frame) => {
      try {
        const data: unknown = JSON.parse(frame.data);
        if (isRecord(data)) this.onEvent({ type: frame.event, data });
      } catch {
        // A malformed frame is dropped, never fatal to the stream.
      }
    });
    parser.lastEventId = lastEventId;
    return new Promise((resolve) => {
      let settled = false;
      const done = (end: StreamEnd): void => {
        if (settled) return;
        settled = true;
        this.request = undefined;
        resolve({ end, lastEventId: parser.lastEventId });
      };
      const headers: Record<string, string> = { ...this.api.authHeaders(), Accept: "text/event-stream" };
      if (lastEventId) headers["Last-Event-ID"] = lastEventId;
      const req = http.request(this.api.url("/api/events"), { method: "GET", headers }, (res) => {
        const status = res.statusCode ?? 0;
        if (status !== 200) {
          res.resume();
          done(status === 401 ? { kind: "unauthorized" } : { kind: "status", status });
          return;
        }
        onLive();
        res.setEncoding("utf8");
        res.on("data", (chunk: string) => parser.feed(chunk));
        res.on("end", () => done({ kind: "closed" }));
        res.on("error", () => done({ kind: "closed" }));
        res.on("close", () => done({ kind: "closed" }));
      });
      // The engine writes a heartbeat every second; ten silent seconds is a dead connection.
      req.setTimeout(10_000, () => req.destroy(new Error("event stream went silent")));
      req.on("error", (e: NodeJS.ErrnoException) =>
        done(e.code === "ECONNREFUSED" ? { kind: "refused", message: e.message } : { kind: "closed" }),
      );
      this.request = req;
      if (this.closed) req.destroy();
      req.end();
    });
  }

  close(): void {
    this.closed = true;
    this.request?.destroy();
  }
}
