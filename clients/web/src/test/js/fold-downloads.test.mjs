// SPDX-License-Identifier: Apache-2.0
// The run's downloads on a card: the terminal's Download block, folded from the `downloads` event.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { downloadsOf, finish, foldEvent, start } from './fold-harness.mjs';

const frame = (jid, data) => ({ type: 'downloads', data: { jid, ...data } });

test('a downloads frame puts the block on its card while bytes move', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, frame(1, {
    settled: 12, planned: 183, files: 12, bytes: 4096, inflight: 2,
    rows: [{ label: 'g:a:1', read: 512, total: 1024 }, { label: 'g:b:1', read: 10, total: -1 }],
  }));
  const dl = cards[0].downloads;
  assert.equal(dl.settled, 12);
  assert.equal(dl.planned, 183);
  assert.equal(dl.rows.length, 2);
  assert.equal(dl.rows[1].total, -1);
});

test('a frame with nothing streaming and the set settled clears the block', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, frame(1, { settled: 1, planned: 2, files: 1, bytes: 10, inflight: 1, rows: [] }));
  foldEvent(cards, frame(1, { settled: 2, planned: 2, files: 2, bytes: 20, inflight: 0, rows: [] }));
  assert.equal(cards[0].downloads, null);
});

test('a run that finishes drops the block even when its last frame was shed', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, frame(1, { settled: 1, planned: 9, files: 1, bytes: 10, inflight: 3, rows: [] }));
  foldEvent(cards, finish(1, { success: true }));
  assert.equal(cards[0].downloads, null);
});

test('nothing moved is nothing shown, as the terminal rule says', () => {
  assert.equal(downloadsOf({ settled: 0, planned: 183, files: 0, bytes: 0, inflight: 0 }), null);
});
