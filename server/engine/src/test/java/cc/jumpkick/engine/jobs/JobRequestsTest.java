// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The job-request decoder every surface shares: names, types and defaults. */
class JobRequestsTest {

    private static JobSpec decode(Map<String, ?> fields) {
        return JobRequests.decode(fields, JobOrigin.WEB, "build", null, Set.of());
    }

    @Test
    void lists_take_an_array_or_one_comma_separated_string() {
        assertThat(decode(Map.of("dir", "/w", "modules", "a, b,")).modules()).containsExactly("a", "b");
        assertThat(decode(Map.of("dir", "/w", "modules", List.of("a", "b"))).modules())
                .containsExactly("a", "b");
    }

    @Test
    void defaults_fill_what_the_request_leaves_out() {
        JobSpec spec = JobRequests.decode(Map.of(), JobOrigin.mcp("s"), "test", "/bound", Set.of());
        assertThat(spec.kind()).isEqualTo("test");
        assertThat(spec.dir()).isEqualTo("/bound");
        assertThat(spec.deadlineMs()).isNull();
        assertThat(spec.origin()).isEqualTo(JobOrigin.mcp("s"));
    }

    @Test
    void a_caller_field_is_skipped_and_any_other_unknown_is_refused() {
        assertThat(JobRequests.decode(Map.of("dir", "/w", "goal", "g"), JobOrigin.WEB, "build", null, Set.of("goal"))
                        .dir())
                .isEqualTo("/w");
        assertThatThrownBy(() -> decode(Map.of("dir", "/w", "goal", "g", "only", "a")))
                .hasMessage("unknown fields goal, only");
    }

    @Test
    void wrong_types_and_a_missing_dir_name_the_field() {
        assertThatThrownBy(() -> decode(Map.of())).hasMessageContaining("dir");
        assertThatThrownBy(() -> decode(Map.of("dir", "/w", "deadline_s", 1.5))).hasMessageContaining("deadline_s");
        assertThatThrownBy(() -> decode(Map.of("dir", "/w", "deadline_s", -1))).hasMessageContaining("deadline_s");
        assertThatThrownBy(() -> decode(Map.of("dir", "/w", "affected", "maybe")))
                .hasMessageContaining("affected");
        assertThatThrownBy(() -> decode(Map.of("dir", "/w", "classes", 3))).hasMessageContaining("classes");
    }
}
