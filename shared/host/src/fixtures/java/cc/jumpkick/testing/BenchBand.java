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
import org.jspecify.annotations.Nullable;

/**
 * The bench tier's side of the scoreboard: a microbenchmark reports its median under a name, and
 * when {@code scoreboard.toml} carries a {@code dim = "bench"} row with that subject (value in
 * milliseconds), a median more than the band above the banked one fails. The file is the {@code
 * jk.scoreboard} system property, else the nearest {@code scoreboard.toml} above the working
 * directory. A bench with no banked row prints its number and passes, so a nightly run's output is
 * what gets banked.
 */
public final class BenchBand {
    /** A bench asserts a shape, not a number: the same band as the scoreboard's absolute walls. */
    public static final double BAND = 0.15;

    private static final Pattern FIELD = Pattern.compile("^([a-z-]+)\\s*=\\s*(.+)$");

    private BenchBand() {}

    /** Report {@code medianMs} under {@code name}; fail when a banked row exists and the band is exceeded. */
    public static void within(String name, long medianMs) {
        Long banked = banked(name);
        if (banked == null) {
            System.out.printf(
                    "bench %s: median=%d ms (unbaselined: bank it as a dim = \"bench\" row in scoreboard.toml)%n",
                    name, medianMs);
            return;
        }
        System.out.printf("bench %s: median=%d ms, banked %d ms%n", name, medianMs, banked);
        if (medianMs > banked * (1 + BAND)) {
            fail("bench " + name + " took " + medianMs + " ms; banked " + banked + " ms (band " + (int) (BAND * 100)
                    + "%)");
        }
    }

    static @Nullable Long banked(String name) {
        Path p = board();
        if (p == null) return null;
        try {
            return parse(Files.readString(p)).get(name);
        } catch (IOException e) {
            return null;
        }
    }

    private static @Nullable Path board() {
        String file = System.getProperty("jk.scoreboard");
        if (file != null && !file.isBlank()) return Files.isRegularFile(Path.of(file)) ? Path.of(file) : null;
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("scoreboard.toml");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /** {@code [[row]]} entries with {@code dim = "bench"} and unit {@code ms}: subject to rounded value. */
    static Map<String, Long> parse(String toml) {
        Map<String, Long> out = new HashMap<>();
        Map<String, String> row = null;
        for (String raw : (toml + "\n[[row]]").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                if (row != null
                        && "\"bench\"".equals(row.get("dim"))
                        && "\"ms\"".equals(row.get("unit"))
                        && row.containsKey("subject")
                        && row.containsKey("value")) {
                    out.put(unquote(row.get("subject")), Math.round(Double.parseDouble(row.get("value"))));
                }
                row = "[[row]]".equals(line) ? new HashMap<>() : null;
                continue;
            }
            Matcher field = FIELD.matcher(line);
            if (row != null && field.matches())
                row.put(field.group(1), field.group(2).strip());
        }
        return out;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
}
