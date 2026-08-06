// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.buildlogic;

/**
 * Registration surface for project build logic. Implementations of {@link BuildLogicContributor}
 * receive a graph during discovery and call {@link #task} to splice work at plan anchors.
 */
public interface BuildLogicGraph {
    /**
     * Register a named task at {@code anchor}. Names must be unique within the logic module;
     * they appear in plan labels as {@code build-logic:&lt;name&gt;}.
     */
    void task(String name, BuildLogicAnchor anchor, BuildLogicTask task);
}
