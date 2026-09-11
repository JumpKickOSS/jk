// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The banked jar sizes in {@code jar-size-baseline.toml}: one {@code [fixture.<name>]} table per
 * fixture, integer byte counts per key. Keys starting with {@code jk-} gate; the tool keys are the
 * comparison and never fail.
 */
final class JarSizeBaseline {

    /**
     * A jk artifact more than this far above its banked bytes fails. Inputs are pinned by the
     * fixture lockfiles, so run-to-run noise is zero; the band absorbs a jk version string changing
     * length inside the SBOM, not a packaging change.
     */
    static final double BAND = 0.005;

    private static final Pattern TABLE = Pattern.compile("^\\[fixture\\.([a-z0-9-]+)]$");
    private static final Pattern VALUE = Pattern.compile("^([a-z-]+)\\s*=\\s*(\\d+)$");

    private final Map<String, Map<String, Long>> tables;

    private JarSizeBaseline(Map<String, Map<String, Long>> tables) {
        this.tables = tables;
    }

    static JarSizeBaseline read(Path file) throws IOException {
        return new JarSizeBaseline(parse(Files.readString(file)));
    }

    static Map<String, Map<String, Long>> parse(String toml) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        @Nullable String current = null;
        for (String raw : toml.split("\n")) {
            String line = raw.strip();
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash).strip();
            if (line.isEmpty()) continue;
            Matcher t = TABLE.matcher(line);
            if (t.matches()) {
                current = t.group(1);
                out.computeIfAbsent(current, k -> new LinkedHashMap<>());
                continue;
            }
            if (line.startsWith("[")) {
                current = null;
                continue;
            }
            Matcher v = VALUE.matcher(line);
            if (current != null && v.matches()) {
                out.computeIfAbsent(current, k -> new LinkedHashMap<>()).put(v.group(1), Long.parseLong(v.group(2)));
            }
        }
        return out;
    }

    @Nullable
    Long banked(String fixture, String key) {
        Map<String, Long> table = tables.get(fixture);
        return table == null ? null : table.get(key);
    }

    /**
     * Compare a measured jk byte count with its banked line. Prints the verdict; fails when the
     * measurement is above the band. A measurement below the band is an improvement to re-bank,
     * never a failure.
     */
    void check(String fixture, String key, long measured) {
        Long banked = banked(fixture, key);
        if (banked == null) {
            System.out.printf(
                    "baseline %s.%s: %,d bytes (unbaselined — bank it as [fixture.%s] %s = %d)%n",
                    fixture, key, measured, fixture, key, measured);
            return;
        }
        double pct = 100.0 * (measured - banked) / banked;
        System.out.printf(
                "baseline %s.%s: measured %,d, banked %,d (%+.3f%%, band ±%.1f%%)%n",
                fixture, key, measured, banked, pct, BAND * 100);
        if (measured > banked * (1 + BAND)) {
            fail(String.format(
                    "%s.%s grew to %,d bytes; banked %,d (+%.3f%%, band %.1f%%) — find the cause in the"
                            + " attribution above, then re-bank in jar-size-baseline.toml with the reason",
                    fixture, key, measured, banked, pct, BAND * 100));
        }
        if (measured < banked * (1 - BAND)) {
            System.out.printf(
                    "baseline %s.%s: improvement — re-bank %s = %d in jar-size-baseline.toml%n",
                    fixture, key, key, measured);
        }
    }
}
