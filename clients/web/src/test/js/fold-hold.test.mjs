// SPDX-License-Identifier: Apache-2.0
// A build on hold for memory (JK-VYJ4X): hold-start marks the card held, progress while held keeps
// the countdown where the hold found it, and hold-end moves its anchors by the time held.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { foldEvent, start } from './fold-harness.mjs';

const progress = (jid, at, remainingMs) => ({
  type: 'workspace-progress',
  at,
  data: { jid, numerator: 40, denominator: 100, progress: 40, remainingMs, R0: 100_000 },
});

test('a hold marks the card, holds its countdown, and moves its anchors by the time held', () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w'), at: 0 });
  foldEvent(cards, progress(1, 10_000, 60_000));
  foldEvent(cards, { type: 'hold-start', at: 20_000, data: { jid: 1, needBytes: 1, freeBytes: 0 } });
  assert.equal(cards[0].holdSince, 20_000);

  // Progress while held anchors at the hold's start, not at its own arrival.
  foldEvent(cards, progress(1, 30_000, 50_000));
  assert.equal(cards[0].residualAt, 20_000);

  foldEvent(cards, { type: 'hold-end', at: 50_000, data: { jid: 1, heldMs: 30_000 } });
  assert.equal(cards[0].holdSince, null);
  assert.equal(cards[0].residualAt, 50_000);
  assert.equal(cards[0].heldMs, 30_000);
});

test('a hold-end with no hold changes nothing', () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w'), at: 0 });
  foldEvent(cards, progress(1, 10_000, 60_000));
  foldEvent(cards, { type: 'hold-end', at: 20_000, data: { jid: 1, heldMs: 5 } });
  assert.equal(cards[0].residualAt, 10_000);
  assert.equal(cards[0].heldMs, undefined);
});

test('progress with a likely range keeps it as gaps around the residual', () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w'), at: 0 });
  foldEvent(cards, {
    type: 'workspace-progress',
    at: 10_000,
    data: { jid: 1, numerator: 40, denominator: 100, progress: 40, remainingMs: 60_000, R0: 100_000, etaLowMs: 45_000, etaHighMs: 90_000 },
  });
  assert.equal(cards[0].rangeBelowMs, 15_000);
  assert.equal(cards[0].rangeAboveMs, 30_000);
});
