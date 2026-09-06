// SPDX-License-Identifier: Apache-2.0
/**
 * Building the facts index from class files: {@link cc.jumpkick.guard.extract.FactsExtractor} is
 * the one ASM pass (body-visiting; the ABI pass stays {@code SKIP_CODE}), and
 * {@link cc.jumpkick.guard.extract.FactsIndexing} keeps {@code target/incremental/<set>-guard.idx}
 * current with one stat per class file and one read per changed class.
 *
 * <p>The index is about class files, not languages, so a Kotlin or Groovy module yields the same
 * sections as a Java one from the same {@code target/classes} directory. What the class file holds
 * is what a rule can see: a Kotlin {@code inline fun} disappears as a call site and its body's calls
 * appear in every caller instead (a {@code forbid} on {@code System#getProperty} fires in {@code
 * main} for an inlined {@code arch()}), file facades are {@code FooKt}, interface defaults live in
 * {@code Foo$DefaultImpls}, and {@code kotlin.Metadata} is an annotation like any other. Groovy's
 * dynamic dispatch goes through call-site arrays, so a {@code System.getProperty} in a dynamic class
 * is not a call the index can attribute; {@code @CompileStatic} makes it an ordinary
 * {@code invokestatic} and the rule fires. Scala has no compile task here yet.
 */
@NullMarked
package cc.jumpkick.guard.extract;

import org.jspecify.annotations.NullMarked;
