// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.buildlogic;

/**
 * BuildPlan splice points for project build-logic tasks ({@code .jk-build/} SPI).
 *
 * <p>Each anchor is a pre/post cut relative to a product <em>stage</em> bucket (see
 * {@code cc.jumpkick.run.BuildStage} in jk-api) — not a second scheduler. Task ordering remains
 * the DAG; anchors only name where SPI tasks are injected.
 *
 * <p>{@code jk.toml} stays data-only — anchors are chosen in Java, not the manifest.
 *
 * <p>This module is dependency-free (plugin SPI floor); stage is carried as a wire name that
 * matches {@code BuildStage#wireName()}.
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
     * After static resources are copied into classes (default for legacy {@code *Build} mains).
     * Stage wire: {@code compile} (resources ride with the compile strip).
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
