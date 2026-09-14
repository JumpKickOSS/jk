// SPDX-License-Identifier: Apache-2.0

/**
 * The curated integration lane: which integration classes the branch gate re-runs, and the format of the registry that
 * says so.
 *
 * The data lives in `curated-integration.txt` at the checkout root, not here, because both builds read it — this object
 * owns the parse and the budget, the `curated-integration` guard test re-derives the same rules from the same file.
 * Membership is a duplicate execution policy layered over [TestTiers], never a tier of its own: every listed class is
 * `@Tag("integration")` and still runs in the nightly integration tier.
 */
enum class CuratedSurface(val id: String, val what: String) {
    WIRE("wire", "CLI to engine JSONL wire"),
    SPAWN("spawn", "engine spawn, election and takeover"),
    WORKERS("workers", "worker and plugin process launch"),
    WORKSPACE("workspace", "workspace build and test"),
    INSTALL("install", "install and materialize"),
    LOCK("lock", "lockfile and action cache"),
}

/** Whether an entry is registered for the passing path, the refused path, or both. */
enum class CuratedOutcome(val id: String) {
    SUCCESS("success"),
    FAILURE("failure"),
}

/** One registry line. [line] is 1-based so a fault can be reported at the spot the reader has to edit. */
data class CuratedEntry(
    val module: String,
    val fqcn: String,
    val surface: CuratedSurface,
    val outcomes: Set<CuratedOutcome>,
    val why: String,
    val line: Int,
) {
    /** Path of the test source, relative to the checkout root. */
    val sourcePath: String
        get() = CuratedIntegration.moduleDirs.getValue(module) + "/src/test/java/" + fqcn.replace('.', '/') + ".java"
}

/** [CuratedIntegration.parse]'s answer: what parsed, and every line that did not. */
data class CuratedRegistry(val entries: List<CuratedEntry>, val faults: List<String>)

object CuratedIntegration {

    /** The registry, relative to the checkout root. */
    const val REGISTRY = "curated-integration.txt"

    /** The per-module and aggregate Gradle task that runs the lane. */
    const val TASK = "curatedIntegrationTest"

    /**
     * Wall-clock budget for the lane, in minutes, measured end to end on a cold checkout. Exceeding it is a signal to
     * drop or split an entry, not to raise the number: the lane exists because the full tier is too slow to gate on.
     */
    const val BUDGET_MINUTES = 8

    /** Gradle project path to its directory. Only modules that own integration tests can appear in the registry. */
    val moduleDirs = mapOf(":cli" to "clients/cli", ":engine" to "server/engine")

    /**
     * The task names that carry a module's integration environment — worker jars, engine jar, sandbox roots, transport.
     * The lane runs a subset of the tier's classes, so it must run them under the tier's setup or it is testing
     * something else. A module script configures the pair through this list, never `integrationTest` alone.
     */
    val integrationTasks = listOf(TestTiers.INTEGRATION, TASK)

    /**
     * Assertion spellings that mean "this operation was refused". A `failure` claim is checked against these and
     * against [refusalWords]; the point is that the claim can be falsified, not that it is fully verified.
     */
    val failureAssertions =
        listOf(
            "assertThatThrownBy",
            "assertThrows",
            "catchThrowable",
            "assertThatExceptionOfType",
            "isNotEqualTo(0)",
            "isNotZero",
        )

    /** Fragments of a test method name that name a refused path. Matched against lower-cased method names. */
    val refusalWords =
        listOf(
            "fail",
            "refus",
            "reject",
            "denie",
            "invalid",
            "missing",
            "unknown",
            "error",
            "stale",
            "mismatch",
            "conflict",
            "nonzero",
            "orphan",
            "ghost",
            "corrupt",
            "crash",
            "timeout",
            "noop",
            "no_op",
            "not_",
            "never",
            "without",
            "bad_",
            "loses",
        )

    /** Tags that disqualify a class from the lane: it would need the network, a toolchain, or gate nothing. */
    val disqualifyingTags = TestTiers.slowTags.filterNot { it == "integration" }

    /**
     * Parse the registry text. Blank lines and `#` comments are skipped; every other line must carry the five
     * `|`-separated fields the file's own header documents. Faults are collected rather than thrown so one run can
     * report every broken line.
     */
    fun parse(text: String): CuratedRegistry {
        val entries = mutableListOf<CuratedEntry>()
        val faults = mutableListOf<String>()
        text.lines().forEachIndexed { index, raw ->
            val lineNumber = index + 1
            val body = raw.trim()
            if (body.isEmpty() || body.startsWith("#")) return@forEachIndexed
            val fields = body.split("|").map { it.trim() }
            if (fields.size != 5) {
                faults.add(
                    "$REGISTRY:$lineNumber: ${fields.size} fields, expected 5 (module | class | surface | outcomes | why)"
                )
                return@forEachIndexed
            }
            val (module, fqcn, surfaceId, outcomeIds) = fields
            val why = fields[4]
            val surface = CuratedSurface.entries.firstOrNull { it.id == surfaceId }
            val outcomes =
                outcomeIds
                    .split(",")
                    .map { it.trim() }
                    .map { id -> id to CuratedOutcome.entries.firstOrNull { it.id == id } }
            when {
                module !in moduleDirs ->
                    faults.add("$REGISTRY:$lineNumber: unknown module '$module'; the registry knows ${moduleDirs.keys}")
                fqcn.isEmpty() || !fqcn.contains('.') ->
                    faults.add("$REGISTRY:$lineNumber: '$fqcn' is not a fully-qualified class name")
                surface == null ->
                    faults.add(
                        "$REGISTRY:$lineNumber: unknown surface '$surfaceId'; one of ${CuratedSurface.entries.map { it.id }}"
                    )
                outcomes.any { it.second == null } ->
                    faults.add(
                        "$REGISTRY:$lineNumber: unknown outcome(s) ${outcomes.filter { it.second == null }.map { it.first }}; one or both of success, failure"
                    )
                why.length < 20 ->
                    faults.add(
                        "$REGISTRY:$lineNumber: the reason is ${why.length} characters; say what merges broken without this class"
                    )
                else ->
                    entries.add(
                        CuratedEntry(module, fqcn, surface, outcomes.mapNotNull { it.second }.toSet(), why, lineNumber)
                    )
            }
        }
        return CuratedRegistry(entries, faults)
    }

    /** The entries a module owns, or none. */
    fun forModule(text: String, module: String): List<CuratedEntry> = parse(text).entries.filter { it.module == module }
}
