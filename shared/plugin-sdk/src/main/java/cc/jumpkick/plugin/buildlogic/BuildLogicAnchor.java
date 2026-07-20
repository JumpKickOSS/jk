// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.buildlogic;

/**
 * Pipeline splice points for project build-logic tasks ({@code .jk-build/} SPI).
 *
 * <p>{@code jk.toml} stays data-only — anchors are chosen in Java, not the manifest.
 */
public enum BuildLogicAnchor {
    /** After main sources are compiled (and mixed modules assembled). */
    AFTER_COMPILE,
    /** After static resources are copied into classes (default for legacy {@code *Build} mains). */
    AFTER_RESOURCES,
    /** Immediately before packaging the jar / image. */
    BEFORE_PACKAGE
}
