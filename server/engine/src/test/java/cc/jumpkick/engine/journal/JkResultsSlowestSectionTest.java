// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.SlowestTests;
import cc.jumpkick.run.TestClassResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class JkResultsSlowestSectionTest {

    @Test
    void the_section_tables_the_slowest_classes_and_methods_with_module_and_tier() {
        SlowestTests slowest = new SlowestTests();
        slowest.add(new TestClassResult(
                "g:engine",
                "cc.demo.EngineE2eTest",
                3,
                0,
                0,
                12_300,
                "fast",
                List.of(new TestClassResult.SlowMethod("boots", 4_200))));
        slowest.add(new TestClassResult("g:cli", "cc.demo.QuickTest", 40, 0, 0, 300, "fast", List.of()));
        StringBuilder sb = new StringBuilder();
        JkResultsSlowestSection.append(sb, slowest.ranking());
        assertThat(sb.toString())
                .isEqualTo("## Slowest tests\n\n"
                        + "Classes and methods of this run that took 1.0s or more; a suite served from cache is not"
                        + " timed. To speed one up: share the fixture, seam the wait, or move it to a tier.\n\n"
                        + "| Class | Module | Tier | Took |\n|---|---|---|---|\n"
                        + "| `cc.demo.EngineE2eTest` | g:engine | fast | 12.3s |\n\n"
                        + "| Method | Module | Tier | Took |\n|---|---|---|---|\n"
                        + "| `cc.demo.EngineE2eTest#boots` | g:engine | fast | 4.2s |\n\n");
    }

    @Test
    void a_run_with_nothing_slow_has_no_section() {
        StringBuilder sb = new StringBuilder();
        JkResultsSlowestSection.append(sb, SlowestTests.Ranking.EMPTY);
        assertThat(sb).isEmpty();
        assertThat(JkResultsMarkdown.render(minimal())).doesNotContain("Slowest tests");
    }

    private static BuildRecord minimal() {
        return new BuildRecord(
                "id",
                1,
                BuildRecord.SCHEMA,
                "test",
                "/ws",
                "g:ws",
                "pid",
                1_000,
                1_100,
                100,
                true,
                false,
                0,
                "9.9",
                null,
                List.of(),
                List.of(),
                List.of(),
                "cli",
                null,
                null,
                null,
                false,
                null,
                0L);
    }
}
