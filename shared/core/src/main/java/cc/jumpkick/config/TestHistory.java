// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * The recent runs of each test class of one module: class name to outcomes, one character per run,
 * oldest first ({@link #PASSED}, {@link #PASSED_ON_RERUN}, {@link #FAILED}), with a {@link
 * #RELEASED} where the class was released from quarantine.
 *
 * <p>On disk it is one file per module under {@code <state>/test-history}, outside the checkout so a
 * clean keeps it: a {@code # <module dir>} line, then {@code <class>\t<outcomes>}.
 */
public record TestHistory(Path moduleDir, Map<String, String> classes) {

    /** A run the class passed. */
    public static final char PASSED = '.';

    /** A run the class failed and then passed on a re-run with nothing changed. */
    public static final char PASSED_ON_RERUN = 'R';

    /** A run the class failed. */
    public static final char FAILED = 'F';

    /** Where the class was released from quarantine: no count looks at the runs before it. */
    public static final char RELEASED = '|';

    /** Held while this process reads and rewrites a history file. */
    private static final Object LOCK = new Object();

    public TestHistory {
        classes = Collections.unmodifiableSortedMap(new TreeMap<>(classes));
    }

    /** The history file of the module at {@code moduleDir}, under {@code stateDir}. */
    public static Path file(Path stateDir, Path moduleDir) {
        String dir = moduleDir.toAbsolutePath().normalize().toString();
        return dir(stateDir).resolve(Hashing.sha256Hex(dir).substring(0, 16) + ".tsv");
    }

    private static Path dir(Path stateDir) {
        return stateDir.resolve("test-history");
    }

    /** Each class's outcomes in {@code file}, oldest first, sorted by class; empty when there is none. */
    public static Map<String, String> read(Path file) throws IOException {
        return Files.isRegularFile(file) ? rows(Files.readAllLines(file)) : new TreeMap<>();
    }

    private static Map<String, String> rows(List<String> lines) {
        Map<String, String> rows = new TreeMap<>();
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (line.startsWith("#") || tab <= 0) continue;
            rows.put(line.substring(0, tab), line.substring(tab + 1).trim());
        }
        return rows;
    }

    /**
     * The history of every module at or under {@code root}, by module directory. A file that cannot
     * be read, or whose first line names no directory, is skipped.
     */
    public static List<TestHistory> under(Path stateDir, Path root) throws IOException {
        Path dir = dir(stateDir);
        if (!Files.isDirectory(dir)) return List.of();
        Path base = root.toAbsolutePath().normalize();
        List<TestHistory> out = new ArrayList<>();
        for (Path file : PathUtil.childFiles(dir)) {
            if (!file.toString().endsWith(".tsv")) continue;
            List<String> lines;
            try {
                lines = Files.readAllLines(file);
            } catch (IOException e) {
                Log.debug("test history not read: " + file, e);
                continue;
            }
            if (lines.isEmpty() || !lines.getFirst().startsWith("# ")) continue;
            Path module = Path.of(lines.getFirst().substring(2).trim()).normalize();
            if (module.startsWith(base)) out.add(new TestHistory(module, rows(lines)));
        }
        out.sort(Comparator.comparing(TestHistory::moduleDir));
        return out;
    }

    /**
     * Apply {@code edit} to the classes of {@code file}, the history of the module at {@code
     * moduleDir}, write them back and answer them.
     */
    public static Map<String, String> edit(Path file, Path moduleDir, UnaryOperator<Map<String, String>> edit)
            throws IOException {
        synchronized (LOCK) {
            Map<String, String> rows = edit.apply(read(file));
            StringBuilder sb = new StringBuilder("# ")
                    .append(moduleDir.toAbsolutePath().normalize())
                    .append('\n');
            rows.forEach(
                    (cls, marks) -> sb.append(cls).append('\t').append(marks).append('\n'));
            Files.createDirectories(file.getParent());
            AtomicWrites.replace(file, sb.toString());
            return rows;
        }
    }

    /**
     * Mark each class of the modules under {@code root} that one of {@code entries} held as released,
     * in its history under {@code stateDir}, so its runs so far count toward no new quarantine.
     */
    public static void release(Path stateDir, Path root, List<TestQuarantine.Entry> entries) throws IOException {
        for (TestHistory h : under(stateDir, root)) {
            String module = TestQuarantine.moduleKey(root, h.moduleDir());
            boolean held = h.classes().keySet().stream()
                    .anyMatch(cls -> entries.stream().anyMatch(e -> e.holds(module, cls)));
            if (!held) continue;
            edit(file(stateDir, h.moduleDir()), h.moduleDir(), rows -> {
                rows.replaceAll((cls, marks) -> entries.stream().anyMatch(e -> e.holds(module, cls))
                                && !marks.isEmpty()
                                && marks.charAt(marks.length() - 1) != RELEASED
                        ? marks + RELEASED
                        : marks);
                return rows;
            });
        }
    }

    /** The last {@code n} runs of {@code marks}, with the releases among them. */
    public static String last(String marks, int n) {
        int runs = 0;
        int i = marks.length();
        while (i > 0 && runs < n) if (marks.charAt(--i) != RELEASED) runs++;
        return marks.substring(i);
    }

    /** The outcomes a count looks at: the last {@code runs} of {@code marks} after its last release. */
    public static String counted(String marks, int runs) {
        String last = last(marks, runs);
        return last.substring(last.lastIndexOf(RELEASED) + 1);
    }

    /** How many of the outcomes {@link #counted} looks at are {@code mark}. */
    public static int count(String marks, int runs, char mark) {
        int n = 0;
        for (char c : counted(marks, runs).toCharArray()) if (c == mark) n++;
        return n;
    }

    /** How many of the last {@code runs} outcomes in {@code marks}, since its last release, passed on a re-run. */
    public static int flakes(String marks, int runs) {
        return count(marks, runs, PASSED_ON_RERUN);
    }

    /** Whether {@code marks} holds a release. */
    public static boolean released(String marks) {
        return marks.indexOf(RELEASED) >= 0;
    }
}
