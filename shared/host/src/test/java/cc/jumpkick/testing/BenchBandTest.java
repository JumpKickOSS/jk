// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BenchBandTest {
    @Test
    void only_bench_tables_are_read_and_medians_round_to_millis() {
        String toml = """
                # header
                [noop.jk]
                median-s = 2.00
                [bench.fqcn-shorten-16-files]
                median-ms = 120
                [bench.forked-javac-aot-on]
                median-ms = 845.6
                [touched.jk-guards]
                median-s = 30.0
                """;
        assertThat(BenchBand.parse(toml)).isEqualTo(Map.of("fqcn-shorten-16-files", 120L, "forked-javac-aot-on", 846L));
    }

    @Test
    void an_unbaselined_bench_has_no_banked_median() {
        assertThat(BenchBand.parse("[noop.jk]\nmedian-s = 1.0\n")).isEmpty();
    }
}
