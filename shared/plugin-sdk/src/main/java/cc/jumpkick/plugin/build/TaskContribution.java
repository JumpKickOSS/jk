// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.plugin.PluginConfig;

/**
 * Contribution surface for resolve/build/test plugin capabilities. Fronts a single <em>implicit
 * task</em> — named after the plugin unless {@link #named renamed} — so the common plugin declares
 * once: set requires/inputs/outputs/contributions, then {@link #run}. Multi-task plugins (Android)
 * add further tasks with {@link #task}.
 *
 * <p>Declarations are ordinary {@link TaskSpec}s on {@link BuildPluginContext}. Ordering is a
 * requires graph; the engine adds edges from {@code contributes*} (see docs/features/build-plan.md).
 */
public interface TaskContribution {

    /** The plugin's validated config table. */
    PluginConfig config();

    /** Read-only project facts (coords, resolved main, capability flags). */
    ProjectFacts project();

    /** Rename the implicit task (default: the plugin id). */
    TaskContribution named(String name);

    /** Upstream task names that must succeed before this task runs. */
    TaskContribution requires(String... taskNames);

    /**
     * Product stage wire name ({@code generate}, {@code compile}, {@code test}, …). Matches
     * BuildStage; omit to let the engine infer from contributions / name.
     */
    TaskContribution stage(String stageWire);

    /** Declare the implicit task's inputs (fingerprinted into its action key). */
    TaskContribution inputs(In... ins);

    /** Declare output dirs (relative to the task's scratch root). */
    TaskContribution outputs(String... dirs);

    /** Fold a declared output dir into the compiler's source set. */
    TaskContribution contributesSources(String relDir);

    /** Fold a declared output dir into packaging + the native-image classpath as classes. */
    TaskContribution contributesClasses(String relDir);

    /** Fold a declared output dir into packaging + the native-image classpath as resources. */
    TaskContribution contributesResources(String relDir);

    /** Append a declared output dir to the module's test runtime classpath. */
    TaskContribution contributesTestClasspath(String relDir);

    /** Declare a declared output dir as the module's classes-dir replacement (at most one per build). */
    TaskContribution transformsClasses(String relDir);

    /** Provide the implicit task's body and register it. Calling this twice is an error. */
    void run(TaskSpec.Body body);

    /** Register an additional, fully-specified named task. */
    void task(TaskSpec spec);
}
