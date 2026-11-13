// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * What this host has learned about how long its work takes, in one file: the calibration probe, the
 * continuous rates, the prior rungs' biases, the effort-priced step kinds, the fetch and the lock
 * timings, and the run means of its step kinds and invocations. Each is a {@code [section]} of lines
 * in its owner's own grammar ({@link SectionedFile}). The file is read once per change of its stamp.
 */
public final class HostStore {

    /** The store's file name under the builds root. */
    public static final String FILE = "host-estimates.txt";

    /** The calibration probe and its derived constants: {@code key = value}. */
    public static final String CALIBRATION = "calibration";

    /** The continuous rates learned from every run: {@code key = value}. */
    public static final String RATES = "rates";

    /** Each rung's bias and spread per step kind: {@code RUNG kind log-factor mean-square runs}. */
    public static final String BIAS = "bias";

    /** The effort-priced step kinds' walls: {@code kind measure units wall-ms}, tab separated. */
    public static final String EFFORT = "effort";

    /** Remote fetch walls: {@code samples ms...}. */
    public static final String FETCH = "fetch";

    /** Lock timings: {@code name ms...}. */
    public static final String LOCK = "lock";

    /** The host's run means: {@code key mean last count} ({@link #foldMeans}). */
    public static final String MEANS = "means";

    private static final StampedMemo<Path, StampedMemo.FileStamp, Map<String, List<String>>> MEMO =
            StampedMemo.bounded(4);

    private static final String HEADER = "what this host learned about its work's walls (HostStore)";

    private HostStore() {}

    /** The host's store. */
    public static Path defaultFile() {
        return JkDirs.builds().resolve(FILE);
    }

    /** The lines of {@code section} in {@code file}; empty when either is missing. */
    public static List<String> section(Path file, String section) {
        return sections(file).getOrDefault(section, List.of());
    }

    /** Every section of {@code file}, in file order. */
    public static Map<String, List<String>> sections(Path file) {
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(file);
        if (stamp == null) return Map.of();
        return MEMO.get(file, stamp, () -> SectionedFile.read(file, null));
    }

    /**
     * Replaces {@code section} of {@code file} with {@code edit} of its lines, under the store's lock;
     * an empty result drops the section. Failures are logged: what a host learns is never worth
     * failing a build over.
     */
    public static void update(Path file, String section, UnaryOperator<List<String>> edit) {
        try {
            SectionedFile.update(file, HEADER, Map.of(section, edit));
        } catch (IOException | RuntimeException e) {
            Log.debug("HostStore: " + section + " not recorded", e);
        }
    }

    /**
     * Whether a run's {@code key} also teaches the host: a step kind's wall anywhere ({@code
     * task.<step>.wall-ms}), and a whole invocation of one kind, not one directory's ({@code
     * invocation.<kind>.wall-ms}, {@code workspace.wall-ms}): the tier a project with no history of
     * its own, or a caller outside any project, reads.
     */
    public static boolean isHostKey(String key) {
        if (key.startsWith("task.")) return true;
        if (key.equals("workspace.wall-ms")) return true;
        if (key.startsWith("invocation.") && key.endsWith(".wall-ms")) {
            String kind = key.substring("invocation.".length(), key.length() - ".wall-ms".length());
            return !kind.isEmpty() && kind.indexOf('.') < 0;
        }
        return false;
    }

    /**
     * Folds a finished run's host keys ({@link #isHostKey}) among {@code samples} into {@link
     * #MEANS}, one {@link RunningMean} per key: {@code key mean last count}. The host's keys are a
     * few per step kind, so the section keeps every one.
     */
    public static void foldMeans(Path file, Map<String, Double> samples) {
        Map<String, Double> host = new LinkedHashMap<>();
        samples.forEach((k, v) -> {
            if (isHostKey(k) && v >= 0) host.put(k, v);
        });
        if (host.isEmpty()) return;
        update(file, MEANS, lines -> {
            Map<String, RunningMean> all = new TreeMap<>(parseMeans(lines));
            host.forEach((k, v) -> all.put(k, RunningMean.fold(all.get(k), v, 0)));
            List<String> out = new ArrayList<>(all.size());
            all.forEach((k, m) ->
                    out.add(k + ' ' + RunningMean.fmt(m.mean()) + ' ' + RunningMean.fmt(m.last()) + ' ' + m.count()));
            return out;
        });
    }

    /** The host's run means in {@code file}, by key. */
    public static Map<String, RunningMean> means(Path file) {
        return parseMeans(section(file, MEANS));
    }

    private static Map<String, RunningMean> parseMeans(List<String> lines) {
        Map<String, RunningMean> out = new LinkedHashMap<>();
        for (String line : lines) {
            String[] cols = line.split(" ");
            RunningMean m = RunningMean.parse(cols, 1);
            if (m != null) out.put(cols[0], m);
        }
        return out;
    }
}
