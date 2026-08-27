// SPDX-License-Identifier: Apache-2.0

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.build.event.BuildEventsListenerRegistry

/**
 * Wires [GateExecutionReport] to task-completion events for the whole build (JK-1018).
 *
 * A plugin rather than a few lines in the root script because `BuildEventsListenerRegistry` is only available by
 * injection, and doing it here keeps the root build file to a one-line `apply`.
 */
abstract class GateReportPlugin @Inject constructor(private val events: BuildEventsListenerRegistry) : Plugin<Project> {

    override fun apply(target: Project) {
        require(target == target.rootProject) { "GateReportPlugin belongs on the root project" }
        val service =
            target.gradle.sharedServices.registerIfAbsent("gateExecutionReport", GateExecutionReport::class.java) {
                parameters.rootDir.set(target.layout.projectDirectory)
                parameters.tierTasks.set(TestTiers.all.map { it.task }.toSet())
            }
        events.onTaskCompletion(service)
    }
}
