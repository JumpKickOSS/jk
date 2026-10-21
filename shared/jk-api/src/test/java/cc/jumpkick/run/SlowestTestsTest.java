// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SlowestTestsTest {

    private static TestClassResult cls(String name, long millis, TestClassResult.SlowMethod... slow) {
        return new TestClassResult("g:a", name, 1, 0, 0, millis, "fast", List.of(slow));
    }

    @Test
    void a_quick_suite_ranks_nothing() {
        SlowestTests slowest = new SlowestTests();
        slowest.add(cls("demo.A", 999, new TestClassResult.SlowMethod("a", 999)));
        slowest.add(cls("demo.B", 20));
        assertThat(slowest.ranking().isEmpty()).isTrue();
    }

    @Test
    void the_top_classes_and_methods_rank_longest_first_across_classes() {
        SlowestTests slowest = new SlowestTests();
        for (int i = 1; i <= 7; i++) {
            slowest.add(cls(
                    "demo.T" + i,
                    i * 1_000L,
                    new TestClassResult.SlowMethod("m" + i, i * 900L),
                    new TestClassResult.SlowMethod("n" + i, i * 500L)));
        }
        SlowestTests.Ranking ranking = slowest.ranking();
        assertThat(ranking.classes())
                .extracting(SlowestTests.Row::className)
                .containsExactly("demo.T7", "demo.T6", "demo.T5", "demo.T4", "demo.T3");
        assertThat(ranking.methods())
                .extracting(r -> r.className() + "#" + r.method())
                .as("a second method of one class outranks the first method of another")
                .containsExactly("demo.T7#m7", "demo.T6#m6", "demo.T5#m5", "demo.T4#m4", "demo.T7#n7");
        assertThat(ranking.methods().getFirst()).isEqualTo(new SlowestTests.Row("g:a", "demo.T7", "m7", "fast", 6_300));
    }

    @Test
    void a_method_name_drops_its_parameter_list_and_keeps_its_invocation() {
        assertThat(SlowestTests.methodName("boots(java.nio.file.Path)")).isEqualTo("boots");
        assertThat(SlowestTests.methodName("each(int)[#2]")).isEqualTo("each[#2]");
        assertThat(SlowestTests.methodName("plain")).isEqualTo("plain");
    }

    @Test
    void a_wall_reads_in_milliseconds_seconds_then_minutes() {
        assertThat(SlowestTests.duration(640)).isEqualTo("640ms");
        assertThat(SlowestTests.duration(1_440)).isEqualTo("1.4s");
        assertThat(SlowestTests.duration(125_000)).isEqualTo("2m 05s");
    }
}
