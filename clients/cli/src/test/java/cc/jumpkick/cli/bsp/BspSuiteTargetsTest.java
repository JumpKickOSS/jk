// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.MiniJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A module's suites beside its default one are test targets of their own, which run that suite. */
class BspSuiteTargetsTest {

    @Test
    @SuppressWarnings("unchecked")
    void a_suite_target_tests_and_does_nothing_else() {
        Map<String, Object> target = (Map<String, Object>)
                requireNonNull(MiniJson.parse(BspServer.suiteTargetJson("file:///ws#app", "app", "integration")));

        assertThat((Map<String, Object>) target.get("id")).containsEntry("uri", "file:///ws#app::integration");
        assertThat(target.get("displayName")).isEqualTo("app · integration");
        assertThat((List<Object>) target.get("tags")).containsExactly("test");
        assertThat((List<Object>) target.get("languageIds")).isEmpty();
        assertThat((List<Object>) target.get("dependencies"))
                .singleElement()
                .isEqualTo(Map.of("uri", "file:///ws#app"));
        assertThat((Map<String, Object>) target.get("capabilities"))
                .containsEntry("canCompile", false)
                .containsEntry("canTest", true)
                .containsEntry("canRun", false);
    }

    @Test
    void testing_a_suite_target_selects_its_suite() {
        assertThat(BspServer.suiteOf("file:///ws#app::integration")).isEqualTo("integration");
        assertThat(BspServer.suiteOf("file:///ws#app")).isNull();

        TestSelection plain = TestSelection.DEFAULT.withClasses(List.of("FooIT"));
        TestSelection suite = BspServer.withTargetSuite(plain, List.of("file:///ws#app::integration"));
        assertThat(suite.suites()).containsExactly("integration");
        assertThat(suite.classes()).containsExactly("FooIT");

        assertThat(BspServer.withTargetSuite(plain, List.of("file:///ws#app"))).isSameAs(plain);
        TestSelection named = TestSelection.of(List.of("e2e"), false, List.of(), List.of());
        assertThat(BspServer.withTargetSuite(named, List.of("file:///ws#app::integration")))
                .as("suites the request named win")
                .isSameAs(named);
    }
}
