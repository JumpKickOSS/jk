// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one place jk turns a list of paths into a {@code -cp} string and back. Lives in the
 * {@code :host} leaf because every process that forks a JVM builds one — the native client, the
 * engine, and the plugin workers, none of which share a richer module.
 *
 * <p>Round 3 counted eleven private joiner <em>methods</em>; widening the search from
 * {@code File.pathSeparator} to the property spelling found fourteen more inline copies, twenty-five
 * sites in all. They agreed on the separator and disagreed on everything else, which is the
 * interesting part, so all three decisions are written down here:
 *
 * <ul>
 *   <li><b>Separator</b> — {@link File#pathSeparator}. The copies spelled it three ways
 *       ({@code File.pathSeparator}, {@code System.getProperty("path.separator")}, and
 *       {@code isWindows() ? ";" : ":"}) and all three produce the same character, so this is a
 *       spelling choice, not a behaviour one.
 *   <li><b>Absolutising</b> — every entry is made absolute. Seventeen copies did and eight did not,
 *       and the seventeen are right: a {@code -cp} is read by a <em>child</em> JVM, whose working
 *       directory is jk's choice and frequently not the parent's. A relative entry that happens to
 *       work is working by accident. Absolutising an already-absolute path is identity, and all
 *       eight of the others were fed CAS or build-output paths, which are absolute — so converging
 *       on the majority changed no output.
 *   <li><b>Duplicates</b> — kept, in order. All twenty-five agreed, and they were right to: the
 *       JVM resolves a duplicated class from the <em>first</em> entry that carries it, so dropping
 *       a later duplicate is safe but dropping an earlier one silently changes which class loads.
 *       A caller that wants a deduplicated classpath must decide which copy wins and say so.
 * </ul>
 *
 * <p>One caller keeps a rule of its own and is right to: {@code MicronautPlugin.joinCp} throws when
 * an entry does not exist, because an AOT run that "succeeds" with half its optimizers missing is
 * worse than one that fails. That is a policy about which classpaths are acceptable, not about how
 * one is spelled, so it stays there and calls {@link #join} for the spelling.
 *
 * <p>Not covered: {@code PATH}. It uses the same separator character and is a different vocabulary
 * — an executable search path, not a class search path — so it keeps its own splitting and must not
 * borrow this owner.
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
    public static List<Path> split(String classpath) {
        if (classpath == null || classpath.isBlank()) return List.of();
        List<Path> out = new ArrayList<>();
        for (String entry : classpath.split(Pattern.quote(SEPARATOR), -1)) {
            if (!entry.isBlank()) out.add(Path.of(entry));
        }
        return List.copyOf(out);
    }
}
