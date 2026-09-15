// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import java.io.IOException;

/**
 * The version a scaffold writes for a coordinate it names without one: the newest stable release
 * the repositories advertise. Scaffolds write that number into {@code jk.toml}; they never write a
 * floating selector.
 */
@FunctionalInterface
public interface ScaffoldVersions {

    /**
     * Newest stable release of {@code group:artifact}.
     *
     * @throws IOException when the repositories cannot be reached or advertise no stable release
     */
    String newestStable(String group, String artifact) throws IOException;
}
