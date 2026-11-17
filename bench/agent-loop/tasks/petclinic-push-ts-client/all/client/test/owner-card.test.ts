import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cardLine } from '../src/owner-card.ts';

test('the card line shows the owner and their phone number', async () => {
  assert.equal(await cardLine(1), 'George Franklin, tel. 6085551023 (1 pet)');
});
