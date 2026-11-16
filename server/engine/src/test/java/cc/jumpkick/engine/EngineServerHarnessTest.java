// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The harness's own assertion: a failed build names why, not just that it failed. */
class EngineServerHarnessTest {

    private static final String STALE = "jk-lock.toml is stale: jk.toml changed since it was written,"
            + " and updating it failed: denied host repo.maven.apache.org";

    @Test
    void a_failed_build_carries_its_error_events() {
        List<String> lines = List.of(
                "{\"type\":\"module-start\",\"dir\":\"/p\"}",
                "{\"type\":\"output\",\"line\":\"compiling\"}",
                "{\"type\":\"task-finish\",\"name\":\"lock\",\"success\":false,\"error\":\"" + STALE + "\"}",
                "{\"type\":\"task-finish\",\"name\":\"compile-java\",\"success\":true}",
                "{\"type\":\"workspace-finish\",\"success\":false}");
        assertThatThrownBy(() -> EngineServerHarness.assertBuildSucceeded(lines, "the fixture build"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the fixture build did not succeed; its error events")
                .hasMessageContaining(STALE)
                .hasMessageNotContaining("compiling")
                .hasMessageNotContaining("compile-java");
    }

    @Test
    void a_failure_without_error_events_shows_the_transcript_tail() {
        List<String> lines = List.of("{\"type\":\"output\",\"line\":\"x\"}", "{\"type\":\"workspace-finish\"}");
        assertThatThrownBy(() -> EngineServerHarness.assertBuildSucceeded(lines, "b"))
                .hasMessageContaining("no error event, its last lines")
                .hasMessageContaining("\"line\":\"x\"");
    }

    @Test
    void a_successful_build_passes() {
        assertThatCode(() -> EngineServerHarness.assertBuildSucceeded(
                        List.of("{\"type\":\"workspace-finish\",\"success\":true}"), "b"))
                .doesNotThrowAnyException();
    }
}
