// SPDX-License-Identifier: Apache-2.0
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { escapeHtml } from '../../main/resources/web/format.js';

test('escapeHtml leaves no markup and no quote of either kind', () => {
  assert.equal(
    escapeHtml(`<img src=x onerror='a("b")'> & co`),
    '&lt;img src=x onerror=&#39;a(&quot;b&quot;)&#39;&gt; &amp; co',
  );
  assert.equal(escapeHtml(42), '42');
});
