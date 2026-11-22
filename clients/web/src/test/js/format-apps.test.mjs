// SPDX-License-Identifier: Apache-2.0
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runningApps } from '../../main/resources/web/format.js';

test('each running app names where it answers and the session that started it', () => {
  assert.deepEqual(runningApps(null), []);
  assert.deepEqual(runningApps({ apps: [] }), []);
  assert.deepEqual(
    runningApps({
      apps: [
        { dir: '/w/shop', name: 'shop', pid: 4242, url: 'http://localhost:8080', startedAt: 1, startedBy: 'claude-code 91be' },
        { dir: '/w/api', name: 'api', pid: 4243, url: '', startedAt: 2, startedBy: 'codex 1a2b' },
      ],
    }),
    [
      { key: '4242', name: 'shop', dir: '/w/shop', url: 'http://localhost:8080', address: 'localhost:8080', startedBy: 'claude-code 91be' },
      { key: '4243', name: 'api', dir: '/w/api', url: '', address: 'starting', startedBy: 'codex 1a2b' },
    ],
  );
});
