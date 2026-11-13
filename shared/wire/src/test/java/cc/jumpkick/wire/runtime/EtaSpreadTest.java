// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.wire.protocol.EtaEvent;
import cc.jumpkick.wire.protocol.WorkspaceProgressEvent;
import org.junit.jupiter.api.Test;

/** The estimate's spread, range and confidence (JK-R0N5S). */
class EtaSpreadTest {

    @Test
    void the_confidence_is_the_chance_of_landing_within_ten_percent() {
        assertThat(EtaSpread.confidence(0)).isEqualTo(1);
        // Φ(ln 1.1 / 0.05) − Φ(ln 0.9 / 0.05)
        assertThat(EtaSpread.confidence(0.05)).isCloseTo(0.954, within(0.002));
        assertThat(EtaSpread.confidence(0.3)).isCloseTo(0.262, within(0.002));
        assertThat(EtaSpread.confidence(1.0)).isCloseTo(0.080, within(0.002));
    }

    @Test
    void the_normal_cdf_is_accurate() {
        assertThat(EtaSpread.phi(0)).isCloseTo(0.5, within(1e-7));
        assertThat(EtaSpread.phi(1.2815515655446004)).isCloseTo(0.9, within(1e-6));
        assertThat(EtaSpread.phi(-1.96)).isCloseTo(0.025, within(1e-4));
    }

    @Test
    void the_range_is_the_tenth_to_ninetieth_percentile() {
        assertThat(EtaSpread.low(100_000, 0.2)).isEqualTo(Math.round(100_000 * Math.exp(-EtaSpread.Z90 * 0.2)));
        assertThat(EtaSpread.high(100_000, 0.2)).isEqualTo(Math.round(100_000 * Math.exp(EtaSpread.Z90 * 0.2)));
        assertThat(EtaSpread.low(100_000, 0)).isEqualTo(100_000);
    }

    @Test
    void spreads_widen_in_quadrature_and_stay_bounded() {
        assertThat(EtaSpread.widen(0.3, 0.4)).isCloseTo(0.5, within(1e-12));
        assertThat(EtaSpread.widen(0)).isEqualTo(EtaSpread.MIN);
        assertThat(EtaSpread.widen(3)).isEqualTo(EtaSpread.MAX);
        assertThat(EtaSpread.clamp(Double.NaN)).isEqualTo(EtaSpread.MAX);
    }

    @Test
    void the_rung_defaults_widen_from_replay_to_calibration() {
        assertThat(EtaSpread.defaultFor(StepCost.Rung.REPLAY)).isLessThan(EtaSpread.defaultFor(StepCost.Rung.MODULE));
        assertThat(EtaSpread.defaultFor(StepCost.Rung.MODULE)).isLessThan(EtaSpread.defaultFor(StepCost.Rung.PROJECT));
        assertThat(EtaSpread.defaultFor(StepCost.Rung.PROJECT)).isLessThan(EtaSpread.defaultFor(StepCost.Rung.HOST));
        assertThat(EtaSpread.defaultFor(StepCost.Rung.HOST))
                .isLessThan(EtaSpread.defaultFor(StepCost.Rung.CALIBRATION));
    }

    @Test
    void the_confidence_rides_workspace_progress_and_eta_and_is_left_out_when_unknown() {
        var e = new WorkspaceProgressEvent(
                "/ws", 10, 100, 10.0, "execute", 1, 4, 30_000, 90_000, -1, 24_000, 39_000, 0.4372);
        String line = e.encode();
        assertThat(line).contains("\"confidence\":0.44");
        var back = WorkspaceProgressEvent.decode(line);
        assertThat(back.confidence()).isEqualTo(0.44);
        assertThat(back.etaLowMs()).isEqualTo(24_000);
        assertThat(back.etaHighMs()).isEqualTo(39_000);

        var none = new WorkspaceProgressEvent("/ws", 10, 100, 10.0, "execute", 1, 4, 30_000, 90_000);
        assertThat(none.encode()).doesNotContain("confidence");
        assertThat(WorkspaceProgressEvent.decode(none.encode()).confidence()).isEqualTo(-1);

        var eta = new EtaEvent(60_000, -1, 50_000, 75_000, 1.0);
        assertThat(eta.encode()).contains("\"confidence\":1");
        assertThat(EtaEvent.decode(eta.encode()).confidence()).isEqualTo(1.0);
    }
}
