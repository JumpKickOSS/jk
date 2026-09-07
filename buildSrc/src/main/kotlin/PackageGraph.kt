// SPDX-License-Identifier: Apache-2.0

import java.io.File

/**
 * The production package graph, scanned once and shared by every guard that reasons about package structure — ownership
 * (G66) and the cycle band (G67).
 *
 * <p>One scope definition on purpose. Two guards deriving "the production packages" separately is two things to drift,
 * and a rule whose corpus quietly differs from its sibling's reports two counts for one tree. The jk side (the
 * `one-module-per-package` and `package-cycles` rules in `jk-guards.toml`) cannot read `buildSrc`, so it names the same
 * scope by hand; both sides name the tiers and the one excluded tree explicitly so a reviewer can diff them.
 */
object PackageGraph {

    /** The four product tiers. Everything else in the checkout is not a module source root. */
    val tiers = listOf("shared", "server", "clients", "plugins")

    /**
     * Module trees inside a tier that are not part of jk's own vocabulary, with the reason. The self-hosted gate
     * excludes the same set from `moduleDirs`.
     */
    val outsideTree =
        mapOf(
            "clients/intellij" to
                "a standalone IntelliJ plugin build that must never see a jk jar, so jk's own" +
                    " vocabulary rules do not reach it — it declares cc.jumpkick.idea and nothing else does"
        )

    /**
     * Corpus floors. Deliberately below the measurement so ordinary churn does not false-fail, while a broken glob or a
     * parser that stops matching lands far under them — the difference between a green result and a blind one.
     */
    const val MIN_SOURCES = 1_200
    const val MIN_PACKAGES = 80
    const val MIN_ROOTS = 25
    const val MIN_EDGES = 100

    /** What the floors were measured against, quoted in every failure message. */
    const val MEASURED = "measured at landing: 1,432 main sources, 95 packages, 29 module roots, 195 package edges"

    private val packageDecl = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;""")

    /** First-party imports, static and plain alike — the only edges that can close a cycle here. */
    private val firstPartyImport = Regex("""(?m)^\s*import\s+(?:static\s+)?(cc\.jumpkick\.[\w.]*?)\s*;""")

    /** One production source: which module declares it, which package it is in, and its text. */
    data class Source(val module: String, val pkg: String, val rel: String, val text: String)

    /** Every production source under the four tiers, minus [outsideTree]. */
    fun scan(repoRoot: File): List<Source> {
        val out = mutableListOf<Source>()
        tiers.forEach { tier ->
            File(repoRoot, tier).listFiles()?.sortedBy(File::getName)?.forEach { module ->
                val moduleRel = "$tier/${module.name}"
                if (moduleRel in outsideTree) return@forEach
                val root = File(module, "src/main/java")
                if (!root.isDirectory) return@forEach
                // Trees.regularFiles, not walkTopDown: build logic never follows a symlink into a
                // directory (G65), and a stable JDK pointer inside a test home is exactly such a link.
                Trees.regularFiles(root)
                    .filter { it.extension == "java" }
                    .sortedBy { it.path }
                    .forEach { file ->
                        val text = file.readText()
                        val pkg = packageDecl.find(text)?.groupValues?.get(1) ?: return@forEach
                        out.add(Source(moduleRel, pkg, file.relativeTo(repoRoot).invariantSeparatorsPath, text))
                    }
            }
        }
        return out
    }

    /** Distinct module source roots in [sources]. */
    fun modules(sources: List<Source>): Set<String> = sources.map(Source::module).toSortedSet()

    /** Package to the set of modules declaring it. A package with two owners is a split package. */
    fun owners(sources: List<Source>): Map<String, Set<String>> {
        val out = sortedMapOf<String, MutableSet<String>>()
        sources.forEach { out.getOrPut(it.pkg) { sortedSetOf() }.add(it.module) }
        return out
    }

    /**
     * Per module, the package-to-package edge set its own sources carry.
     *
     * <p>An import resolves to the **longest declared-package prefix within the same module**. That is what puts a
     * static import (`cc.jumpkick.plugin.protocol.PluginProtocol.T`) and a nested type on the package that actually
     * declares them rather than on an invented parent, and it drops every import that leaves the module — a
     * cross-module edge cannot close a cycle the compiler would already reject.
     */
    fun edges(sources: List<Source>): Map<String, Map<String, Set<String>>> {
        val byModule = sources.groupBy(Source::module)
        val out = sortedMapOf<String, Map<String, Set<String>>>()
        byModule.forEach { (module, moduleSources) ->
            val declared = moduleSources.map(Source::pkg).toSortedSet()
            val edges = sortedMapOf<String, MutableSet<String>>()
            moduleSources.forEach { source ->
                firstPartyImport.findAll(source.text).forEach { match ->
                    val imported = match.groupValues[1]
                    val target =
                        declared.filter { imported == it || imported.startsWith("$it.") }.maxByOrNull { it.length }
                    if (target != null && target != source.pkg) {
                        edges.getOrPut(source.pkg) { sortedSetOf() }.add(target)
                    }
                }
            }
            out[module] = edges
        }
        return out
    }

    /** Total resolved edges across every module — the self-fail arm's signal that resolution works. */
    fun edgeCount(edges: Map<String, Map<String, Set<String>>>): Int =
        edges.values.sumOf { module -> module.values.sumOf { it.size } }

    /**
     * Non-trivial strongly-connected components, largest first (Tarjan, iterative so a 15-node component cannot
     * overflow a stack). A component of one is not a cycle; a self-edge is impossible here because an import inside its
     * own package resolves to itself and is dropped.
     */
    fun components(nodes: Set<String>, edges: Map<String, Set<String>>): List<List<String>> {
        var counter = 0
        val index = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val found = mutableListOf<List<String>>()

        nodes.sorted().forEach { start ->
            if (start in index) return@forEach
            val work = ArrayDeque<Pair<String, Iterator<String>>>()
            index[start] = counter
            low[start] = counter
            counter++
            stack.addLast(start)
            onStack.add(start)
            work.addLast(start to (edges[start] ?: emptySet()).sorted().iterator())
            while (work.isNotEmpty()) {
                val (node, iterator) = work.last()
                var descended = false
                while (iterator.hasNext()) {
                    val next = iterator.next()
                    if (next !in index) {
                        index[next] = counter
                        low[next] = counter
                        counter++
                        stack.addLast(next)
                        onStack.add(next)
                        work.addLast(next to (edges[next] ?: emptySet()).sorted().iterator())
                        descended = true
                        break
                    } else if (next in onStack) {
                        low[node] = minOf(low.getValue(node), index.getValue(next))
                    }
                }
                if (descended) continue
                work.removeLast()
                work.lastOrNull()?.let { (parent, _) -> low[parent] = minOf(low.getValue(parent), low.getValue(node)) }
                if (low.getValue(node) == index.getValue(node)) {
                    val component = mutableListOf<String>()
                    while (true) {
                        val popped = stack.removeLast()
                        onStack.remove(popped)
                        component.add(popped)
                        if (popped == node) break
                    }
                    if (component.size > 1) found.add(component.sorted())
                }
            }
        }
        return found.sortedWith(compareByDescending<List<String>> { it.size }.thenBy { it.first() })
    }

    /** Packages of [module] sitting in a cycle, and the components they form. */
    fun cyclesIn(
        module: String,
        edges: Map<String, Map<String, Set<String>>>,
        sources: List<Source>,
    ): List<List<String>> {
        val declared = sources.filter { it.module == module }.map(Source::pkg).toSortedSet()
        return components(declared, edges[module] ?: emptyMap())
    }
}
