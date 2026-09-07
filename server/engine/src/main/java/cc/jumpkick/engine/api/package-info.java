// SPDX-License-Identifier: Apache-2.0
/**
 * The engine's leaf types: what its sub-packages (jobs, journal, http, verbs, listen) share without
 * reaching back into the {@code engine} root — the JSON and wire writers, the in-flight build table,
 * the history kinds and fingerprints, the coalescing listeners, the lock floor, the live snapshot and
 * the SSE event surface. Nothing here imports another engine package; that is the point of it.
 */
@NullMarked
package cc.jumpkick.engine.api;

import org.jspecify.annotations.NullMarked;
