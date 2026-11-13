// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.SuiteSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A suite's own JVM settings key the runs that run it, and only those. */
class SuiteRunStampTest {

    private static BuildBlock build(String integrationEnv) {
        Map<String, DeclaredSuite> suites = new LinkedHashMap<>();
        suites.put(
                "integration",
                new DeclaredSuite(
                        List.of(),
                        List.of("integration"),
                        List.of(),
                        List.of(),
                        new SuiteSettings(Map.of("DENY", integrationEnv), List.of(), Map.of(), 300_000L),
                        false));
        suites.put(
                "live",
                new DeclaredSuite(
                        List.of(),
                        List.of("live"),
                        List.of(),
                        List.of(),
                        new SuiteSettings(Map.of("KEYCHAIN", "1"), List.of(), Map.of(), null),
                        true));
        return BuildBlock.EMPTY.withTestSuites(suites);
    }

    @Test
    void a_selected_suites_settings_key_its_run_and_an_unselected_ones_do_not() {
        TestSelection gate = TestSelection.of(List.of("test", "integration"), false, List.of(), List.of());
        assertThat(TestStampInputs.suiteRunExtras(build("a"), gate))
                .singleElement()
                .asString()
                .startsWith("suite-run:integration=")
                .contains("timeout.default=300000ms");
        assertThat(TestStampInputs.suiteRunExtras(build("a"), gate))
                .isNotEqualTo(TestStampInputs.suiteRunExtras(build("b"), gate));
        assertThat(TestStampInputs.suiteRunExtras(build("a"), TestSelection.DEFAULT))
                .as("the default suite's run does not move when integration's env does")
                .isEqualTo(TestStampInputs.suiteRunExtras(build("b"), TestSelection.DEFAULT))
                .isEmpty();
    }

    @Test
    void every_suite_but_the_explicit_ones_runs_under_all() {
        TestSelection all = TestSelection.of(List.of(), true, List.of(), List.of());
        assertThat(TestStampInputs.suiteRunExtras(build("a"), all))
                .singleElement()
                .asString()
                .startsWith("suite-run:integration=");
        assertThat(TestStampInputs.suiteRunExtras(
                        build("a"), TestSelection.of(List.of("live"), false, List.of(), List.of())))
                .singleElement()
                .asString()
                .startsWith("suite-run:live=");
    }
}
