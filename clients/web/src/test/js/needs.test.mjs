// SPDX-License-Identifier: Apache-2.0
// The console's Needs-you banner (needs.js): open decisions oldest first, folded from the SSE
// `decision` frame, and absent on an engine with no decision queue.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const spa = (name) => import(pathToFileURL(path.join(process.env.JK_APP_DIR, name)));
const { bannerRows, fetchDecisions, foldDecision, isOpen, openDecisions, waitingSessions } = await spa('needs.js');

const d = (id, openedAt, over = {}) => ({ id, question: 'Move 2 pins across a minor?', session: 'mcp-91be', episode: 'mcp-91be.1', openedAt, deadline: openedAt + 1_800_000, ...over });

test('open decisions only, oldest first, from either body shape', () => {
  const body = { decisions: [d('b', 20), d('a', 10), d('c', 5, { resolution: { answer: 'accept' } }), d('e', 1, { state: 'expired' })] };
  assert.deepEqual(openDecisions(body).map((x) => x.id), ['a', 'b']);
  assert.deepEqual(openDecisions([d('z', 3)]).map((x) => x.id), ['z']);
  assert.deepEqual(openDecisions(null), []);
  assert.equal(isOpen({}), false);
});

test('the decision frame opens and resolves a row', () => {
  let list = [];
  list = foldDecision(list, { change: 'opened', decision: d('b', 20) });
  list = foldDecision(list, { change: 'opened', decision: d('a', 10) });
  assert.deepEqual(list.map((x) => x.id), ['a', 'b']);
  list = foldDecision(list, { change: 'resolved', decision: d('a', 10) });
  assert.deepEqual(list.map((x) => x.id), ['b']);
  assert.equal(foldDecision(list, {}), list, 'a frame naming no decision changes nothing');
});

test('an engine with no decision queue leaves the banner absent', async () => {
  const missing = await fetchDecisions(() => Promise.reject({ status: 404 }));
  assert.deepEqual(missing, { available: false, decisions: [] });
  const there = await fetchDecisions(() => Promise.resolve({ decisions: [d('a', 1)] }));
  assert.equal(there.available, true);
  assert.equal(there.decisions.length, 1);
  await assert.rejects(fetchDecisions(() => Promise.reject({ status: 401 })), (e) => e.status === 401, 'a 401 still reaches the auth gate');
});

test('a banner row names who asks and links the decision, whose dialog answers it', () => {
  const sessions = [{ id: 'mcp-91be', origin: 'mcp', label: 'codex 91be' }];
  const [row] = bannerRows([d('dec 1', 10)], sessions);
  assert.equal(row.askedBy, 'codex 91be');
  assert.equal(row.href, '#decision/dec%201');
  assert.equal('command' in row, false, 'nothing sends the person to a terminal');
  assert.equal(bannerRows([d('x', 1, { session: 'gone' })], sessions)[0].askedBy, 'gone');
  assert.deepEqual([...waitingSessions([d('a', 1), d('b', 2, { resolution: {} })])], ['mcp-91be']);
});
