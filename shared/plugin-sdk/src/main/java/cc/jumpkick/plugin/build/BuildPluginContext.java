// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * The registration surface handed to {@link BuildPlugin#register}. Registration code may branch on
 * {@link #config()} / {@link #project()} — that is exactly where conditional shape belongs (the
 * manifest's predicate set is deliberately closed; anything richer is code, per the plan's
 * anti-DSL-creep rule). Registrations are recorded, not executed: the engine drives execution
 * later, per step, with resolved inputs.
 */
public interface BuildPluginContext {

    /** The parsed, schema-validated table this plugin owns. */
    cc.jumpkick.plugin.PluginConfig config();

    /** Read-only project facts (coords, resolved main, capability flags). */
    ProjectFacts project();

    /** Register a generated-artifact task. */
    void task(TaskSpec spec);

    /** @deprecated use {@link #task(TaskSpec)} */
    @Deprecated
    default void step(TaskSpec spec) { task(spec); }

    /** Register the main-artifact packager (plan §3.3) — at most one per project. */
    void packaging(PackagerSpec spec);

    /** Register a plugin command ({@code jk <name>}, plan row 11) — worker-executed. */
    void command(PluginCommandSpec spec);

    // ---- reserved hooks -----------------------------------------------------------------
    // run()/nativeImage() shaping is a LATER step (jk dev's hot-reload hooks) — the hooks and
    // their shape records land WITH the feature; a published method that always throws is
    // a lie in the contract. Import is manifest data ({@code [[import.gradle-plugin]]}).
    // Giter8 templates live under {@code templates/<lang>/<kind>/} in the plugin jar.

}
