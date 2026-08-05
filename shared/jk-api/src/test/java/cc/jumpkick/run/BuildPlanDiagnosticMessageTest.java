// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class BuildPlanDiagnosticMessageTest {

    @Test
    void closed_messages_include_exception_class() {
        assertThat(BuildPlan.diagnosticMessage(new IOException("closed"))).isEqualTo("closed (IOException)");
        assertThat(BuildPlan.diagnosticMessage(new IOException("Stream closed")))
                .contains("Stream closed")
                .contains("IOException");
        assertThat(BuildPlan.diagnosticMessage(new RuntimeException("real failure")))
                .isEqualTo("real failure");
        assertThat(BuildPlan.diagnosticMessage(new RuntimeException())).isEqualTo("RuntimeException");
    }
}
