// SPDX-License-Identifier: Apache-2.0

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoverageBandTest {
    private val report =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <report name="engine">
          <package name="cc/jumpkick/a">
            <counter type="LINE" missed="10" covered="90"/>
          </package>
          <package name="cc/jumpkick/b">
            <counter type="LINE" missed="30" covered="70"/>
          </package>
          <counter type="INSTRUCTION" missed="1" covered="2"/>
          <counter type="LINE" missed="40" covered="160"/>
          <counter type="METHOD" missed="1" covered="2"/>
        </report>
        """
            .trimIndent()

    @Test
    fun the_report_total_is_the_last_line_counter_not_a_package_one() {
        val m = CoverageBand.parseReport("server/engine", report)!!
        assertThat(m.covered).isEqualTo(160)
        assertThat(m.missed).isEqualTo(40)
        assertThat(m.percent).isEqualTo(80.0)
    }

    @Test
    fun a_report_without_line_counters_measures_nothing() {
        assertThat(CoverageBand.parseReport("x", "<report/>")).isNull()
    }

    @Test
    fun a_module_forced_below_its_line_is_a_regression_and_fails() {
        val baseline = CoverageBand.parseBaseline("# header\nserver/engine 81.0\n")
        val verdicts = CoverageBand.judge(baseline, listOf(CoverageBand.Measured("server/engine", 160, 40)))
        assertThat(verdicts.single().kind).isEqualTo(CoverageBand.Kind.REGRESSION)
        assertThat(CoverageBand.regressions(verdicts))
            .containsExactly("server/engine: 80.0% line coverage, baseline 81.0% (band 0.5)")
        // The bar does not move down: the rendered file keeps the recorded line.
        assertThat(CoverageBand.render("# header", baseline, verdicts)).isEqualTo("# header\nserver/engine 81.0\n")
    }

    @Test
    fun within_the_band_holds_and_the_file_is_unchanged() {
        val baseline = CoverageBand.parseBaseline("server/engine 80.3\n")
        val verdicts = CoverageBand.judge(baseline, listOf(CoverageBand.Measured("server/engine", 160, 40)))
        assertThat(verdicts.single().kind).isEqualTo(CoverageBand.Kind.HOLD)
        assertThat(CoverageBand.render("# h", baseline, verdicts)).isEqualTo("# h\nserver/engine 80.3\n")
    }

    @Test
    fun an_improvement_rewrites_the_line_in_the_same_run_and_a_new_module_is_added() {
        val baseline = CoverageBand.parseBaseline("server/engine 70.0\nshared/host 90.0\n")
        val verdicts =
            CoverageBand.judge(
                baseline,
                listOf(
                    CoverageBand.Measured("server/engine", 160, 40),
                    CoverageBand.Measured("plugins/protobuf", 50, 50),
                    CoverageBand.Measured("clients/web", 0, 0),
                ),
            )
        assertThat(verdicts.map { it.module to it.kind })
            .containsExactly(
                "plugins/protobuf" to CoverageBand.Kind.ADDED,
                "server/engine" to CoverageBand.Kind.IMPROVED,
            )
        assertThat(CoverageBand.regressions(verdicts)).isEmpty()
        assertThat(CoverageBand.render("# h", baseline, verdicts))
            .isEqualTo("# h\nplugins/protobuf 50.0\nserver/engine 80.0\nshared/host 90.0\n")
    }

    @Test
    fun a_malformed_baseline_line_is_refused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            CoverageBand.parseBaseline("server/engine eighty\n")
        }
    }
}
