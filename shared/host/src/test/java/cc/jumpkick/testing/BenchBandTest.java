// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BenchBandTest {
    @Test
    void only_bench_rows_in_milliseconds_are_read_and_values_round_to_millis() {
        String toml = """
                # header

                [[row]]
                dim = "inner"
                subject = "inner.noop.wall"
                value = 0.98
                unit = "ratio"

                [[row]]
                dim = "bench"
                subject = "fqcn-shorten-16-files"
                project = "jk"
                value = 120
                unit = "ms"

                [[row]]
                dim = "bench"
                subject = "forked-javac-aot-on"
                value = 845.6
                unit = "ms"
                """;
        assertThat(BenchBand.parse(toml)).isEqualTo(Map.of("fqcn-shorten-16-files", 120L, "forked-javac-aot-on", 846L));
    }

    @Test
    void an_unbaselined_bench_has_no_banked_median() {
        assertThat(BenchBand.parse("[[row]]\ndim = \"wall\"\nsubject = \"wall.noop\"\nvalue = 1.0\nunit = \"s\"\n"))
                .isEmpty();
    }
}
