// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.host.OutputDirs;
import java.nio.file.Files;
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

    /**
     * {@code target}, {@code .git}, {@code .gradle}, {@code .idea}, {@code node_modules} by name, and
     * Gradle's {@code build/} by position ({@link OutputDirs#isGradleBuildDir}): a package named
     * {@code build} under {@code src/} is walked.
     */
    private static final Set<String> WORKSPACE_KEY =
            Set.of(BuildLayout.TARGET, ".git", ".gradle", ".idea", "node_modules");

    public static boolean workspaceKey(Path dir) {
        Path name = dir.getFileName();
        return name != null && (WORKSPACE_KEY.contains(name.toString()) || OutputDirs.isGradleBuildDir(dir));
    }

    /** {@link #workspaceKey} plus {@code out}. */
    public static boolean pathSource(Path dir) {
        Path name = dir.getFileName();
        if (name == null) return false;
        String s = name.toString();
        return WORKSPACE_KEY.contains(s) || s.equals("out") || OutputDirs.isGradleBuildDir(dir);
    }

    /**
     * A directory that is another checkout — a nested repository or a git worktree, either of which
     * carries a {@code .git} entry (a directory or a pointer file). Its sources belong to the branch
     * checked out there, not to the tree that contains it. Recognised by what it is, not by name:
     * a name list is exactly what lets one through.
     */
    public static boolean nestedCheckout(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }

    /**
     * Format collect, by segment name: {@code target}, {@code .jk}, {@code jk}, {@code .git},
     * {@code node_modules}, {@code *.g8}/{@code g8}, {@code $…$}. Does not skip {@code .gradle},
     * {@code .idea}, or {@code out}. A name alone cannot tell Gradle's {@code build/} from a package
     * named {@code build}; the walk asks {@link #formatSkip} with the directory.
     */
    public static boolean formatSegment(String s) {
        if (s.equals(BuildLayout.TARGET)
                || s.equals(".jk")
                || s.equals("jk")
                || s.equals(".git")
                || s.equals("node_modules")) {
            return true;
        }
        if (s.endsWith(".g8") || s.equals("g8")) return true;
        return s.length() > 1 && s.startsWith("$") && s.endsWith("$");
    }

    /** {@link #formatSegment} on the directory's name, plus Gradle's {@code build/} by position. */
    public static boolean formatSkip(Path dir) {
        Path name = dir.getFileName();
        if (name == null) return false;
        return formatSegment(name.toString()) || OutputDirs.isGradleBuildDir(dir);
    }
}
