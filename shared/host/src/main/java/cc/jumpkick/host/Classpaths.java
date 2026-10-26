// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The one place jk turns a list of paths into a {@code -cp} string and back. Lives in the
 * {@code :host} leaf because every process that forks a JVM builds one — the native client, the
 * engine, and the plugin workers, none of which share a richer module.
 *
 * <p>Three decisions govern how a classpath is formatted:
 *
 * <ul>
 *   <li><b>Separator</b> — {@link File#pathSeparator}.
 *   <li><b>Absolutising</b> — every entry is made absolute, because a {@code -cp} is read by a
 *       child JVM whose working directory may differ from the parent's.
 *   <li><b>Duplicates</b> — kept, in order, because the JVM resolves a duplicated class from the
 *       first entry that carries it.
 * </ul>
 *
 * <p>One caller keeps a rule of its own and is right to: {@code MicronautPlugin.joinCp} throws when
 * an entry does not exist, because a Micronaut AOT run that "succeeds" with half its optimizers missing is
 * worse than one that fails. That is a policy about which classpaths are acceptable, not about how
 * one is spelled, so it stays there and calls {@link #join} for the spelling.
 *
 * <p>Not covered: {@code PATH}. It uses the same separator character and is a different vocabulary
 * — an executable search path, not a class search path — so it belongs to {@link SearchPath} and
 * must not borrow this owner.
 */
public final class Classpaths {

    private Classpaths() {}

    /** The platform's classpath separator: {@code ;} on Windows, {@code :} elsewhere. */
    public static final String SEPARATOR = File.pathSeparator;

    /**
     * Join classpath entries into a {@code -cp} string: absolutised, in order, duplicates kept.
     * An empty collection yields {@code ""}, which is what every caller's "no classpath" branch
     * already produced.
     */
    public static String join(Collection<Path> entries) {
        StringBuilder sb = new StringBuilder();
        for (Path entry : entries) {
            if (sb.length() > 0) sb.append(SEPARATOR);
            sb.append(entry.toAbsolutePath());
        }
        return sb.toString();
    }

    /**
     * Split a {@code -cp} string back into entries, dropping blanks. Blank-dropping is the inverse
     * asymmetry to {@link #join}'s duplicate-keeping: a trailing or doubled separator is a
     * formatting artefact of whoever built the string, never an entry someone meant.
     */
    public static List<Path> split(@Nullable String classpath) {
        if (classpath == null || classpath.isBlank()) return List.of();
        List<Path> out = new ArrayList<>();
        for (String entry : classpath.split(Pattern.quote(SEPARATOR), -1)) {
            if (!entry.isBlank()) out.add(Path.of(entry));
        }
        return List.copyOf(out);
    }

    /**
     * Fail when a jar the {@code what} classpath names is not on disk. A compiler given such a path
     * skips it and fails later on the first class it cannot find, which names neither the jar nor
     * the reason; this names both. Directories are not checked — a sibling's classes tree may be
     * legitimately empty — only archive entries.
     */
    public static void requireArchivesOnDisk(Collection<Path> classpath, String what) {
        List<String> missing = new ArrayList<>();
        for (Path entry : classpath) {
            String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
            boolean archive = name.endsWith(".jar") || name.endsWith(".aar") || name.endsWith(".zip");
            if (archive && !Files.isRegularFile(entry)) missing.add(entry.toString());
        }
        if (missing.isEmpty()) return;
        throw new IllegalStateException(
                what + " names " + missing.size() + " jar(s) that are not on disk: " + String.join(", ", missing)
                        + " — run `jk sync` to fetch the lock's artifacts, or `jk lock` if the lock is stale");
    }
}
