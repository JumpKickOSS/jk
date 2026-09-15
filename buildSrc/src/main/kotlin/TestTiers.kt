// SPDX-License-Identifier: Apache-2.0

/**
 * One tier per JUnit tag, and the table that says which.
 *
 * A `@Tag` routes a test out of the fast tier into exactly one other task. This table is total: every subset of
 * [slowTags] is run by exactly one tier, and any unknown tag falls through to [UNIT]. Build filters and guard G23 both
 * derive from it so they cannot drift.
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

    /** Fast tier: untagged tests only. The floor of `check`. */
    const val UNIT = "test"

    /**
     * Engine / e2e / worker suites. Part of `checkAll`, the bootstrap's widest run; the gate is `jk test --profile
     * integration`.
     */
    const val INTEGRATION = "integrationTest"

    /**
     * Tests that talk to a real remote. **Deliberately not part of `checkAll`**: Sonatype enforces a per-IP quota on
     * Maven Central, so a run that needs the network fails for reasons the change did not cause. Runs on demand
     * (`./gradlew networkTest`).
     */
    const val NETWORK = "networkTest"

    /**
     * Framework and language end-to-end suites (Android / Grails / Scala / KSP / Protobuf). **Deliberately not part of
     * `checkAll`**: they assert plugin/toolchain surfaces, not engine or CLI core, and are too expensive for a routine
     * run.
     */
    const val SLOW = "slowTest"

    /**
     * Microbenchmarks. Not part of any gate — they print medians and assert nothing about deltas, so gating on them
     * would gate on noise. They still have to *run* somewhere or they rot (`./gradlew benchTest`).
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
