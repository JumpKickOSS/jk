// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.test.ClassReads;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The code files a suite's JVMs read, as its record file's header names them: each by its content
 * and since when it has held it. A class that read more code files than its row keeps (a JVM's code
 * files are charged to every class that ran after they were read) stands on all of them: it is
 * replayed only when none has changed since it ran ({@link #since}).
 *
 * <p>Each run's list is merged with the one before, so a class replayed in a run that read none of
 * those files still finds its own. A file keeps its time while its content holds; a file new to the
 * list, or whose content moved, takes the start of the run that saw it, so only the classes of that
 * run stand on it. Past {@link ClassReads#SUITE_CODE_KEPT} files the list starts over from the run's
 * own, at that run's start, and every class of an earlier run that stood on it runs again.
 */
final class SuiteCodeFiles {

    private SuiteCodeFiles() {}

    /** The header's list: {@code <since> <content> <path>} per file. */
    static final String FIELD = "suiteCode";

    /** Set when the suite read more code files than the list holds: no class stands on it. */
    static final String MORE = "suiteCodeMore";

    /** What a header names: its entries, and whether the suite read more than they hold. */
    record Listed(List<String> entries, boolean more) {}

    /**
     * The list this run leaves: {@code now}, the code files it read, merged with what {@code
     * previousHeader} names; {@code runStart} is when its first class finished.
     */
    static Listed listed(@Nullable String previousHeader, ClassReads.SuiteCode now, Path workspace, long runStart) {
        if (now.more()) return new Listed(List.of(), true);
        long fresh = runStart - 1;
        Map<String, String[]> before = new LinkedHashMap<>();
        if (previousHeader != null && !Jsonl.bool(previousHeader, MORE, false)) {
            for (String e : Jsonl.strArray(previousHeader, FIELD)) {
                String[] parts = e.split(" ", 3);
                if (parts.length == 3) before.put(parts[2], parts);
            }
        }
        TreeSet<String> paths = spelled(now.files(), workspace);
        paths.addAll(before.keySet());
        if (paths.size() > ClassReads.SUITE_CODE_KEPT) {
            paths = spelled(now.files(), workspace);
            before.clear();
        }
        List<String> entries = new ArrayList<>();
        for (String path : paths) {
            String content = content(workspace, path);
            String[] was = before.get(path);
            long since = was != null && was[1].equals(content) ? Long.parseLong(was[0]) : fresh;
            entries.add(since + " " + content + " " + path);
        }
        return new Listed(entries, false);
    }

    /**
     * The latest moment a file of {@code header}'s list began to hold what it holds now: a class that
     * finished after it read what is there today. {@link Long#MAX_VALUE} when a file changed since the
     * header was written, the suite read more than the list holds, or the header names no list.
     */
    static long since(@Nullable String header, Path workspace) {
        if (header == null || !Jsonl.has(header, FIELD) || Jsonl.bool(header, MORE, false)) return Long.MAX_VALUE;
        long latest = Long.MIN_VALUE;
        for (String e : Jsonl.strArray(header, FIELD)) {
            String[] parts = e.split(" ", 3);
            if (parts.length != 3 || !parts[1].equals(content(workspace, parts[2]))) return Long.MAX_VALUE;
            try {
                latest = Math.max(latest, Long.parseLong(parts[0]));
            } catch (NumberFormatException odd) {
                return Long.MAX_VALUE;
            }
        }
        return latest;
    }

    private static TreeSet<String> spelled(Collection<Path> files, Path workspace) {
        TreeSet<String> out = new TreeSet<>();
        for (Path p : files) out.add(ClassKeys.spell(workspace, p));
        return out;
    }

    private static String content(Path workspace, String spelled) {
        try {
            return ClassKeys.content(workspace.resolve(spelled).toAbsolutePath().normalize());
        } catch (InvalidPathException odd) {
            return "odd";
        }
    }
}
