// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import java.nio.file.Path;
import java.util.Set;

/**
 * Skip predicates for input-tree walks. Three sets, not one: unifying them would change
 * workspace-logic cache keys ({@code out/} would stop invalidating build-logic) and would make
 * format skip {@code .gradle}.
 *
 * <p>Do not bake {@code .jk-*} into {@code :host}. Lambdas are not cache keys — use the {@code
 * ID_*} constants.
 */
public final class WalkSkip {

    private WalkSkip() {}

    public static final String ID_NONE = "none";
    public static final String ID_WORKSPACE_KEY = "workspaceKey";
    public static final String ID_PATH_SOURCE = "pathSource";
    public static final String ID_FORMAT = "format";

    /** {@code target}, {@code build}, {@code .git}, {@code .gradle}, {@code .idea}, {@code node_modules}. */
    private static final Set<String> WORKSPACE_KEY =
            Set.of(BuildLayout.TARGET, "build", ".git", ".gradle", ".idea", "node_modules");

    public static boolean workspaceKey(Path dir) {
        Path name = dir.getFileName();
        return name != null && WORKSPACE_KEY.contains(name.toString());
    }

    /** {@link #workspaceKey} plus {@code out}. */
    public static boolean pathSource(Path dir) {
        Path name = dir.getFileName();
        if (name == null) return false;
        String s = name.toString();
        return WORKSPACE_KEY.contains(s) || s.equals("out");
    }

    /**
     * Format collect: {@code target}, {@code build}, {@code .jk}, {@code jk}, {@code .git},
     * {@code node_modules}, {@code *.g8}/{@code g8}, {@code $…$}. Does not skip {@code .gradle},
     * {@code .idea}, or {@code out}.
     */
    public static boolean formatSegment(String s) {
        if (s.equals(BuildLayout.TARGET)
                || s.equals("build")
                || s.equals(".jk")
                || s.equals("jk")
                || s.equals(".git")
                || s.equals("node_modules")) {
            return true;
        }
        if (s.endsWith(".g8") || s.equals("g8")) return true;
        return s.length() > 1 && s.startsWith("$") && s.endsWith("$");
    }
}
