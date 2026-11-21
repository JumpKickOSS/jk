// SPDX-License-Identifier: Apache-2.0 AND BSD-2-Clause
package cc.jumpkick.plugin.audit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The score of a CVSS v4.0 vector, per the <a href="https://www.first.org/cvss/v4.0/specification-document">
 * CVSS v4.0 specification</a>: the vector's MacroVector score from the lookup table, less the mean
 * proportional distance to the next lower MacroVector. A port of the reference calculator's {@code
 * cvss40.js} (<a href="https://github.com/RedHatProductSecurity/cvss-v4-calculator">
 * RedHatProductSecurity/cvss-v4-calculator</a>, the upstream of FIRST's calculator; Copyright (c)
 * 2023 FIRST.ORG, Inc., Red Hat, and contributors, BSD-2-Clause); the table is the {@code
 * cvss4-macrovectors.txt} resource beside this class, which carries that licence in full. Threat and
 * environmental metrics count when the vector carries them, as the calculator counts them; unlike
 * the calculator, metrics in another order than the specification's are scored too.
 */
final class Cvss4 {

    private Cvss4() {}

    /** Every metric a v4.0 vector may carry and the values it may take, in the specification's order. */
    private static final Map<String, List<String>> METRICS = Map.ofEntries(
            Map.entry("AV", List.of("N", "A", "L", "P")),
            Map.entry("AC", List.of("L", "H")),
            Map.entry("AT", List.of("N", "P")),
            Map.entry("PR", List.of("N", "L", "H")),
            Map.entry("UI", List.of("N", "P", "A")),
            Map.entry("VC", List.of("H", "L", "N")),
            Map.entry("VI", List.of("H", "L", "N")),
            Map.entry("VA", List.of("H", "L", "N")),
            Map.entry("SC", List.of("H", "L", "N")),
            Map.entry("SI", List.of("H", "L", "N")),
            Map.entry("SA", List.of("H", "L", "N")),
            Map.entry("E", List.of("X", "A", "P", "U")),
            Map.entry("CR", List.of("X", "H", "M", "L")),
            Map.entry("IR", List.of("X", "H", "M", "L")),
            Map.entry("AR", List.of("X", "H", "M", "L")),
            Map.entry("MAV", List.of("X", "N", "A", "L", "P")),
            Map.entry("MAC", List.of("X", "L", "H")),
            Map.entry("MAT", List.of("X", "N", "P")),
            Map.entry("MPR", List.of("X", "N", "L", "H")),
            Map.entry("MUI", List.of("X", "N", "P", "A")),
            Map.entry("MVC", List.of("X", "H", "L", "N")),
            Map.entry("MVI", List.of("X", "H", "L", "N")),
            Map.entry("MVA", List.of("X", "H", "L", "N")),
            Map.entry("MSC", List.of("X", "H", "L", "N")),
            Map.entry("MSI", List.of("X", "S", "H", "L", "N")),
            Map.entry("MSA", List.of("X", "S", "H", "L", "N")),
            Map.entry("S", List.of("X", "N", "P")),
            Map.entry("AU", List.of("X", "N", "Y")),
            Map.entry("R", List.of("X", "A", "U", "I")),
            Map.entry("V", List.of("X", "D", "C")),
            Map.entry("RE", List.of("X", "L", "M", "H")),
            Map.entry("U", List.of("X", "Clear", "Green", "Amber", "Red")));

    private static final List<String> BASE = List.of("AV", "AC", "AT", "PR", "UI", "VC", "VI", "VA", "SC", "SI", "SA");

    /** The metrics whose severity distance from a MacroVector's highest vector is measured. */
    private static final List<String> DISTANCE =
            List.of("AV", "PR", "UI", "AC", "AT", "VC", "VI", "VA", "SC", "SI", "SA", "CR", "IR", "AR");

