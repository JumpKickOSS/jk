// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublishDiagnosticsSelectTest {

    @Test
    void keeps_every_test_failure_and_caps_other_errors() {
        List<BuildPlanResult.Diagnostic> in = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            in.add(new BuildPlanResult.Diagnostic("run-tests", "test-failure", "fail " + i));
        }
        for (int i = 0; i < 12; i++) {
            in.add(new BuildPlanResult.Diagnostic("compile-main", "javac", "err " + i));
        }
        var published = EngineServer.selectPublishedDiagnostics(in);
        long tests =
                published.stream().filter(d -> "test-failure".equals(d.code())).count();
        long javac = published.stream().filter(d -> "javac".equals(d.code())).count();
        assertThat(tests).isEqualTo(15);
        assertThat(javac).isEqualTo(EngineServer.MAX_DIAGNOSTIC_EVENTS);
        assertThat(EngineServer.unpublishedOtherCount(in)).isEqualTo(12 - EngineServer.MAX_DIAGNOSTIC_EVENTS);
    }
}
