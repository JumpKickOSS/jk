// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bench tier's side of the wall ratchet: a microbenchmark reports its median under a name, and
 * when {@code wall-baseline.toml} (the path in the {@code jk.wallBaseline} system property) carries a
 * {@code [bench.<name>]} table, a median more than the band above the banked one fails. A bench with
 * no banked line prints its number and passes — the first nightly run's output is what gets banked.
 */
public final class BenchBand {
    /** Same band as the wall rows: hosted runners are noisy, and a bench asserts a shape, not a number. */
    public static final double BAND = 0.15;

    private static final Pattern TABLE = Pattern.compile("^\\[bench\\.([a-z0-9-]+)]$");
    private static final Pattern MEDIAN = Pattern.compile("^median-ms\\s*=\\s*(\\d+(?:\\.\\d+)?)$");

    private BenchBand() {}

    /** Report {@code medianMs} under {@code name}; fail when a banked line exists and the band is exceeded. */
    public static void within(String name, long medianMs) {
        Long banked = banked(name);
        if (banked == null) {
            System.out.printf(
                    "bench %s: median=%d ms (unbaselined — bank it as [bench.%s] median-ms)%n", name, medianMs, name);
            return;
        }
        System.out.printf("bench %s: median=%d ms, banked %d ms%n", name, medianMs, banked);
        if (medianMs > banked * (1 + BAND)) {
            fail("bench " + name + " took " + medianMs + " ms; banked " + banked + " ms (band " + (int) (BAND * 100)
                    + "%)");
        }
    }

    static Long banked(String name) {
        String file = System.getProperty("jk.wallBaseline");
        if (file == null || file.isBlank()) return null;
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) return null;
        try {
            return parse(Files.readString(p)).get(name);
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code [bench.<name>]} tables → their {@code median-ms}, ignoring every other table. */
    static Map<String, Long> parse(String toml) {
        Map<String, Long> out = new HashMap<>();
        String current = null;
        for (String raw : toml.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher table = TABLE.matcher(line);
            if (table.matches()) {
                current = table.group(1);
                continue;
            }
            if (line.startsWith("[")) {
                current = null;
                continue;
            }
            Matcher median = MEDIAN.matcher(line);
            if (current != null && median.matches()) {
                out.put(current, Math.round(Double.parseDouble(median.group(1))));
            }
        }
        return out;
    }
}
