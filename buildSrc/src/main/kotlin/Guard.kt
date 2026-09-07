// SPDX-License-Identifier: Apache-2.0

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/** Where a guard is registered and what it attaches to. */
enum class GuardHome {
    /** Every Java module, via jk.verification. */
    MODULE,
    /** Plugin modules, via jk.plugin-conventions. */
    PLUGIN_MODULE,
    /** Root build.gradle.kts. */
    ROOT,
    /** A specific module's build.gradle.kts. */
    MODULE_OWNED,
    /** Enforced as a JUnit test (parity exception). */
    TEST,
    /** The self-hosted after-build gate only. */
    JK_ONLY,
    /** Letter retired into another task. */
    FOLDED,
    /** Never allocated; not reusable. */
    NEVER,
}

/** Lifecycle tasks a guard may hang off. */
enum class GuardAttach(val taskName: String) {
    CHECK("check"),
    JAR("jar"),
    TEST("test"),
}

/**
 * One enforcement site. A letter may have a published table row and a separate Gradle task; [inTable] is true on
 * exactly one site per letter.
 */
data class GuardSpec(
    val letter: Int?,
    val task: String,
    val rule: String,
    val form: String,
    val home: GuardHome,
    val ownerPath: String? = null,
    val attach: Set<GuardAttach> = setOf(GuardAttach.CHECK, GuardAttach.JAR),
    val inFastGate: Boolean = true,
    val gradleLetter: Boolean = true,
    val inTable: Boolean = letter != null,
    val tableTask: String? = null,
    val mavenPublishOnly: Boolean = false,
    val description: String = "",
    /**
     * The `[guards.<id>]` table in `jk-guards.toml` that enforces this letter on the self-hosted side, once the letter
     * has moved out of `.jk/after-build.kts`. Parity (G51) counts the letter as jk-enforced when the table exists, and
     * fails when the table is missing or unclaimed.
     */
    val ruleId: String? = null,
    /**
     * The engine validation that enforces this letter on the self-hosted side: an invariant of the build model the
     * engine checks in its guard lanes under this reserved code, with no `[guards.<id>]` table and no script block.
     * Parity (G51) counts the letter as jk-enforced.
     */
    val engineCode: String? = null,
) {
    val id: String
        get() = letter?.let { "G$it" } ?: task

    /** Markdown for the published table's task cell. */
    val tableTaskCell: String
        get() = tableTask ?: if (task.isEmpty()) "—" else "`$task`"

    val taskDescription: String
        get() = description.ifEmpty { rule }

    /** Whether this site is a Gradle task [Project.registerGuard] may create. */
    val registers: Boolean
        get() =
            home == GuardHome.MODULE ||
                home == GuardHome.PLUGIN_MODULE ||
                home == GuardHome.ROOT ||
                home == GuardHome.MODULE_OWNED
}

fun Project.registerGuard(spec: GuardSpec, configure: Task.() -> Unit): TaskProvider<Task> {
    require(spec.registers && spec.task.isNotEmpty()) { "${spec.id} does not register a Gradle task" }
    val provider =
        tasks.register(spec.task) {
            configure()
            group = "verification"
            description = spec.taskDescription
        }
    spec.attach.forEach { attachment -> tasks.named(attachment.taskName) { dependsOn(provider) } }
    return provider
}

fun Project.registerGuard(taskName: String, configure: Task.() -> Unit): TaskProvider<Task> =
    registerGuard(Guards.named(taskName), configure)
