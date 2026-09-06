// SPDX-License-Identifier: Apache-2.0
/**
 * Building the facts index from class files: {@link cc.jumpkick.guard.extract.FactsExtractor} is
 * the one ASM pass (body-visiting; the ABI pass stays {@code SKIP_CODE}), and
 * {@link cc.jumpkick.guard.extract.FactsIndexing} keeps {@code target/incremental/<set>-guard.idx}
 * current with one stat per class file and one read per changed class.
 */
@NullMarked
package cc.jumpkick.guard.extract;

import org.jspecify.annotations.NullMarked;
