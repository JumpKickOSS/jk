// SPDX-License-Identifier: Apache-2.0
/**
 * The lines of {@code jk --output jsonl} and {@code details.jsonl}, one record each: every line
 * opens with {@code schema}, {@code ts} and {@code type} ({@link cc.jumpkick.wire.transcript.JsonlEnvelope}),
 * encodes through {@code JsonFields} and decodes through the {@code Jsonl} readers, so the shape an
 * agent reads is the shape the CLI wrote, in one file per line kind.
 */
@NullMarked
package cc.jumpkick.cli.run.jsonl;

import org.jspecify.annotations.NullMarked;
