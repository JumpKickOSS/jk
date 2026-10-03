// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a test failure does to the run: {@link #FAIL} fails it, {@link #REPORT} keeps every failure in
 * the run's results and lets the run succeed, as Maven's {@code maven.test.failure.ignore} does.
 * A compile error, a crashed test JVM or a timeout fails the run either way.
 */
public enum TestFailureMode {
    FAIL,
    REPORT;

    /** The spelling in {@code jk.toml}, on the command line and on the wire. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code fail} or {@code report}, case-insensitive; empty for anything else. */
    public static Optional<TestFailureMode> parse(@Nullable String raw) {
        if (raw == null) return Optional.empty();
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "fail" -> Optional.of(FAIL);
            case "report" -> Optional.of(REPORT);
            default -> Optional.empty();
        };
    }
}
