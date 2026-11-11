// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.SearchPath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The directories jk's upward searches never climb into: {@code JK_CEILING_DIRECTORIES}, a list of
 * absolute paths separated by the platform's path separator ({@code :}, or {@code ;} on Windows),
 * as Git's {@code GIT_CEILING_DIRECTORIES} is.
 *
 * <p>Every search that climbs from a directory for the project it belongs to — the nearest {@code
 * jk.toml}, the workspace that lists a module, the workspace {@code jk new} and {@code jk add}
 * register a module in — examines the directory it starts in, then each parent until the next one
 * would be a ceiling. A ceiling and everything above it are outside the search, so a project under
 * {@code /work/scratch} with {@code JK_CEILING_DIRECTORIES=/work/scratch} never joins a workspace at
 * {@code /work}. A search that starts outside every ceiling climbs as it would without one.
 *
 * <p>A relative or malformed entry is ignored, and so is an empty one. Each entry also counts by its
 * real path, so a ceiling spelled through a symbolic link ({@code /tmp} on macOS) stops a search
 * that climbs through the target's spelling.
 */
public final class SearchCeiling {

    /** The environment variable, read through {@link JkDirs#env} so a test can set it in-process. */
    public static final String ENV = "JK_CEILING_DIRECTORIES";

    private static final SearchCeiling NONE = new SearchCeiling(Set.of());

    private final Set<Path> ceilings;

    private SearchCeiling(Set<Path> ceilings) {
        this.ceilings = ceilings;
    }

    /** The last value {@link #current} parsed and its answer: searches are hot, the value rarely moves. */
    private record Parsed(String value, SearchCeiling ceiling) {}

    private static volatile @Nullable Parsed last;

    /** The ceilings {@code JK_CEILING_DIRECTORIES} names now. */
    public static SearchCeiling current() {
        String value = JkDirs.env(ENV);
        if (value == null || value.isBlank()) return NONE;
        Parsed kept = last;
        if (kept != null && kept.value().equals(value)) return kept.ceiling();
        SearchCeiling parsed = parse(value);
        last = new Parsed(value, parsed);
        return parsed;
    }

    /** The ceilings {@code value} names, in the variable's syntax; none for {@code null}. */
    public static SearchCeiling parse(@Nullable String value) {
        if (value == null || value.isBlank()) return NONE;
        Set<Path> out = new LinkedHashSet<>();
        for (String entry : SearchPath.entries(value)) {
            Path path = SearchPath.path(entry.strip());
            if (path == null || !path.isAbsolute()) continue;
            Path normalized = path.normalize();
            out.add(normalized);
            try {
                out.add(normalized.toRealPath());
            } catch (IOException missing) {
                // a ceiling that does not exist yet still counts by its spelling
            }
        }
        return out.isEmpty() ? NONE : new SearchCeiling(Set.copyOf(out));
    }

    /**
     * The directory an upward search examines after {@code dir}: its parent, or {@code null} at the
     * filesystem root or when the parent is a ceiling. {@code dir} is absolute and normalized.
     */
    public @Nullable Path above(Path dir) {
        Path parent = dir.getParent();
        if (parent == null || ceilings.contains(parent)) return null;
        return parent;
    }
}
