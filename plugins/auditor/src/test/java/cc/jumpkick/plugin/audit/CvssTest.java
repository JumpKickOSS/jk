// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Base scores as the FIRST calculator gives them for well-known vectors. */
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
    void a_vector_that_is_not_v3_or_lacks_a_base_metric_has_no_score() {
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"))
                .isNull();
        assertThat(Cvss.baseScore("AV:N/AC:L/Au:N/C:P/I:P/A:P")).isNull();
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H")).isNull();
        assertThat(Cvss.baseScore("CVSS:3.1/AV:X/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isNull();
        assertThat(Cvss.baseScore("CVSS:3.1/AV:N/AV:L/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isNull();
        assertThat(Cvss.baseScore("")).isNull();
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
