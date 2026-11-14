// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { EngineApi } from "../../src/engine/api";
import { HttpError } from "../../src/engine/http";
import { EngineSession, type SessionState } from "../../src/engine/session";
import type { EngineEvent } from "../../src/engine/stream";
import { StubEngine } from "../support/stubEngine";

let engine: StubEngine;
let session: EngineSession | undefined;

beforeEach(async () => {
  engine = new StubEngine();
  await engine.start();
});

afterEach(async () => {
  session?.dispose();
  session = undefined;
  await engine.stop();
});

function open(found = () => ({ url: engine.url, token: engine.token, engineId: engine.identity.id })): {
  s: EngineSession;
  events: EngineEvent[];
  states: SessionState[];
  discovered: () => number;
} {
  let n = 0;
  const s = new EngineSession({
    discover: async () => {
      n++;
      return found();
    },
    retryMs: 20,
    retryCapMs: 50,
    refusedGiveUpMs: 300,
  });
  session = s;
  const events: EngineEvent[] = [];
  const states: SessionState[] = [];
  s.onEvent((e) => events.push(e));
  s.onState((st) => states.push(st));
  return { s, events, states, discovered: () => n };
}

async function until(what: string, cond: () => boolean, ms = 3000): Promise<void> {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}

test("connect returns once the event stream is attached, and frames arrive in order", async () => {
  const { s, events } = open();
  const api = await s.connect();
  assert.equal(s.state.kind, "live");
  assert.equal(engine.streamCount, 1);
  assert.equal(api.engineEpoch, engine.epoch);
  engine.emit("request-start", { jid: 1, dir: "/x" });
  engine.emit("request-finish", { jid: 1, success: true });
  await until("two frames", () => events.length === 2);
  assert.deepEqual(
    events.map((e) => e.type),
    ["request-start", "request-finish"],
  );
});

test("a dropped stream reconnects with Last-Event-ID and misses nothing", async () => {
  const { s, events } = open();
  await s.connect();
  engine.emit("label", { jid: 1, progress: 1 });
  await until("first frame", () => events.length === 1);
  engine.dropStreams();
  engine.emit("label", { jid: 1, progress: 2 }); // sent while no stream is attached
  await until("replayed frame", () => events.length === 2);
  engine.emit("label", { jid: 1, progress: 3 });
  await until("live frame", () => events.length === 3);
  assert.deepEqual(
    events.map((e) => e.data["progress"]),
    [1, 2, 3],
  );
});

test("a replacement engine's new generation is latched before the next call", async () => {
  const { s } = open();
  const api = await s.connect();
  await engine.restart("0.0.0-stub@2");
  await until("live again", () => engine.streamCount === 1);
  const admitted = await api.startJob("/x", "build");
  assert.equal(admitted.kind, "started");
  assert.equal(api.engineEpoch, "0.0.0-stub@2");
});

test("a rotated token sends the session back to jk for the new one", async () => {
  const { s, discovered } = open();
  await s.connect();
  engine.token = "rotated";
  engine.dropStreams();
  await until("rediscovered and live", () => discovered() === 2 && s.state.kind === "live" && engine.streamCount === 1);
});

test("a stopped engine leaves the session offline, and no engine is started for it", async () => {
  const { s, states, discovered } = open();
  await s.connect();
  await engine.stop();
  await until("offline", () => s.state.kind === "offline" && s.state.reason === "the engine is not running");
  assert.equal(discovered(), 1);
  assert.ok(states.some((st) => st.kind === "live"));
});

test("an engine at the address that is not the one jk named is refused", async () => {
  const { s } = open(() => ({ url: engine.url, token: engine.token, engineId: "someone-else" }));
  await assert.rejects(s.connect(), /another engine answers/);
  assert.equal(s.state.kind, "failed");
});

test("the API speaks the dashboard's contract: bearer token, generation header, 409 for a busy checkout", async () => {
  const api = new EngineApi({ url: engine.url, token: engine.token, engineId: "" });
  engine.busyJid = 77;
  assert.deepEqual(await api.startJob("/x", "test"), {
    kind: "already-running",
    jid: 77,
    message: "a build is already running in this project",
  });
  const wrong = new EngineApi({ url: engine.url, token: "nope", engineId: "" });
  await assert.rejects(wrong.status(), (e: unknown) => e instanceof HttpError && e.status === 401);
  assert.ok(engine.requests.includes("POST /api/build"));
});
