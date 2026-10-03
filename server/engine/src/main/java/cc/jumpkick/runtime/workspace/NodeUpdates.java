// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ToolchainSpec;
import cc.jumpkick.node.NodeCatalog;
import cc.jumpkick.node.NodeRelease;
import cc.jumpkick.node.NodeSpec;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk update --major} for a manifest's own {@code node = <major>}: the major moves to the
 * newest one with an LTS release. A point release, an {@code =} pin and a keyword keep their text;
 * within a major, the relock that follows moves the lock.
 */
final class NodeUpdates {

    /** The handle a selection names to move the Node.js major. */
    static final String HANDLE = "node";

    private NodeUpdates() {}

    /** One move of {@code node}: the table it was written in ({@code node} or {@code node.version}), from, to. */
    record Move(String table, String from, String to, String text) {}

    /**
     * The move of the {@code node} major {@code text} declares itself, or null. {@code releases}
     * is read only when the manifest declares a plain major.
     */
    static @Nullable Move major(String text, Supplier<List<NodeRelease>> releases) {
        JkBuild own;
        try {
            own = JkBuildParser.parse(text);
        } catch (RuntimeException unparsable) {
            return null;
        }
        ToolchainSpec declared = own.project().nodeSpec();
        if (declared.isEmpty()) return null;
        NodeSpec spec = NodeSpec.of(declared);
        if (spec.kind() != NodeSpec.Kind.MAJOR) return null;
        int newest = newestLtsMajor(releases.get());
        if (newest <= spec.major()) return null;
        String to = String.valueOf(newest);
        // TOML holds one or the other: `[node] version` when a [node] table exists, else the root key.
        try {
            return new Move("node.version", spec.text(), to, JkBuildEditor.setTableScalar(text, "node", "version", to));
        } catch (IllegalStateException noTable) {
            try {
                return new Move("node", spec.text(), to, JkBuildEditor.setRootScalar(text, "node", to));
            } catch (IllegalStateException unwritable) {
                Log.debug("update: node not rewritten", unwritable);
                return null;
            }
        }
    }

    /** The newest major with an LTS release, or 0 when the catalog lists none. */
    static int newestLtsMajor(List<NodeRelease> releases) {
        return releases.stream()
                .filter(r -> !r.preRelease() && r.lts() != null)
                .mapToInt(NodeRelease::major)
                .max()
                .orElse(0);
    }

    /** The catalog's releases, or none when it cannot be read (offline with no cache). */
    static List<NodeRelease> releases(NodeCatalog catalog) {
        try {
            return catalog.releases();
        } catch (IOException e) {
            Log.debug("update: node catalog unavailable", e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
