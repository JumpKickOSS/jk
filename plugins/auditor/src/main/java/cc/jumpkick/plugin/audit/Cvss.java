// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The score of a CVSS v3.0, v3.1 or v4.0 vector and its qualitative rating. A v3 vector is scored per
 * the <a href="https://www.first.org/cvss/v3.1/specification-document">CVSS v3.1 specification</a>
 * (section 7), a v4.0 one by {@link Cvss4}. Used when an advisory carries a vector but no severity
 * label.
 */
final class Cvss {

    private Cvss() {}

    private static final Set<String> BASE = Set.of("AV", "AC", "PR", "UI", "S", "C", "I", "A");

    /**
     * The score of {@code vector} ({@code CVSS:3.1/AV:N/AC:L/...} or {@code CVSS:4.0/AV:N/...}), 0.0 to
     * 10.0; null when it is not a v3.0, v3.1 or v4.0 vector carrying every base metric once with a
     * value its specification defines.
     */
    static @Nullable Double baseScore(String vector) {
        if (vector.trim().startsWith("CVSS:4.0/")) return Cvss4.score(vector);
        String[] parts = vector.trim().split("/");
        boolean v30 = parts[0].equals("CVSS:3.0");
        if (!v30 && !parts[0].equals("CVSS:3.1")) return null;
        Map<String, String> metrics = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int colon = parts[i].indexOf(':');
            if (colon <= 0) return null;
            String key = parts[i].substring(0, colon);
            // Temporal and environmental metrics may follow; only a repeated base metric is malformed.
            if (metrics.put(key, parts[i].substring(colon + 1)) != null && BASE.contains(key)) return null;
        }
        if (!metrics.keySet().containsAll(BASE)) return null;
        boolean changed;
        switch (metrics.getOrDefault("S", "")) {
            case "U" -> changed = false;
            case "C" -> changed = true;
            default -> {
                return null;
            }
        }
        double av = weight(metrics.getOrDefault("AV", ""), "N", 0.85, "A", 0.62, "L", 0.55, "P", 0.2);
        double ac = weight(metrics.getOrDefault("AC", ""), "L", 0.77, "H", 0.44);
        double pr = changed
                ? weight(metrics.getOrDefault("PR", ""), "N", 0.85, "L", 0.68, "H", 0.5)
                : weight(metrics.getOrDefault("PR", ""), "N", 0.85, "L", 0.62, "H", 0.27);
        double ui = weight(metrics.getOrDefault("UI", ""), "N", 0.85, "R", 0.62);
        double c = weight(metrics.getOrDefault("C", ""), "H", 0.56, "L", 0.22, "N", 0);
        double in = weight(metrics.getOrDefault("I", ""), "H", 0.56, "L", 0.22, "N", 0);
        double a = weight(metrics.getOrDefault("A", ""), "H", 0.56, "L", 0.22, "N", 0);
        if (Double.isNaN(av + ac + pr + ui + c + in + a)) return null;

        double iss = 1 - (1 - c) * (1 - in) * (1 - a);
        double impact = changed ? 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15) : 6.42 * iss;
        double exploitability = 8.22 * av * ac * pr * ui;
        if (impact <= 0) return 0.0;
        double raw = changed ? Math.min(1.08 * (impact + exploitability), 10) : Math.min(impact + exploitability, 10);
        return v30 ? Math.ceil(raw * 10) / 10 : roundUp(raw);
    }

    /**
     * The qualitative rating of a score, the same scale in v3 and v4: the label {@code
     * AuditReport.Severity} reads.
     */
    static String label(double score) {
        if (score >= 9.0) return "CRITICAL";
        if (score >= 7.0) return "HIGH";
        if (score >= 4.0) return "MEDIUM";
        // A 0.0 ("None") is still reported at the lowest severity rather than dropped.
        return "LOW";
    }

    /** v3.1's Roundup: the smallest one-decimal number at or above {@code value}, free of float error. */
    private static double roundUp(double value) {
        long scaled = Math.round(value * 100_000);
        if (scaled % 10_000 == 0) return scaled / 100_000.0;
        return (Math.floor(scaled / 10_000.0) + 1) / 10.0;
    }

    /** The weight {@code pairs} give {@code value} ({@code key, weight, key, weight, …}), or NaN. */
    private static double weight(String value, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i].equals(value)) return ((Number) pairs[i + 1]).doubleValue();
        }
        return Double.NaN;
    }
}