    /** Each metric value's severity level, 0 the most severe, in steps of 0.1. */
    private static final Map<String, Map<String, Double>> LEVELS = Map.ofEntries(
            Map.entry("AV", Map.of("N", 0.0, "A", 0.1, "L", 0.2, "P", 0.3)),
            Map.entry("PR", Map.of("N", 0.0, "L", 0.1, "H", 0.2)),
            Map.entry("UI", Map.of("N", 0.0, "P", 0.1, "A", 0.2)),
            Map.entry("AC", Map.of("L", 0.0, "H", 0.1)),
            Map.entry("AT", Map.of("N", 0.0, "P", 0.1)),
            Map.entry("VC", Map.of("H", 0.0, "L", 0.1, "N", 0.2)),
            Map.entry("VI", Map.of("H", 0.0, "L", 0.1, "N", 0.2)),
            Map.entry("VA", Map.of("H", 0.0, "L", 0.1, "N", 0.2)),
            Map.entry("SC", Map.of("H", 0.1, "L", 0.2, "N", 0.3)),
            Map.entry("SI", Map.of("S", 0.0, "H", 0.1, "L", 0.2, "N", 0.3)),
            Map.entry("SA", Map.of("S", 0.0, "H", 0.1, "L", 0.2, "N", 0.3)),
            Map.entry("CR", Map.of("H", 0.0, "M", 0.1, "L", 0.2)),
            Map.entry("IR", Map.of("H", 0.0, "M", 0.1, "L", 0.2)),
            Map.entry("AR", Map.of("H", 0.0, "M", 0.1, "L", 0.2)));

    /** The highest-severity vectors of each EQ level ({@code MAX_COMPOSED}); EQ3 is keyed by EQ6 too. */
    private static final List<List<String>> EQ1_MAX = List.of(
            List.of("AV:N/PR:N/UI:N"),
            List.of("AV:A/PR:N/UI:N", "AV:N/PR:L/UI:N", "AV:N/PR:N/UI:P"),
            List.of("AV:P/PR:N/UI:N", "AV:A/PR:L/UI:P"));

    private static final List<List<String>> EQ2_MAX = List.of(List.of("AC:L/AT:N"), List.of("AC:H/AT:N", "AC:L/AT:P"));

    private static final Map<String, List<String>> EQ3_EQ6_MAX = Map.of(
            "00", List.of("VC:H/VI:H/VA:H/CR:H/IR:H/AR:H"),
            "01", List.of("VC:H/VI:H/VA:L/CR:M/IR:M/AR:H", "VC:H/VI:H/VA:H/CR:M/IR:M/AR:M"),
            "10", List.of("VC:L/VI:H/VA:H/CR:H/IR:H/AR:H", "VC:H/VI:L/VA:H/CR:H/IR:H/AR:H"),
            "11",
                    List.of(
                            "VC:L/VI:H/VA:L/CR:H/IR:M/AR:H",
                            "VC:L/VI:H/VA:H/CR:H/IR:M/AR:M",
                            "VC:H/VI:L/VA:H/CR:M/IR:H/AR:M",
                            "VC:H/VI:L/VA:L/CR:M/IR:H/AR:H",
                            "VC:L/VI:L/VA:H/CR:H/IR:H/AR:M"),
            "21", List.of("VC:L/VI:L/VA:L/CR:H/IR:H/AR:H"));

    private static final List<List<String>> EQ4_MAX =
            List.of(List.of("SC:H/SI:S/SA:S"), List.of("SC:H/SI:H/SA:H"), List.of("SC:L/SI:L/SA:L"));

    /** The depth of each EQ level, in severity steps plus one ({@code MAX_SEVERITY}). */
    private static final int[] EQ1_DEPTH = {1, 4, 5};

    private static final int[] EQ2_DEPTH = {1, 2};
    private static final Map<String, Integer> EQ3_EQ6_DEPTH = Map.of("00", 7, "01", 6, "10", 8, "11", 8, "21", 10);
    private static final int[] EQ4_DEPTH = {6, 5, 4};

    private static final Map<String, Double> LOOKUP = readLookup();

