// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * What this host has learned about how long its work takes, in one file: the calibration probe, the
 * continuous rates, the prior rungs' biases, the effort-priced step kinds, the fetch and the lock
 * timings (JK-BSRC5). Each is a {@code [section]} of lines in its owner's own grammar; no line of a
 * section starts with {@code [}. The file is read once per change of its stamp and rewritten whole,
 * atomically, under one cross-process lock, so writers of different sections never drop each
 * other's lines.
 */
public final class HostStore {

    /** The store's file name under the builds root. */
    public static final String FILE = "host-estimates.txt";

    /** The calibration probe and its derived constants: {@code key = value}. */
    public static final String CALIBRATION = "calibration";

    /** The continuous rates learned from every run: {@code key = value}. */
    public static final String RATES = "rates";

    /** The prior rungs' biases: {@code RUNG kind log-factor runs}. */
    public static final String BIAS = "bias";

    /** The effort-priced step kinds' walls: {@code kind measure units wall-ms}, tab separated. */
    public static final String EFFORT = "effort";

    /** Remote fetch walls: {@code samples ms...}. */
    public static final String FETCH = "fetch";

    /** Lock timings: {@code name ms...}. */
    public static final String LOCK = "lock";

    /** The host's run means: {@code key = mean count}. */
    public static final String MEANS = "means";

    private static final StampedMemo<Path, StampedMemo.FileStamp, Map<String, List<String>>> MEMO =
            StampedMemo.bounded(4);

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
        return MEMO.get(file, stamp, () -> read(file));
    }

    /**
     * Replaces {@code section} of {@code file} with {@code edit} of its lines, under the store's lock;
     * an empty result drops the section. Failures are logged: what a host learns is never worth
     * failing a build over.
     */
    public static void update(Path file, String section, UnaryOperator<List<String>> edit) {
        try {
            OwnerOnlyFiles.createDirectories(Objects.requireNonNull(file.getParent(), "a file has a directory"));
            FileLocks.withLock(ProjectBuilds.ledgerLock(file), () -> {
                Map<String, List<String>> all = new LinkedHashMap<>(read(file));
                List<String> now = edit.apply(new ArrayList<>(all.getOrDefault(section, List.of())));
                if (now.isEmpty()) all.remove(section);
                else all.put(section, List.copyOf(now));
                AtomicWrites.replaceIfChanged(file, render(all));
            });
        } catch (IOException | RuntimeException e) {
            Log.debug("HostStore: " + section + " not recorded", e);
        }
    }

    static Map<String, List<String>> read(Path file) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            List<String> into = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("[") && line.endsWith("]")) {
                    into = new ArrayList<>();
                    out.put(line.substring(1, line.length() - 1), into);
                } else if (into != null && !line.isBlank() && !line.startsWith("#")) {
                    into.add(line);
                }
            }
        } catch (IOException e) {
            Log.debug("HostStore: unreadable " + file, e);
            return new LinkedHashMap<>();
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        return out;
    }

    private static String render(Map<String, List<String>> all) {
        StringBuilder sb = new StringBuilder("# what this host learned about its work's walls (HostStore)\n");
        for (Map.Entry<String, List<String>> e : all.entrySet()) {
            sb.append('[').append(e.getKey()).append("]\n");
            for (String line : e.getValue()) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
