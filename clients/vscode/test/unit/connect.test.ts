// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import { test } from "node:test";
import { discover, parseEndpoint } from "../../src/engine/connect";

test("jk web -O jsonl's line names the engine, the url always ending in a slash", () => {
  const ep = parseEndpoint({
    code: 0,
    stdout: 'starting engine\n{"url":"http://127.0.0.1:8911","token":"t0k","engineId":"abc"}\n',
    stderr: "",
  });
  assert.deepEqual(ep, { url: "http://127.0.0.1:8911/", token: "t0k", engineId: "abc" });
});

test("jk's own error is what the user hears", () => {
  assert.throws(
    () => parseEndpoint({ code: 1, stdout: '{"error":"HTTP dashboard is off"}\n', stderr: "" }),
    /HTTP dashboard is off/,
  );
  assert.throws(() => parseEndpoint({ code: 2, stdout: "", stderr: "boom\nreal reason\n" }), /real reason/);
  assert.throws(() => parseEndpoint({ code: 0, stdout: '{"url":"http://x/","token":""}', stderr: "" }), /token/);
});

test("discover runs jk web with JSONL output in the project folder", async () => {
  const calls: unknown[] = [];
  const ep = await discover("/opt/jk/bin/jk", "/work/app", async (bin, args, cwd) => {
    calls.push([bin, args, cwd]);
    return { code: 0, stdout: '{"url":"http://127.0.0.1:8910/","token":"t","engineId":""}', stderr: "" };
  });
  assert.equal(ep.url, "http://127.0.0.1:8910/");
  assert.deepEqual(calls, [["/opt/jk/bin/jk", ["web", "-O", "jsonl", "--with-token"], "/work/app"]]);
});
