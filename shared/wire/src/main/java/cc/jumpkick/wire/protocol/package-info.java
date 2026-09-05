// SPDX-License-Identifier: Apache-2.0

/**
 * JSONL job and event records for the client–engine socket protocol.
 *
 * <p>A request component whose compact constructor substitutes a default — an empty list, an empty
 * map, {@code TestSelection.DEFAULT} — is declared non-null, because that is what the accessor
 * returns for every instance that exists. The constructor still tolerates a null argument, so an
 * omitted wire field and an unchecked caller both land on the default rather than on a throw; the
 * type states the guarantee callers actually get. Components normalized <em>to</em> null keep
 * {@code @Nullable}: for those, absent is the value.
 */
@NullMarked
package cc.jumpkick.wire.protocol;

import org.jspecify.annotations.NullMarked;
