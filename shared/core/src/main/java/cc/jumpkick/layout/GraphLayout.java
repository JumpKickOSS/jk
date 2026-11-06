// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a checkout keeps its code graph: build output under the workspace root's {@code
 * target/graph/}, gone with {@code jk clean}. The fragments it is linked from live in the action
 * cache.
 */
public final class GraphLayout {

    private GraphLayout() {}

    /** The linked graph, the file every read loads. */
    public static final String SNAPSHOT = "graph.bin";

    /** {@code <root>/target/graph/}. */
    public static Path dir(Path root) {
        return BuildLayout.moduleTargetDir(root).resolve("graph");
    }

    /** Whether the checkout at {@code root} has a graph: the first graph question makes one. */
    public static boolean built(Path root) {
        return Files.isRegularFile(dir(root).resolve(SNAPSHOT));
    }
}
