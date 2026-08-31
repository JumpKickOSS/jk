// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Every report ack must round-trip through {@link EngineProtocol#typeOf} or its client hangs. */
class ReportDiscriminatorTest {

    @Test
    void every_report_encodes_the_type_discriminator_its_client_matches() {
        assertThat(EngineProtocol.typeOf(WhyReport.error("x").encode())).isEqualTo(EngineProtocol.WHY_ACK);
        assertThat(EngineProtocol.typeOf(OutdatedReport.error("x").encode())).isEqualTo(EngineProtocol.OUTDATED_ACK);
        assertThat(EngineProtocol.typeOf(AffectedTestsReport.error("c", "x").encode()))
                .isEqualTo(EngineProtocol.AFFECTED_TESTS_ACK);
        assertThat(EngineProtocol.typeOf(IdeWireModel.error("x").encode())).isEqualTo(EngineProtocol.IDE_MODEL_ACK);
        assertThat(EngineProtocol.typeOf(ExecPlan.error("run", "x").encode())).isEqualTo(EngineProtocol.EXEC_PLAN_ACK);
        assertThat(EngineProtocol.typeOf(GeneratedFiles.error("x").encode())).isEqualTo(EngineProtocol.GENERATE_ACK);
        assertThat(EngineProtocol.typeOf(ProjectInfo.error("x").encode())).isEqualTo(EngineProtocol.PROJECT_INFO_ACK);
        assertThat(EngineProtocol.typeOf(DenyReport.error("x").encode())).isEqualTo(EngineProtocol.DENY_CHECK_ACK);
        assertThat(EngineProtocol.typeOf(PluginCommandReport.error("x").encode()))
                .isEqualTo(EngineProtocol.PLUGIN_VERB_ACK);
    }
}
