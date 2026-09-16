// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.util.DirKeys;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How a module directory is spelled inside a metrics key.
 *
 * <p>On disk a module under the project root is spelled relative to it ({@code server/io}; the
 * root itself is {@value #ROOT}), so every checkout of one project — a clone, a worktree, a moved
 * directory — writes and reads one row set. A module outside the root keeps its absolute path. In
 * memory every key is absolute again: {@link #absolute} expands the relative spellings against the
 * root the ledger is being read for, so readers keep looking modules up by their real path.
 */
public final class ModuleKeys {

    /** The key spelling of the project root itself. */
    public static final String ROOT = "_";

    /** The key segments that follow a module directory; the directory ends at the first of them. */
    private static final List<String> MARKERS = List.of(".task.", ".phase.", ".test-class.");

    private static final String MODULE = "module.";

    private ModuleKeys() {}

    /**
     * The on-disk spelling of {@code moduleDir} under {@code rootDir}: {@value #ROOT} for the root,
     * the slash-separated path below it, or the sanitized absolute path when it lies elsewhere
     * (or no root is known).
     */
    public static String relative(@Nullable String moduleDir, @Nullable String rootDir) {
        if (moduleDir == null) return AggregatedMetrics.sanitize(null);
        if (rootDir == null || rootDir.isBlank()) return AggregatedMetrics.sanitize(moduleDir);
        String module = normalize(moduleDir);
        String root = normalize(rootDir);
        if (module.equals(root)) return ROOT;
        String prefix = root.endsWith("/") ? root : root + "/";
        if (module.startsWith(prefix)) return AggregatedMetrics.sanitize(module.substring(prefix.length()));
        return AggregatedMetrics.sanitize(moduleDir);
    }

    /**
     * {@code key} with a relative module directory expanded against {@code root} (the sanitized
     * absolute project directory). Keys that carry no module directory, or an absolute one, and
     * every key when {@code root} is unknown, come back unchanged.
     */
    public static String absolute(String key, @Nullable String root) {
        if (root == null || root.isEmpty() || !key.startsWith(MODULE)) return key;
        int at = firstMarker(key);
        if (at < 0) return key;
        String dir = key.substring(MODULE.length(), at);
        if (dir.equals(ROOT)) return MODULE + root + key.substring(at);
        if (dir.isEmpty() || isAbsolute(dir) || dir.indexOf(':') >= 0) return key;
        return MODULE + root + "/" + dir + key.substring(at);
    }

    private static int firstMarker(String key) {
        int at = -1;
        for (String marker : MARKERS) {
            int i = key.indexOf(marker, MODULE.length());
            if (i > 0 && (at < 0 || i < at)) at = i;
        }
        return at;
    }

    private static boolean isAbsolute(String dir) {
        if (dir.startsWith("/")) return true;
        return dir.length() >= 3 && Character.isLetter(dir.charAt(0)) && dir.charAt(1) == ':' && dir.charAt(2) == '/';
    }

    private static String normalize(String dir) {
        String slashed = DirKeys.slashes(dir);
        try {
            String n = DirKeys.slashes(
                    Path.of(slashed).toAbsolutePath().normalize().toString());
            return n.length() > 1 && n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
        } catch (RuntimeException e) {
            return slashed;
        }
    }
}
