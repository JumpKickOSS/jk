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
        var published = SsePublisher.selectPublishedDiagnostics(in);
        long tests =
                published.stream().filter(d -> "test-failure".equals(d.code())).count();
        long javac = published.stream().filter(d -> "javac".equals(d.code())).count();
        assertThat(tests).isEqualTo(15);
        assertThat(javac).isEqualTo(SsePublisher.MAX_DIAGNOSTIC_EVENTS);
        assertThat(SsePublisher.unpublishedCount(in)).isEqualTo(12 - SsePublisher.MAX_DIAGNOSTIC_EVENTS);
    }

    @Test
    void pathological_test_failure_floods_are_bounded() {
        // A broken shared fixture failing thousands of tests must not stream unbounded
        // snippet+stack payloads onto the SSE card (JK-1880). The "+N more" line owns the rest.
        List<BuildPlanResult.Diagnostic> in = new ArrayList<>();
        for (int i = 0; i < SsePublisher.MAX_TEST_FAILURE_EVENTS + 250; i++) {
            in.add(new BuildPlanResult.Diagnostic("run-tests", "test-failure", "fail " + i));
        }
        in.add(new BuildPlanResult.Diagnostic("compile-main", "javac", "err"));

        var published = SsePublisher.selectPublishedDiagnostics(in);
        long tests =
                published.stream().filter(d -> "test-failure".equals(d.code())).count();
        assertThat(tests).isEqualTo(SsePublisher.MAX_TEST_FAILURE_EVENTS);
        // The first failures win (stable prefix), and non-test diagnostics still ride along.
        assertThat(published.getFirst().message()).isEqualTo("fail 0");
        assertThat(published.stream().filter(d -> "javac".equals(d.code())).count())
                .isEqualTo(1);
        assertThat(SsePublisher.unpublishedCount(in)).isEqualTo(250);
    }
}
