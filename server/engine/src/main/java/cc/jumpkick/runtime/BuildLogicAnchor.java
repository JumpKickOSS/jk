// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.BuildStage;

/**
 * BuildPlan splice points for project {@code .jk/} stem-script tasks.
 *
 * <p>Each anchor is a pre/post cut relative to a product <em>stage</em> bucket (see
 * {@code cc.jumpkick.run.BuildStage}) — not a second scheduler. Task ordering remains the DAG;
 * anchors only name where scripts are injected.
 *
 * <p>{@code jk.toml} stays data-only — the script's stem chooses the anchor, not the manifest.
 */
public enum BuildLogicAnchor {
    /**
     * Before main language compile (java/kotlin/groovy). Use for codegen that must land as
     * sources before {@code compile-*}. Product stage wire: {@code generate}.
     */
    BEFORE_COMPILE(BuildStage.GENERATE),
    /** After main sources are compiled (and mixed modules assembled). Stage wire: {@code compile}. */
    AFTER_COMPILE(BuildStage.COMPILE),
    /**
     * After static resources are copied into classes. Stage wire: {@code compile} (resources ride
     * with the compile strip).
     */
    AFTER_RESOURCES(BuildStage.COMPILE),
    /** Immediately before packaging the jar / image. Stage wire: {@code package}. */
    BEFORE_PACKAGE(BuildStage.PACKAGE),

    /**
     * The workspace root's own always-on anchor: after every member module has finished building.
     * One of two root-scoped anchors ({@link #GUARD} is the other); no member may use either.
     *
     * <p>The four above are cuts relative to a compile that a sourceless root does not have —
     * there is nothing for {@code before-compile} to be before. This one is defined by the
     * workspace instead of by a stage, which is what a workspace-wide step or check actually
     * wants: every member's sources and outputs are on disk when it runs.
     *
     * <p>Stage wire {@code package}: it is the last thing in the build, and the fold a user reads
     * has no bucket further right.
     */
    AFTER_BUILD(BuildStage.PACKAGE),

    /**
     * Share-the-commit checks bound to {@code --guard} / {@code --scripts-only}. Same root-only
     * shape as {@link #AFTER_BUILD} (once per graph, whole-tree cache key) but it does not run on
     * an inner {@code jk test} / {@code jk build}. Legal at a workspace root or a standalone
     * project; illegal in a workspace member.
     */
    GUARD(BuildStage.PACKAGE);

    private final BuildStage stage;

    BuildLogicAnchor(BuildStage stage) {
        this.stage = stage;
    }

    /** Whether this anchor belongs to the workspace root rather than to a module. */
    public boolean workspaceScoped() {
        return this == AFTER_BUILD || this == GUARD;
    }

    /** The stage the anchor's task carries, for UI fold, ETA and the plan's stage ordering. */
    public BuildStage stage() {
        return stage;
    }
}
