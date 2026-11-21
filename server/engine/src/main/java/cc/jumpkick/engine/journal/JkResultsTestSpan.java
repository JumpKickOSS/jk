// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.run.TaskNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The modules a run's test count spans, read from each module's {@code run-tests} step as the
 * terminal's workspace line reads them: a suite that ran or replayed its green run counts, and a
 * prerequisite {@code --exclusive} compiled without running its suite is counted apart.
 *
 * @param suites modules whose suite ran or was replayed
 * @param notTested prerequisites whose suite {@code --exclusive} kept from running
 */
record JkResultsTestSpan(int suites, int notTested) {

    static JkResultsTestSpan of(BuildRecord r) {
        List<List<BuildRecord.Task>> plans = new ArrayList<>();
        plans.add(r.steps());
        for (BuildRecord.Module m : r.modules()) plans.add(m.steps());
        int suites = 0;
        int notTested = 0;
        for (List<BuildRecord.Task> steps : plans) {
            for (BuildRecord.Task t : steps) {
                if (!TaskNames.RUN_TESTS.equals(t.name())) continue;
                String status = t.status() == null ? "" : t.status().strip().toUpperCase(Locale.ROOT);
                if ("SKIPPED".equals(status)) {
                    if (TaskNames.TESTS_NOT_SELECTED.equals(t.detail())) notTested++;
                    else if (TaskNames.TESTS_UP_TO_DATE.equals(t.detail())) suites++;
                } else if ("SUCCESS".equals(status) || "FAIL".equals(status)) {
                    suites++;
                }
            }
        }
        return new JkResultsTestSpan(suites, notTested);
    }

    /** {@code " in 5 modules"} beside a count that spans more than one module's suite; empty otherwise. */
    String modules() {
        return suites > 1 ? " in " + suites + " modules" : "";
    }

    /** {@code "3 prerequisites not tested (--exclusive)"}, the terminal's words; empty when there are none. */
    String untested() {
        if (notTested <= 0) return "";
        return notTested + " prerequisite" + (notTested == 1 ? "" : "s") + " not tested (--exclusive)";
    }
}
