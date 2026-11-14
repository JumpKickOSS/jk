// SPDX-License-Identifier: Apache-2.0

/** One server-sent event as the engine frames it: `id:`, `event:` and one or more `data:` lines. */
export interface SseFrame {
  readonly id: string;
  readonly event: string;
  readonly data: string;
}

/**
 * Incremental parser for a `text/event-stream` body
 * (https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation).
 * Feed it decoded chunks in arrival order; it calls `onFrame` once per dispatched event. Comment
 * lines (the engine's `: heartbeat`) and frames with no data are dropped.
 */
export class SseParser {
  private buffer = "";
  private id = "";
  private event = "";
  private data: string[] = [];
  /** The id of the last dispatched frame that carried one: what a reconnect names. */
  lastEventId = "";

  constructor(private readonly onFrame: (frame: SseFrame) => void) {}

  feed(chunk: string): void {
    this.buffer += chunk;
    for (;;) {
      const nl = this.buffer.search(/\r\n|\r|\n/);
      if (nl < 0) return;
      const crlf = this.buffer.startsWith("\r\n", nl);
      // A lone \r at the end of a chunk may be the first half of \r\n: wait for the next chunk.
      if (!crlf && this.buffer[nl] === "\r" && nl === this.buffer.length - 1) return;
      const line = this.buffer.slice(0, nl);
      this.buffer = this.buffer.slice(nl + (crlf ? 2 : 1));
      this.line(line);
    }
  }

  private line(line: string): void {
    if (line === "") {
      this.dispatch();
      return;
    }
    if (line.startsWith(":")) return;
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    switch (field) {
      case "id":
        if (!value.includes("\0")) this.id = value;
        break;
      case "event":
        this.event = value;
        break;
      case "data":
        this.data.push(value);
        break;
      default:
        break;
    }
  }

  private dispatch(): void {
    const event = this.event || "message";
    const id = this.id;
    const data = this.data;
    this.event = "";
    this.data = [];
    if (id) this.lastEventId = id;
    if (data.length === 0) return;
    this.onFrame({ id, event, data: data.join("\n") });
  }
}
