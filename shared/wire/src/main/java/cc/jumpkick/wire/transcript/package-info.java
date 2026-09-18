// SPDX-License-Identifier: Apache-2.0
/**
 * The lines of a run's {@code details.jsonl} that both ends write: every line opens with {@code
 * schema}, {@code ts} and {@code type} ({@link cc.jumpkick.wire.transcript.JsonlEnvelope}), and the
 * {@code session-start} header that names the command and who asked is one record whether the CLI
 * opens the transcript or the engine opens it for a detached MCP or web run.
 */
@NullMarked
package cc.jumpkick.wire.transcript;

import org.jspecify.annotations.NullMarked;
