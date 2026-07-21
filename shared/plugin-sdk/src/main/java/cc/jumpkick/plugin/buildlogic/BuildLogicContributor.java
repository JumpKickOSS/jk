// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.buildlogic;

/**
 * SPI entry for project {@code .jk-build/} sources. Prefer this over a bare {@code main} when you
 * need named tasks or anchors other than {@link BuildLogicAnchor#AFTER_RESOURCES}.
 *
 * <p>Legacy {@code *Build} / {@code *BuildMain} classes with {@code public static void main} remain
 * supported and run at {@link BuildLogicAnchor#AFTER_RESOURCES}.
 */
public interface BuildLogicContributor {
    void register(BuildLogicGraph graph);
}
