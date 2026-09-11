// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The one place jk splits and joins an executable search path — {@code PATH}. Same separator
 * character as a classpath, different vocabulary, which is why this owner sits beside
 * {@link Classpaths} instead of inside it:
 *
 * <ul>
 *   <li><b>Blank entries are kept.</b> An empty {@code PATH} entry means the current directory on
 *       POSIX, so a doubled or trailing separator is a real entry someone may rely on — the exact
 *       opposite of {@link Classpaths#split}, where a blank is a formatting artefact. Callers that
 *       refuse to probe the current directory skip blanks themselves, as a policy they own.
 *   <li><b>Nothing is absolutised.</b> A {@code -cp} is read by a child JVM whose working directory
 *       is jk's choice; {@code PATH} entries are resolved by the OS against whatever the current
 *       directory is at lookup time, and rewriting them would change that meaning.
 *   <li><b>Order is precedence.</b> {@link #prepend} exists because every join in the tree is
 *       "put this {@code bin} first"; there is deliberately no general join.
 * </ul>
 *
 * <p>The per-entry probe — which file names count as the tool under an entry ({@code .exe} /
 * {@code .cmd} / {@code .bat} variants, executability, symlink resolution) — stays with each
 * caller: it differs by tool on purpose, and this owner only decides where one entry ends and the
 * next begins.
 */
public final class SearchPath {

    private SearchPath() {}

    /** The platform's search-path separator: {@code ;} on Windows, {@code :} elsewhere. */
    public static final String SEPARATOR = File.pathSeparator;

    /**
     * Split a {@code PATH} value into entries, blanks preserved: {@code "a::b"} is three entries,
     * and the middle one means the current directory on POSIX. A {@code null} or empty value is no
     * entries at all — not one blank entry, because an unset {@code PATH} searches nothing.
     */
    public static List<String> entries(@Nullable String path) {
        if (path == null || path.isEmpty()) return List.of();
        return List.of(path.split(Pattern.quote(SEPARATOR), -1));
    }

    /**
     * Put {@code binDir} first on {@code existing}. When {@code existing} is {@code null} or empty
     * the result is {@code binDir} alone — appending a separator to nothing would add a blank
     * entry, i.e. silently put the current directory on the search path.
     */
    public static String prepend(String binDir, @Nullable String existing) {
        if (existing == null || existing.isEmpty()) return binDir;
        return binDir + SEPARATOR + existing;
    }

    /**
     * Drop every occurrence of {@code entry} from {@code path}, blanks preserved. A {@code null} or
     * blank {@code entry} is a no-op. When every entry is removed the result is {@code ""} (unset
     * search path), never a single blank entry.
     */
    public static String remove(@Nullable String entry, @Nullable String path) {
        if (entry == null || entry.isEmpty()) return path == null ? "" : path;
        var kept = new ArrayList<String>();
        for (String e : entries(path)) {
            if (!e.equals(entry)) kept.add(e);
        }
        if (kept.isEmpty()) return "";
        return String.join(SEPARATOR, kept);
    }
}
