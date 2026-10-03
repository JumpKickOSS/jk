// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import java.nio.file.Path;
import java.util.Objects;

/** A Node install found on this machine: its home, its version and the manager that put it there. */
public record DiscoveredNode(Path home, String version, String source) {

    public DiscoveredNode {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(source, "source");
    }
}
