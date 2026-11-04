// SPDX-License-Identifier: Apache-2.0
/**
 * The lines of {@code jk -O jsonl} and {@code details.jsonl} that only the CLI writes — the
 * session's bounds, the job binding, the workspace's start, the slowest tests — one record each.
 * Every line opens with {@code schema}, {@code ts} and {@code type} ({@link
 * cc.jumpkick.wire.transcript.JsonlEnvelope}). An event the engine streams has no record here: the
 * console writes its wire record under the same envelope ({@code JsonlShape}), so it is spelled the
 * same on every surface.
 */
@NullMarked
package cc.jumpkick.cli.run.jsonl;

import org.jspecify.annotations.NullMarked;
