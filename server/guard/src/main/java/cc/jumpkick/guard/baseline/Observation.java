// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import org.jspecify.annotations.Nullable;

/**
 * What an evaluator saw this run, in baseline terms: a site fingerprint, or a metric unit with its
 * measured value. {@code value} is {@code null} for sites.
 *
 * @param file source file, when known, for the report
 * @param line source line, 0 when unknown (bytecode without debug info, model rules)
 * @param detail what was observed, in the rule's words
 */
public record Observation(
        String key, @Nullable Double value, @Nullable String file, int line, String detail) {

    public static Observation site(String fingerprint, @Nullable String file, int line, String detail) {
        return new Observation(fingerprint, null, file, line, detail);
    }

    public static Observation metric(String unit, double value, @Nullable String file, String detail) {
        return new Observation(unit, value, file, 0, detail);
    }

    public boolean isMetric() {
        return value != null;
    }
}
