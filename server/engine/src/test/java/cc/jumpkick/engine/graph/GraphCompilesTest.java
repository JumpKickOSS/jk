// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.host.time.Clock;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GraphCompilesTest {

    private static final Path ROOT = Path.of("/ws").toAbsolutePath();

    /** A clock a second ahead at every look, so a bounded wait ends after a few polls. */
    private static Clock ticking() {
        AtomicLong nanos = new AtomicLong();
        return new Clock() {
            @Override
            public long millis() {
                return nanos.get() / 1_000_000;
            }

            @Override
            public long nanos() {
                return nanos.addAndGet(1_000_000_000L);
            }
        };
    }

    private static HttpLive.Run building(String kind) {
        return new HttpLive.Run(
                7, 1, kind, ROOT.toString(), "g:a", null, null, 0, 0, 0.5, "r1", -1, -1, 0, 0, List.of(), List.of());
    }

    @Test
    void a_build_of_the_same_checkout_is_waited_for_briefly_then_the_question_answers_without() throws Exception {
        List<JobSpec> submitted = new ArrayList<>();
        GraphCompiles compiles = new GraphCompiles(
                spec -> {
                    submitted.add(spec);
                    return 9;
                },
                () -> List.of(building("build")),
                jid -> Optional.empty(),
                ticking());
        assertThat(compiles.compile(ROOT, List.of(), false)).isEqualTo("a build of this checkout is running");
        assertThat(submitted).isEmpty();
    }

    @Test
    void a_compile_is_submitted_as_a_graph_job_and_its_record_is_the_answer() throws Exception {
        List<JobSpec> submitted = new ArrayList<>();
        String green = "{\"success\":true,\"diagnostics\":[]}";
        GraphCompiles compiles = new GraphCompiles(
                spec -> {
                    submitted.add(spec);
                    return 9;
                },
                List::of,
                jid -> jid == 9 ? Optional.of(green) : Optional.empty(),
                ticking());
        assertThat(compiles.compile(ROOT, List.of("core"), false)).isNull();
        assertThat(compiles.compile(ROOT, List.of("app"), true)).isNull();
        assertThat(submitted).hasSize(2);
        assertThat(submitted.getFirst()).satisfies(spec -> {
            assertThat(spec.kind()).isEqualTo("compile");
            assertThat(spec.modules()).containsExactly("core");
            assertThat(spec.origin().trigger()).isEqualTo("graph");
        });
        assertThat(submitted.getLast()).satisfies(spec -> {
            assertThat(spec.kind()).as("tests that never compiled").isEqualTo("test-compile");
            assertThat(spec.modules()).containsExactly("app");
        });
    }

    @Test
    void a_compile_that_fails_says_where_in_one_line() {
        String red = "{\"success\":false,\"diagnostics\":[{\"severity\":\"warning\",\"message\":\"w\"},"
                + "{\"severity\":\"error\",\"file\":\"src/A.java\",\"line\":3,\"message\":\"';' expected\\nmore\"}]}";
        assertThat(GraphCompiles.verdict(red)).isEqualTo("the code does not compile (src/A.java:3: ';' expected)");
        assertThat(GraphCompiles.verdict("{\"success\":false}")).isEqualTo("the code does not compile");
    }

    @Test
    void main_sources_are_what_a_compile_brings_current() {
        assertThat(GraphRunner.mainSource("src/main/java/a/A.java", false)).isTrue();
        assertThat(GraphRunner.mainSource("src/test/java/a/ATest.java", false)).isFalse();
        assertThat(GraphRunner.mainSource("src/a/A.java", true)).isTrue();
        assertThat(GraphRunner.mainSource("test/src/a/ATest.java", true)).isFalse();
    }
}
