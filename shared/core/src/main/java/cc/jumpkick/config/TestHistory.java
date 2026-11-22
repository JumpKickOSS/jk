// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Hashing;
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

/**
 * The recent runs of each test class of one module: class name to outcomes, one character per run,
 * oldest first ({@link #PASSED}, {@link #PASSED_ON_RERUN}, {@link #FAILED}).
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
     * The history of every module at or under {@code root}, by module directory. A file whose first
     * line names no directory is skipped.
     */
    public static List<TestHistory> under(Path stateDir, Path root) throws IOException {
        Path dir = dir(stateDir);
        if (!Files.isDirectory(dir)) return List.of();
        Path base = root.toAbsolutePath().normalize();
        List<TestHistory> out = new ArrayList<>();
        for (Path file : PathUtil.childFiles(dir)) {
            if (!file.toString().endsWith(".tsv")) continue;
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty() || !lines.getFirst().startsWith("# ")) continue;
            Path module = Path.of(lines.getFirst().substring(2).trim()).normalize();
            if (module.startsWith(base)) out.add(new TestHistory(module, rows(lines)));
        }
        out.sort(Comparator.comparing(TestHistory::moduleDir));
        return out;
    }

    /** The last {@code n} outcomes of {@code marks}. */
    public static String last(String marks, int n) {
        return marks.length() <= n ? marks : marks.substring(marks.length() - n);
    }

    /** How many of the last {@code runs} outcomes in {@code marks} are {@code mark}. */
    public static int count(String marks, int runs, char mark) {
        int n = 0;
        for (char c : last(marks, runs).toCharArray()) if (c == mark) n++;
        return n;
    }

    /** How many of the last {@code runs} outcomes in {@code marks} passed on a re-run. */
    public static int flakes(String marks, int runs) {
        return count(marks, runs, PASSED_ON_RERUN);
    }
}
