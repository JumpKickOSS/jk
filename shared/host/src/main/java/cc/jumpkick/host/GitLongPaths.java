// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.List;

/**
 * The options every git command jk runs starts with: on Windows, {@code core.longpaths}, since a
 * clone in jk's caches nests past 260 characters, which git refuses there unless the user set it.
 */
public final class GitLongPaths {

    private GitLongPaths() {}

    /** The options for this host. */
    public static List<String> options() {
        return options(Os.isWindows());
    }

    /** The options for a Windows host when {@code windows}, otherwise none. */
    public static List<String> options(boolean windows) {
        return windows ? List.of("-c", "core.longpaths=true") : List.of();
    }
}
