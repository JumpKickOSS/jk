// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.run.SlowestTests;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlowestTestsBlockTest {

    @Test
    void the_block_lists_classes_then_methods_with_wall_module_and_tier() {
        var ranking = new SlowestTests.Ranking(
                List.of(
                        new SlowestTests.Row("g:engine", "cc.demo.EngineE2eTest", "", "fast", 12_300),
                        new SlowestTests.Row("g:cli", "cc.demo.Outer$InnerTest", "", "fast", 8_100)),
                List.of(new SlowestTests.Row("g:engine", "cc.demo.EngineE2eTest", "boots[#2]", "fast", 4_200)));
        List<String> lines =
                SlowestTestsBlock.lines(ranking).stream().map(TestAnsi::strip).toList();
        assertThat(lines)
                .containsExactly(
                        "Slowest tests",
                        "  classes  12.3s  EngineE2eTest            g:engine · fast",
                        "            8.1s  Outer$InnerTest          g:cli · fast",
                        "  methods   4.2s  EngineE2eTest#boots[#2]  g:engine · fast");
    }

    @Test
    void nothing_slow_prints_nothing() {
        assertThat(SlowestTestsBlock.lines(SlowestTests.Ranking.EMPTY)).isEmpty();
    }
}
