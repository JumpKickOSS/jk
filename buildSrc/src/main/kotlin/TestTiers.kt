// SPDX-License-Identifier: Apache-2.0

/**
 * One tier per JUnit tag, and the table that says which.
 *
 * A `@Tag` is a routing decision: it moves a test out of the fast tier and into some other task. Before JK-2447 the
 * routing lived in two half-tables that nobody could see together — `test` excluded four tags, `integrationTest`
 * re-included two of them — so `@Tag("bench")` was excluded from both and `ForkedJavacAotBenchTest` was run by no task
 * at all, while `@Tag("network")` reached the merge gate only by accident, because the one class carrying it also
 * carried `slow`.
 *
 * This is that table, as one object, and it is [total]: **every** subset of [slowTags] is run by exactly one tier, and
 * any tag this table has never heard of falls through to [UNIT]. The build's `useJUnitPlatform { }` filters are
 * generated from it and guard G23 re-derives the partition from it, so the tier config and the guard cannot drift apart
 * — there is only one copy.
 */
data class TestTier(
    /** The Gradle task that runs this tier. */
    val task: String,
    /** `includeTags`. Empty means "everything not excluded", which is what the fast tier is. */
    val include: Set<String>,
    /** `excludeTags`. */
    val exclude: Set<String>,
) {
    /**
     * JUnit's own tag semantics for this tier's filters: an element carrying [tags] runs when the include set is empty
     * or intersects, and the exclude set does not.
     */
    fun runs(tags: Set<String>): Boolean =
        (include.isEmpty() || tags.any { it in include }) && tags.none { it in exclude }
}

object TestTiers {

    /** Fast tier: untagged tests only. The PR gate (`ci.yml`) and the floor of `check`. */
    const val UNIT = "test"

    /** Engine / e2e / worker suites. Part of `checkAll`, the documented pre-merge bar. */
    const val INTEGRATION = "integrationTest"

    /**
     * Tests that talk to a real remote. **Deliberately not part of `checkAll`**: Sonatype enforces a per-IP quota on
     * Maven Central and this repo has a documented history of 429s from it (JK-1277), so a merge gate that needs the
     * network is a gate that fails for reasons the change did not cause. Runs nightly (`ci-nightly.yml`) and on demand.
     */
    const val NETWORK = "networkTest"

    /**
     * Framework and language end-to-end suites: does an Android / Grails / Scala / KSP / Protobuf
     * project still build all the way through. **Deliberately not part of `checkAll`** as of
     * JK-1023.
     *
     * Measured 2026-08-26: 19 classes, 28 tests, 426s of `integrationTest`'s 1419s — 30% of the
     * gating tier for **15 seconds per test**, the worst ratio in the tree. What they assert breaks
     * when a plugin or a toolchain moves, not when engine or CLI core does, so the average change
     * pays that cost for coverage it cannot affect.
     *
     * The tag already existed and already meant this; before JK-1023 it simply routed to
     * [INTEGRATION] alongside `integration`, which is what put it on the merge bar.
     */
    const val SLOW = "slowTest"

    /**
     * Microbenchmarks. Not part of any gate — they print medians and assert nothing about deltas, so gating on them
     * would gate on CI noise. They still have to *run* somewhere or they rot: before JK-2447 this tier did not exist
     * and `@Tag("bench")` was excluded from both tasks.
     */
    const val BENCH = "benchTest"

    /**
     * Tags the fast tier refuses. Each one is included by exactly one tier in [all] — that is the invariant guard G23's
     * first arm proves, exhaustively, over every subset.
     */
    val slowTags = listOf("integration", "slow", "network", "bench")

    /** The tiers, in the order a developer meets them. */
    val all =
        listOf(
            TestTier(UNIT, include = emptySet(), exclude = slowTags.toSet()),
            TestTier(INTEGRATION, include = setOf("integration"), exclude = setOf("slow", "network", "bench")),
            TestTier(SLOW, include = setOf("slow"), exclude = setOf("network", "bench")),
            TestTier(NETWORK, include = setOf("network"), exclude = setOf("bench")),
            TestTier(BENCH, include = setOf("bench"), exclude = emptySet()),
        )

    /** The tiers `checkAll` runs. [SLOW], [NETWORK] and [BENCH] are absent on purpose; see their docs. */
    val gating = setOf(UNIT, INTEGRATION)

    /** The tasks that would run an element carrying exactly [tags]. Invariant: exactly one. */
    fun tiersFor(tags: Set<String>): List<String> = all.filter { it.runs(tags) }.map { it.task }

    /**
     * Every tag this table mentions at all — [slowTags] plus anything a tier includes or excludes. A tier that filters
     * on a tag missing from [slowTags] is itself a defect (the fast tier would run that test too), so the vocabulary is
     * collected from the whole table rather than assumed to be [slowTags].
     */
    val vocabulary: List<String> = (slowTags + all.flatMap { it.include + it.exclude }).distinct().sorted()

    /**
     * Every subset of [vocabulary] that is **not** run by exactly one tier, rendered for an error message. Empty means
     * the table is a total partition of the tags it knows about.
     *
     * Exhaustive rather than sampled: there are only `2^vocabulary.size` subsets (16 today), so there is no reason to
     * check a chosen few and hope. This is the arm that can catch the defect on the day a tag is added, before any test
     * carries it.
     */
    fun partitionFaults(): List<String> {
        val faults = mutableListOf<String>()
        for (mask in 0 until (1 shl vocabulary.size)) {
            val tags = vocabulary.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
            val tiers = tiersFor(tags)
            if (tiers.size != 1) {
                val what = if (tiers.isEmpty()) "no task runs it" else "run by ${tiers.size} tasks: $tiers"
                faults.add("  @Tag" + (tags.ifEmpty { setOf("(untagged)") }) + " — $what")
            }
        }
        return faults
    }
}
