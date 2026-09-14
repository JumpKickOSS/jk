// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult

/**
 * Says which test tiers this build actually ran, and reads their counts out of `TEST-*.xml` rather than believing the
 * task outcome.
 *
 * Why it exists: a `checkAll` run reported nine failures when there were ten, because `:cli:test` was `UP-TO-DATE` and
 * never executed — a red tier was invisible in the gate that was supposed to be the merge bar. A later run of the same
 * gate finished in three seconds with 899 of 928 tasks up-to-date and results served from the build cache, and printed
 * `BUILD SUCCESSFUL`. Both are correct Gradle behaviour and both are useless as evidence, because the thing a human
 * reads off a gate is "the tests passed", and what the gate said was "nothing needed doing".
 *
 * A cached pass is a fine answer to "is this input already known good". It is not an answer to "did this tier run", and
 * the two are indistinguishable in the default output.
 *
 * Mechanics: a [BuildService] listening to task completion, which is the outcome the build itself saw — not a
 * re-derivation. `UP-TO-DATE` and `FROM-CACHE` both mean the task body did not run, so both are reported as not
 * executed. Counts come from the XML because a tier can execute, report `BUILD SUCCESSFUL` for the task, and still have
 * been filtered down to zero tests.
 */
abstract class GateExecutionReport :
    BuildService<GateExecutionReport.Params>, OperationCompletionListener, AutoCloseable {

    interface Params : BuildServiceParameters {
        /** Tree root: where `**`/`build/test-results/<tier>` are found. */
        val rootDir: DirectoryProperty
        /** Task names that count as test tiers, e.g. `test`, `integrationTest`. */
        val tierTasks: SetProperty<String>
    }

    private enum class Ran {
        EXECUTED,
        NOT_EXECUTED,
        SKIPPED,
        FAILED,
    }

    private val outcomes = ConcurrentHashMap<String, Ran>()

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val path = event.descriptor.taskPath
        if (path.substringAfterLast(':') !in parameters.tierTasks.get()) return
        outcomes[path] =
            when (val result = event.result) {
                is TaskFailureResult -> Ran.FAILED
                is TaskSkippedResult -> Ran.SKIPPED
                is TaskSuccessResult -> if (result.isUpToDate || result.isFromCache) Ran.NOT_EXECUTED else Ran.EXECUTED
                else -> Ran.NOT_EXECUTED
            }
    }

    override fun close() {
        if (outcomes.isEmpty()) return
        val counts = resultsByModuleTier()
        val lines = mutableListOf<String>()
        var quiet = 0
        outcomes.toSortedMap().forEach { (path, ran) ->
            val c = counts[GateKeys.moduleTier(path)]
            val tally =
                if (c == null) "no TEST-*.xml found"
                else "${c.tests} tests, ${c.failures} failures, ${c.errors} errors, ${c.skipped} skipped"
            when (ran) {
                Ran.EXECUTED -> lines.add("  ran          $path — $tally")
                Ran.FAILED -> lines.add("  FAILED       $path — $tally")
                Ran.SKIPPED -> {
                    quiet++
                    lines.add("  skipped      $path — did not run")
                }
                Ran.NOT_EXECUTED -> {
                    quiet++
                    lines.add("  NOT EXECUTED $path — up-to-date or from cache; $tally is from an earlier run")
                }
            }
        }
        println("\njk gate — what actually ran:")
        lines.forEach { println(it) }
        if (quiet > 0) {
            println(
                "  ^ $quiet tier(s) did not execute in this build. Their counts above are" +
                    " historical, not evidence about the current tree."
            )
        }
    }

    private data class Tally(val tests: Int, val failures: Int, val errors: Int, val skipped: Int)

    /**
     * Sum every `TEST-*.xml`, keyed `<module dir leaf>/<tier>`. Keyed per module, not per tier: a single bucket per
     * tier sums every module's results and then reports that total against each task, which reads as every tier having
     * run every test in the tree.
     */
    private fun resultsByModuleTier(): Map<String, Tally> {
        val root = parameters.rootDir.get().asFile
        val acc = mutableMapOf<String, IntArray>()
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (name == ".git") return FileVisitResult.SKIP_SUBTREE
                    if (dir.parent?.fileName?.toString() != "test-results") return FileVisitResult.CONTINUE
                    // <module>/build/test-results/<tier>
                    val tierDir = dir.toFile()
                    val module = tierDir.parentFile?.parentFile?.parentFile?.name ?: return FileVisitResult.SKIP_SUBTREE
                    val a = acc.getOrPut("$module/$name") { IntArray(4) }
                    tierDir
                        .listFiles { f: File -> f.name.startsWith("TEST-") && f.extension == "xml" }
                        ?.forEach { xml -> addSuite(xml, a) }
                    return FileVisitResult.SKIP_SUBTREE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return acc.mapValues { (_, a) -> Tally(a[0], a[1], a[2], a[3]) }
    }

    private fun addSuite(xml: File, into: IntArray) {
        try {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
            val e = doc.documentElement ?: return
            into[0] += e.getAttribute("tests").toIntOrNull() ?: 0
            into[1] += e.getAttribute("failures").toIntOrNull() ?: 0
            into[2] += e.getAttribute("errors").toIntOrNull() ?: 0
            into[3] += e.getAttribute("skipped").toIntOrNull() ?: 0
        } catch (_: Exception) {
            // A half-written or malformed report is not this reporter's business to fail on.
        }
    }
}

/**
 * The key that joins a task path to the directory its results land in.
 *
 * Split out so the one assumption in this reporter is testable: that a module's directory leaf matches its Gradle
 * project name. That holds across this tree — `:core` is `shared/core`, `:cli` is `clients/cli`, `:toolchain-jdk` is
 * `shared/toolchain-jdk` — and where it ever stops holding the lookup misses and the report says "no TEST-*.xml found"
 * rather than attributing another module's numbers, which is the failure mode that matters.
 */
internal object GateKeys {
    fun moduleTier(taskPath: String): String {
        val parts = taskPath.trim(':').split(':')
        return if (parts.size >= 2) "${parts[parts.size - 2]}/${parts.last()}" else taskPath
    }
}
