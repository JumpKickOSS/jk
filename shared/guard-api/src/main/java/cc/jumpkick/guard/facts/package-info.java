// SPDX-License-Identifier: Apache-2.0
/**
 * The facts index: what the compile output says about a module, read once per changed class and
 * queried by every bytecode rule and every guard test. {@link cc.jumpkick.guard.facts.FactsIndex}
 * is the queryable model; {@link cc.jumpkick.guard.facts.FactsFormat} is its on-disk shape (compact
 * binary with a string table, header readable without the body). Extraction lives engine-side; this
 * package has no ASM dependency so a guard test's JVM can read an index without it.
 */
@NullMarked
package cc.jumpkick.guard.facts;

import org.jspecify.annotations.NullMarked;
