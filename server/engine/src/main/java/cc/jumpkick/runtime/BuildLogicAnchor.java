// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

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
    BEFORE_COMPILE("generate"),
    /** After main sources are compiled (and mixed modules assembled). Stage wire: {@code compile}. */
    AFTER_COMPILE("compile"),
    /**
     * After static resources are copied into classes. Stage wire: {@code compile} (resources ride
     * with the compile strip).
     */
    AFTER_RESOURCES("compile"),
    /** Immediately before packaging the jar / image. Stage wire: {@code package}. */
    BEFORE_PACKAGE("package");

    private final String stageWire;

    BuildLogicAnchor(String stageWire) {
        this.stageWire = stageWire;
    }

    /**
     * Product stage wire name for UI fold / ETA ({@code generate}, {@code compile}, {@code package}).
     * Same spelling as {@code BuildStage#wireName()}.
     */
    public String stageWireName() {
        return stageWire;
    }
}
