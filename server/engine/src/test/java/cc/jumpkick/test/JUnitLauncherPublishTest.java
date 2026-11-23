// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Request;
import cc.jumpkick.orchestrator.ResultAggregator;
import cc.jumpkick.orchestrator.SuiteReport;
import cc.jumpkick.orchestrator.TestProgressListener;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run's tests join the request's results, the count {@code jk-results.md} reports, unless it says not to. */
class JUnitLauncherPublishTest {

    @Test
    void a_run_publishes_its_tests_to_the_request(@TempDir Path dir) {
        assertThat(published(new JUnitLauncher(), dir)).isEqualTo(1);
    }

    @Test
    void a_run_without_published_results_leaves_the_request_s_tests_alone(@TempDir Path dir) {
        assertThat(published(new JUnitLauncher().withoutPublishedResults(), dir))
                .isZero();
    }

    /** Test rows {@code launcher} published from a one-test run into a fresh request sink. */
    private static int published(JUnitLauncher launcher, Path dir) {
        RunResults sink = new RunResults();
        Request.detached().with(RunResults.SLOT, sink).run(() -> {
            var md = new MarkdownTestReport(false);
            var agg = new ResultAggregator(TestProgressListener.noop(), 0, SuiteReport.all(null, md), "");
            agg.accept("{\"event\":\"finished\",\"uniqueId\":\"[engine:junit-jupiter]/[class:A]/[method:a()]\","
                    + "\"testClass\":\"A\",\"testMethod\":\"a()\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
            launcher.publishTests(md, dir, null);
        });
        return sink.takeTests().stream().mapToInt(r -> r.entries().size()).sum();
    }
}