    /**
     * The score of {@code vector} ({@code CVSS:4.0/AV:N/AC:L/...}), 0.0 to 10.0; null when it is not
     * a v4.0 vector carrying every base metric, no metric twice, and only metrics and values the
     * specification defines.
     */
    static @Nullable Double score(String vector) {
        String[] parts = vector.trim().split("/");
        if (!parts[0].equals("CVSS:4.0")) return null;
        Map<String, String> selected = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int colon = parts[i].indexOf(':');
            if (colon <= 0) return null;
            String key = parts[i].substring(0, colon);
            String value = parts[i].substring(colon + 1);
            List<String> allowed = METRICS.get(key);
            if (allowed == null || !allowed.contains(value) || selected.put(key, value) != null) return null;
        }
        if (!selected.keySet().containsAll(BASE)) return null;
        return score(selected);
    }

    private static double score(Map<String, String> selected) {
        if (List.of("VC", "VI", "VA", "SC", "SI", "SA").stream()
                .allMatch(k -> m(selected, k).equals("N"))) {
            return 0.0;
        }
        int[] eq = macroVector(selected);
        double value = lookup(eq);

        // The score of the next lower MacroVector along each EQ; NaN where there is none.
        double lowerEq1 = lookup(eq[0] + 1, eq[1], eq[2], eq[3], eq[4], eq[5]);
        double lowerEq2 = lookup(eq[0], eq[1] + 1, eq[2], eq[3], eq[4], eq[5]);
        double lowerEq3Eq6;
        if (eq[2] == 0 && eq[5] == 0) {
            double left = lookup(eq[0], eq[1], eq[2], eq[3], eq[4], eq[5] + 1);
            double right = lookup(eq[0], eq[1], eq[2] + 1, eq[3], eq[4], eq[5]);
            lowerEq3Eq6 = Math.max(left, right);
        } else if (eq[2] == 1 && eq[5] == 0) {
            lowerEq3Eq6 = lookup(eq[0], eq[1], eq[2], eq[3], eq[4], eq[5] + 1);
        } else if (eq[5] == 1 && eq[2] <= 1) {
            lowerEq3Eq6 = lookup(eq[0], eq[1], eq[2] + 1, eq[3], eq[4], eq[5]);
        } else {
            lowerEq3Eq6 = lookup(eq[0], eq[1], eq[2] + 1, eq[3], eq[4], eq[5] + 1);
        }
        double lowerEq4 = lookup(eq[0], eq[1], eq[2], eq[3] + 1, eq[4], eq[5]);
        double lowerEq5 = lookup(eq[0], eq[1], eq[2], eq[3], eq[4] + 1, eq[5]);

        // The distance of the vector from the first of its MacroVector's highest vectors it does not
        // exceed in any metric; when it exceeds every one, the last one's distance stands, as in the
        // calculator.
        String eq3Eq6 = "" + eq[2] + eq[5];
        Map<String, Double> distance = new HashMap<>();
        search:
        for (String eq1 : EQ1_MAX.get(eq[0])) {
            for (String eq2 : EQ2_MAX.get(eq[1])) {
                for (String eq36 : Objects.requireNonNull(EQ3_EQ6_MAX.get(eq3Eq6), eq3Eq6)) {
                    for (String eq4 : EQ4_MAX.get(eq[3])) {
                        Map<String, String> max = metrics(eq1 + "/" + eq2 + "/" + eq36 + "/" + eq4);
                        boolean below = true;
                        for (String k : DISTANCE) {
                            double d = level(k, m(selected, k)) - level(k, Objects.requireNonNull(max.get(k), k));
                            distance.put(k, d);
                            if (d < 0) below = false;
                        }
                        if (below) break search;
                    }
                }
            }
        }
        double distEq1 = d(distance, "AV") + d(distance, "PR") + d(distance, "UI");
        double distEq2 = d(distance, "AC") + d(distance, "AT");
        double distEq3Eq6 = d(distance, "VC")
                + d(distance, "VI")
                + d(distance, "VA")
                + d(distance, "CR")
                + d(distance, "IR")
                + d(distance, "AR");
        double distEq4 = d(distance, "SC") + d(distance, "SI") + d(distance, "SA");

        double step = 0.1;
        int lower = 0;
        double sum = 0;
        if (!Double.isNaN(value - lowerEq1)) {
            lower++;
            sum += (value - lowerEq1) * (distEq1 / (EQ1_DEPTH[eq[0]] * step));
        }
        if (!Double.isNaN(value - lowerEq2)) {
            lower++;
            sum += (value - lowerEq2) * (distEq2 / (EQ2_DEPTH[eq[1]] * step));
        }
        if (!Double.isNaN(value - lowerEq3Eq6)) {
            lower++;
            int depth = Objects.requireNonNull(EQ3_EQ6_DEPTH.get(eq3Eq6), eq3Eq6);
            sum += (value - lowerEq3Eq6) * (distEq3Eq6 / (depth * step));
        }
        if (!Double.isNaN(value - lowerEq4)) {
            lower++;
            sum += (value - lowerEq4) * (distEq4 / (EQ4_DEPTH[eq[3]] * step));
        }
        // EQ5's proportion is always 0; a lower MacroVector there still counts toward the mean.
        if (!Double.isNaN(value - lowerEq5)) lower++;

        value -= lower == 0 ? 0 : sum / lower;
        value = Math.clamp(value, 0.0, 10.0);
        // Half up to one decimal, lifted first so a tie one float step below x.x5 still rounds up.
        return Math.round((value + 1e-6) * 10) / 10.0;
    }

    /** The six EQ levels of {@code selected}. */
    private static int[] macroVector(Map<String, String> s) {
        String av = m(s, "AV"), pr = m(s, "PR"), ui = m(s, "UI");
        int eq1;
        if (av.equals("N") && pr.equals("N") && ui.equals("N")) eq1 = 0;
        else if ((av.equals("N") || pr.equals("N") || ui.equals("N")) && !av.equals("P")) eq1 = 1;
        else eq1 = 2;

        int eq2 = m(s, "AC").equals("L") && m(s, "AT").equals("N") ? 0 : 1;

        boolean vc = m(s, "VC").equals("H"),
                vi = m(s, "VI").equals("H"),
                va = m(s, "VA").equals("H");
        int eq3 = vc && vi ? 0 : vc || vi || va ? 1 : 2;

        int eq4;
        if (m(s, "MSI").equals("S") || m(s, "MSA").equals("S")) eq4 = 0;
        else if (m(s, "SC").equals("H") || m(s, "SI").equals("H") || m(s, "SA").equals("H")) eq4 = 1;
        else eq4 = 2;

        int eq5 =
                switch (m(s, "E")) {
                    case "A" -> 0;
                    case "P" -> 1;
                    default -> 2;
                };

        boolean eq6High = (m(s, "CR").equals("H") && vc)
                || (m(s, "IR").equals("H") && vi)
                || (m(s, "AR").equals("H") && va);
        return new int[] {eq1, eq2, eq3, eq4, eq5, eq6High ? 0 : 1};
    }

    /**
     * The value scoring reads for {@code metric}: a not-defined E, CR, IR or AR as its worst case, and
     * a modified metric that is defined in place of the base one.
     */
    private static String m(Map<String, String> selected, String metric) {
        String value = selected.getOrDefault(metric, "X");
        if (value.equals("X")) {
            switch (metric) {
                case "E" -> {
                    return "A";
                }
                case "CR", "IR", "AR" -> {
                    return "H";
                }
                default -> {}
            }
        }
        String modified = selected.get("M" + metric);
        return modified != null && !modified.equals("X") ? modified : value;
    }

    private static double level(String metric, String value) {
        Double level = Objects.requireNonNull(LEVELS.get(metric), metric).get(value);
        return level == null ? Double.NaN : level;
    }

    private static double d(Map<String, Double> distance, String metric) {
        return distance.getOrDefault(metric, Double.NaN);
    }

    private static Map<String, String> metrics(String vector) {
        Map<String, String> out = new HashMap<>();
        for (String part : vector.split("/")) {
            int colon = part.indexOf(':');
            out.put(part.substring(0, colon), part.substring(colon + 1));
        }
        return out;
    }

    private static double lookup(int... eq) {
        StringBuilder key = new StringBuilder(6);
        for (int level : eq) key.append(level);
        Double score = LOOKUP.get(key.toString());
        return score == null ? Double.NaN : score;
    }

    private static Map<String, Double> readLookup() {
        Map<String, Double> out = new HashMap<>();
        try (InputStream in = Objects.requireNonNull(
                        Cvss4.class.getResourceAsStream("cvss4-macrovectors.txt"), "cvss4-macrovectors.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.trim().split(" ");
                out.put(f[0], Double.parseDouble(f[1]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(out);
    }
}
