// SPDX-License-Identifier: Apache-2.0
import * as http from "node:http";
import type { AddressInfo } from "node:net";

export interface StubJob {
  readonly jid: number;
  readonly dir: string;
  readonly kind: string;
}

/**
 * A stand-in for the engine's HTTP surface, as docs/contributors/http.md specifies it: the bearer
 * token on every `/api` call but `/api/identity`, the generation header (409
 * `engine-epoch-mismatch`), an SSE stream with increasing ids that replays from `Last-Event-ID`,
 * and `POST /api/build` / `/api/cancel`. Tests script what the engine says with {@link emit}.
 */
export class StubEngine {
  token = "stub-token";
  epoch = "0.0.0-stub@1";
  identity = { id: "stub00000001", label: "Stub engine", version: "0.0.0" };
  readonly jobs: StubJob[] = [];
  readonly cancelled: number[] = [];
  /** What `GET /api/history` answers; tests fill it. */
  history: Record<string, unknown>[] = [];
  /** Called for every admitted job, after the 202 is sent. */
  onJob: (job: StubJob) => void = () => undefined;
  /** When set, `POST /api/build` answers 409 naming this job, as for a checkout already building. */
  busyJid: number | undefined;
  /** Every request's method and path, in order. */
  readonly requests: string[] = [];

  private server: http.Server | undefined;
  private readonly streams = new Set<http.ServerResponse>();
  private readonly kept: { id: number; text: string }[] = [];
  private nextId = 1000;
  private nextJid = 1;
  private heartbeat: NodeJS.Timeout | undefined;
  private port = 0;

  async start(port = 0): Promise<void> {
    this.server = http.createServer((req, res) => this.handle(req, res));
    await new Promise<void>((resolve) => this.server?.listen(port, "127.0.0.1", resolve));
    this.port = (this.server.address() as AddressInfo).port;
    this.heartbeat = setInterval(() => {
      for (const s of this.streams) s.write(": heartbeat\n\n");
    }, 1000);
  }

  get url(): string {
    return `http://127.0.0.1:${this.port}/`;
  }

  /** The line `jk web -O jsonl` prints for this engine. */
  connectLine(): string {
    return JSON.stringify({ url: this.url, token: this.token, engineId: this.identity.id });
  }

  get streamCount(): number {
    return this.streams.size;
  }

  /** Broadcast one frame to every attached stream, and keep it for replay. */
  emit(type: string, data: Record<string, unknown>): void {
    const id = this.nextId++;
    const text = `id: ${id}\nevent: ${type}\ndata: ${JSON.stringify({ schema: 1, type, ...data })}\n\n`;
    this.kept.push({ id, text });
    for (const s of this.streams) s.write(text);
  }

  /** Drop every attached stream, as a restarting engine would. */
  dropStreams(): void {
    for (const s of this.streams) s.destroy();
    this.streams.clear();
  }

  async stop(): Promise<void> {
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.dropStreams();
    const server = this.server;
    this.server = undefined;
    if (server) {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  }

  /** Bind again on the same port, as a replacement engine does. */
  async restart(epoch: string): Promise<void> {
    const port = this.port;
    await this.stop();
    this.epoch = epoch;
    await this.start(port);
  }

  private handle(req: http.IncomingMessage, res: http.ServerResponse): void {
    const url = new URL(req.url ?? "/", this.url);
    this.requests.push(`${req.method} ${url.pathname}`);
    if (url.pathname === "/api/identity") return json(res, 200, this.identity);
    if (req.headers.authorization !== `Bearer ${this.token}`) {
      return json(res, 401, { error: "missing or invalid bearer token" });
    }
    if (url.pathname === "/api/events") return this.events(req, res);
    if (url.pathname === "/api/status") return json(res, 200, { engineEpoch: this.epoch, identity: this.identity });
    if (req.headers["x-jk-engine-epoch"] !== this.epoch) {
      return json(res, 409, { error: "engine-epoch-mismatch", engineEpoch: this.epoch });
    }
    if (req.method === "POST" && url.pathname === "/api/build") {
      return readBody(req, (body) => {
        if (this.busyJid !== undefined) {
          json(res, 409, { error: "a build is already running in this project", jid: this.busyJid });
          return;
        }
        const job: StubJob = { jid: this.nextJid++, dir: String(body["dir"]), kind: String(body["kind"] ?? "build") };
        this.jobs.push(job);
        json(res, 202, { jid: job.jid, events: "/api/events" });
        setImmediate(() => this.onJob(job));
      });
    }
    if (req.method === "POST" && url.pathname === "/api/cancel") {
      return readBody(req, (body) => {
        if (typeof body["jid"] === "number") this.cancelled.push(body["jid"]);
        json(res, 200, { cancelled: true });
      });
    }
    if (req.method === "GET" && url.pathname === "/api/history") return json(res, 200, this.history);
    json(res, 404, { error: "no such route" });
  }

  private events(req: http.IncomingMessage, res: http.ServerResponse): void {
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store" });
    const last = Number(req.headers["last-event-id"] ?? "");
    if (Number.isFinite(last) && last > 0) {
      for (const f of this.kept) if (f.id > last) res.write(f.text);
    }
    res.write(": connected\n\n");
    this.streams.add(res);
    res.on("close", () => this.streams.delete(res));
  }
}

/** The frames a successful or failing job sends, in the engine's order. */
export function scriptJob(engine: StubEngine, job: StubJob, success: boolean, extra: () => void = () => undefined): void {
  engine.emit("request-start", { jid: job.jid, kind: job.kind, dir: job.dir, trigger: "web", startedAt: Date.now() });
  engine.emit("workspace-progress", { jid: job.jid, dir: job.dir, progress: 50 });
  extra();
  engine.emit("request-finish", { jid: job.jid, kind: job.kind, dir: job.dir, success, cancelled: false, progress: success ? 100 : 50 });
}

function json(res: http.ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function readBody(req: http.IncomingMessage, then: (body: Record<string, unknown>) => void): void {
  const chunks: Buffer[] = [];
  req.on("data", (c: Buffer) => chunks.push(c));
  req.on("end", () => {
    try {
      then(JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>);
    } catch {
      then({});
    }
  });
}
