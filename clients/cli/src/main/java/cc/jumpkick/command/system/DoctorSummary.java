// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The line under {@code jk doctor}'s rows: the checks printed, by status ({@code 12 ok, 3
 * warnings}), then the tool installs when there are any. The tool count alone read as if nothing
 * were healthy on a host with no tool installs ({@code 0 healthy} under a column of ok rows).
 */
final class DoctorSummary {

    private DoctorSummary() {}

    /** The line for {@code checks} and {@code tally}, each count passed through {@code number}. */
    static String line(List<DoctorCommand.Check> checks, DoctorCommand.Tally tally, UnaryOperator<String> number) {
        List<String> parts = new ArrayList<>();
        parts.add(number.apply(String.valueOf(count(checks, DoctorCommand.Status.OK))) + " ok");
        long warn = count(checks, DoctorCommand.Status.WARN);
        add(parts, number, warn, warn == 1 ? "warning" : "warnings");
        add(parts, number, count(checks, DoctorCommand.Status.FAIL), "failed");
        String line = String.join(", ", parts);
        if (tally.healthy() + tally.pruned() == 0) return line;
        List<String> tools = new ArrayList<>();
        tools.add(number.apply(String.valueOf(tally.healthy())) + " healthy");
        add(tools, number, tally.pruned(), "pruned");
        add(tools, number, tally.verified(), "unchanged");
        add(tools, number, tally.firstSeen(), "recorded");
        add(tools, number, tally.drifted(), "drifted");
        add(tools, number, tally.empty(), "empty");
        return line + " · tools: " + String.join(", ", tools);
    }

    private static long count(List<DoctorCommand.Check> checks, DoctorCommand.Status status) {
        return checks.stream().filter(c -> c.status() == status).count();
    }

    private static void add(List<String> parts, UnaryOperator<String> number, long n, String label) {
        if (n > 0) parts.add(number.apply(String.valueOf(n)) + " " + label);
    }
}
