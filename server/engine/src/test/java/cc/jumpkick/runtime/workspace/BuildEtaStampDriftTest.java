// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A module whose ONLY dirty step is its suite is not evidence of a suite to run.
 *
 * <p>That shape has two very different causes and they cost differently by orders of magnitude. A
 * test-only edit recompiles, so {@code compile-test} shows non-cached alongside the suite. A
 * run-tests stamp key that no longer matches what the live run stores shows nothing else at all —
 * and the forecast used to read the second as the first, pricing full suites for untouched modules.
 * On jk's own tree that was 27 modules including {@code shared/core}'s 1,189 tests, which became
 * the estimate's long pole: 31.2 s predicted against 17.5 s actual.
 *
 * <p>Being wrong by seconds when the suite really does run beats being wrong by minutes when it
 * does not, and the live run skipping it is the common case.
 */
class BuildEtaStampDriftTest {

    private static TaskForecast.Task task(String name, TaskForecast.Status st, String text) {
        return new TaskForecast.Task(name, st, text, null);
    }

    private static List<EffortWeights.ModuleCost> price(TaskForecast.Module m) throws Exception {
        var plan = new ExplainPlan(List.of(m), Map.of(m.dir(), Set.of()), 1, List.of());
        return SessionContext.where(
                Session.defaults(),
                () -> BuildService.etaCostsFromExplainPlan(
                        plan, Path.of("/tmp/jk-eta-stamp-drift-test"), 1, null, null, false, false, 0));
    }

    /** Only the suite is dirty and nothing else changed: a drifted stamp, not work. */
    @Test
    void a_suite_alone_is_not_priced_as_a_suite() throws Exception {
        var m = new TaskForecast.Module(
                Path.of("/host"),
                "g:host",
                List.of(
                        task("compile-main", TaskForecast.Status.CACHED, ""),
                        task("compile-test", TaskForecast.Status.CACHED, ""),
                        task("run-tests", TaskForecast.Status.RUN, "run tests · ~147 tests"),
                        task("package-jar", TaskForecast.Status.CACHED, "")),
                40,
                147,
                true,
                false);

        var cost = price(m).stream()
                .filter(c -> c.dir().equals(m.dir()))
                .findFirst()
                .orElseThrow();
        assertThat(cost.testWeight())
                .as("no other step changed, so the suite is a stamp miss rather than a suite")
                .isZero();
    }

    /**
     * The same shape with a different cause: nothing else changed because the last run was red and
     * the stamp is never green after a failure. The forecaster marks it, and the suite is priced.
     */
    @Test
    void a_rerun_after_a_red_suite_is_priced_as_a_suite() throws Exception {
        var m = new TaskForecast.Module(
                Path.of("/host"),
                "g:host",
                List.of(
                        task("compile-main", TaskForecast.Status.CACHED, ""),
                        task("compile-test", TaskForecast.Status.CACHED, ""),
                        task(
                                "run-tests",
                                TaskForecast.Status.RUN,
                                "run tests · ~147 tests · " + TaskForecast.LAST_RUN_FAILED),
                        task("package-jar", TaskForecast.Status.CACHED, "")),
                40,
                147,
                true,
                false);

        var cost = price(m).stream()
                .filter(c -> c.dir().equals(m.dir()))
                .findFirst()
                .orElseThrow();
        assertThat(cost.testWeight())
                .as("a red suite is never skipped by the live run, so it is a suite to price")
                .isGreaterThan(0);
    }

    /** A test-only edit recompiles the test sources — that IS evidence, so keep the full suite. */
    @Test
    void a_test_only_edit_still_prices_its_suite() throws Exception {
        var m = new TaskForecast.Module(
                Path.of("/host"),
                "g:host",
                List.of(
                        task("compile-main", TaskForecast.Status.CACHED, ""),
                        task("compile-test", TaskForecast.Status.PARTIAL, "compile · 3 sources changed"),
                        task("run-tests", TaskForecast.Status.RUN, "run tests · ~147 tests"),
                        task("package-jar", TaskForecast.Status.CACHED, "")),
                40,
                147,
                true,
                false);

        var cost = price(m).stream()
                .filter(c -> c.dir().equals(m.dir()))
                .findFirst()
                .orElseThrow();
        assertThat(cost.testWeight())
                .as("test sources were recompiled, so the suite really is going to run")
                .isGreaterThan(0);
    }
}
