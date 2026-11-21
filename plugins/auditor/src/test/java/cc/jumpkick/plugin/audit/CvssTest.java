// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Scores as the reference calculators give them for well-known vectors. */
class CvssTest {

    @Test
    void base_scores_match_the_specification() {
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isEqualTo(9.8);
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"))
                .isEqualTo(10.0);
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N"))
                .isEqualTo(4.3);
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AC:H/PR:N/UI:R/S:U/C:L/I:N/A:N"))
                .isEqualTo(3.1);
        assertThat(Cvss.baseScore("CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:H"))
                .isEqualTo(7.8);
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N"))
                .isEqualTo(6.1);
        assertThat(Cvss.baseScore("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N"))
                .isEqualTo(7.5);
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"))
                .isEqualTo(0.0);
    }

    @Test
    void temporal_metrics_after_the_base_ones_are_ignored() {
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H/E:U/RL:O/RC:C"))
                .isEqualTo(9.8);
    }

    @Test
    void a_vector_that_is_not_v3_or_v4_or_lacks_a_base_metric_has_no_score() {
        assertThat(Cvss.baseScore("AV:N/AC:L/Au:N/C:P/I:P/A:P")).isNull();
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H")).isNull();
        assertThat(Cvss.baseScore("CVSS:3.1/AV:X/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isNull();
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AV:L/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isNull();
        assertThat(Cvss.baseScore("")).isNull();
    }

    @Test
    void v4_scores_match_the_reference_calculator() {
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"))
                .isEqualTo(9.3);
        assertThat(Cvss.baseScore("CVSS:4.0/AV:L/AC:L/AT:N/PR:L/UI:N/VC:L/VI:N/VA:N/SC:N/SI:N/SA:N"))
                .isEqualTo(4.8);
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:H/AT:P/PR:L/UI:A/VC:L/VI:N/VA:N/SC:N/SI:N/SA:N"))
                .isEqualTo(2.0);
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:U"))
                .as("a threat metric counts")
                .isEqualTo(8.1);
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:N/SA:N"))
                .isEqualTo(0.0);
    }

    /** A score whose float lands one step below a tie at x.x5 still rounds half up. */
    @Test
    void a_v4_score_at_a_tie_rounds_up() {
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:P/VC:H/VI:L/VA:N/SC:H/SI:H/SA:H"))
                .isEqualTo(8.6);
    }

    /** Every vector of the reference set scores as the calculator scores it. */
    @Test
    void v4_scores_match_the_reference_vector_set() throws Exception {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        try (InputStream in =
                        Objects.requireNonNull(CvssTest.class.getResourceAsStream("cvss4-reference-vectors.txt"));
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int dash = line.lastIndexOf(" - ");
                String vector = line.substring(0, dash);
                double expected = Double.parseDouble(line.substring(dash + 3));
                Double actual = Cvss.baseScore(vector);
                if (actual == null || actual != expected) wrong.add(vector + " = " + actual + ", want " + expected);
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(3000);
        assertThat(wrong).isEmpty();
    }

    @Test
    void a_v4_vector_out_of_the_specification_has_no_score() {
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N"))
                .as("a base metric missing")
                .isNull();
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:X"))
                .as("a base metric not defined")
                .isNull();
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:Q"))
                .as("a value the metric does not take")
                .isNull();
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/ZZ:A"))
                .as("an unknown metric")
                .isNull();
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AV:L/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"))
                .as("a metric twice")
                .isNull();
        assertThat(Cvss.baseScore("CVSS:4.1/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"))
                .isNull();
    }

    @Test
    void ratings_follow_the_qualitative_scale() {
        assertThat(Cvss.label(9.0)).isEqualTo("CRITICAL");
        assertThat(Cvss.label(8.9)).isEqualTo("HIGH");
        assertThat(Cvss.label(7.0)).isEqualTo("HIGH");
        assertThat(Cvss.label(6.9)).isEqualTo("MEDIUM");
        assertThat(Cvss.label(4.0)).isEqualTo("MEDIUM");
        assertThat(Cvss.label(3.9)).isEqualTo("LOW");
        assertThat(Cvss.label(0.0)).isEqualTo("LOW");
    }
}
