// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.HostStore;
import cc.jumpkick.builds.MetricsFile;
import cc.jumpkick.builds.ProjectStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Seeds the estimate stores for a test from a readable spelling: {@code [mean]}, {@code [last]} and
 * {@code [count]} tables of {@code key = value} rows, {@code [test-class."<dir>"."<pkg>"]} tables of
 * class walls ({@link MetricsFile}), and for the host a {@code [calibration]} table taken as is. A
 * key with a mean and no last has its mean as its last, and the reverse; a key with no count has one.
 */
public final class StoreSeed {

    private StoreSeed() {}

    /** Writes {@code body} as the project store under {@code projectHome}, replacing any. */
    public static Path project(Path projectHome, String body) {
        Rows rows = rows(body);
        StringBuilder sb = new StringBuilder("# seeded\n[meta]\nruns 1\n[means]\n");
        rows.keys()
                .forEach((key, m) ->
                        sb.append(key).append(' ').append(columns(m)).append(" 1\n"));
        sb.append("[classes]\n");
        rows.classes().forEach(c -> sb.append(c).append('\n'));
        Path file = ProjectStore.file(projectHome);
        write(file, sb.toString());
        return file;
    }

    /** Writes {@code body} as the host store under {@code buildsRoot}, replacing any. */
    public static Path host(Path buildsRoot, String body) {
        Rows rows = rows(body);
        StringBuilder sb = new StringBuilder("# seeded\n[" + HostStore.MEANS + "]\n");
        rows.keys()
                .forEach((key, m) ->
                        sb.append(key).append(' ').append(columns(m)).append('\n'));
        List<String> calibration = section(body, "calibration");
        if (!calibration.isEmpty()) {
            sb.append('[').append(HostStore.CALIBRATION).append("]\n");
            calibration.forEach(l -> sb.append(l).append('\n'));
        }
        Path file = buildsRoot.resolve(HostStore.FILE);
        write(file, sb.toString());
        return file;
    }

    private record Rows(Map<String, double[]> keys, List<String> classes) {}

    /** {mean, last, count}, NaN where unset. */
    private static Rows rows(String body) {
        Map<String, double[]> keys = new LinkedHashMap<>();
        List<String> classes = new ArrayList<>();
        MetricsFile.scan(
                body,
                (section, key, v) -> {
                    double[] m = keys.computeIfAbsent(key, k -> new double[] {Double.NaN, Double.NaN, Double.NaN});
                    switch (section) {
                        case "last" -> m[1] = v;
                        case "count" -> m[2] = v;
                        case "calibration" -> keys.remove(key);
                        default -> m[0] = v;
                    }
                },
                (dir, fqcn, ms) -> classes.add(dir + '\t' + fqcn + '\t' + fmt(ms) + '\t' + fmt(ms) + "\t1\t1"));
        return new Rows(keys, classes);
    }

    private static String columns(double[] m) {
        double mean = Double.isNaN(m[0]) ? m[1] : m[0];
        double last = Double.isNaN(m[1]) ? mean : m[1];
        long count = Double.isNaN(m[2]) ? 1 : Math.max(1, Math.round(m[2]));
        return fmt(mean) + ' ' + fmt(last) + ' ' + count;
    }

    private static List<String> section(String body, String name) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : body.split("\n")) {
            String t = line.strip();
            if (t.startsWith("[")) {
                in = t.equals("[" + name + "]");
            } else if (in && !t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out;
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
