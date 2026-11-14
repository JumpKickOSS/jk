// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import { test } from "node:test";
import { SseParser, type SseFrame } from "../../src/engine/sse";

function parse(...chunks: string[]): { frames: SseFrame[]; parser: SseParser } {
  const frames: SseFrame[] = [];
  const parser = new SseParser((f) => frames.push(f));
  for (const c of chunks) parser.feed(c);
  return { frames, parser };
}

test("a frame split across chunks is dispatched once, whole", () => {
  const { frames } = parse("id: 7\nevent: request-st", "art\ndata: {\"jid\":1}\n", "\n");
  assert.deepEqual(frames, [{ id: "7", event: "request-start", data: '{"jid":1}' }]);
});

test("CRLF and a CR split between chunks both end a line", () => {
  const { frames } = parse("event: a\r", "\ndata: 1\r\n\r\nevent: b\rdata: 2\r\r\n");
  assert.deepEqual(
    frames.map((f) => [f.event, f.data]),
    [
      ["a", "1"],
      ["b", "2"],
    ],
  );
});

test("heartbeat comments and data-less frames dispatch nothing", () => {
  const { frames } = parse(": heartbeat\n\n: heartbeat\n\nevent: x\n\n");
  assert.equal(frames.length, 0);
});

test("data lines join with newlines and the last id is kept for a reconnect", () => {
  const { frames, parser } = parse("id: 1\ndata: a\ndata: b\n\nid: 2\ndata: c\n\n");
  assert.deepEqual(
    frames.map((f) => f.data),
    ["a\nb", "c"],
  );
  assert.equal(frames[0]?.event, "message");
  assert.equal(parser.lastEventId, "2");
});
