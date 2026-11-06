// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/** A class whose run abandoned a thread leaves the formatter reusable for the next class in the JVM. */
class LeftThreadsBehindBoundaryTest {

    @Test
    void a_class_after_a_timed_out_run_starts_with_a_reusable_formatter() {
        CodeFormatter.restoreLeftThreadsBehind(false);
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(LeavesAThreadBehind.class), selectClass(FindsItReusable.class))
                .configurationParameter(
                        "junit.jupiter.testclass.order.default", ClassOrderer.OrderAnnotation.class.getName())
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        TestExecutionSummary summary = listener.getSummary();

        assertThat(summary.getFailures())
                .extracting(f -> f.getTestIdentifier().getDisplayName() + ": " + f.getException())
                .isEmpty();
        assertThat(summary.getTestsSucceededCount()).isEqualTo(2);
        assertThat(new CodeFormatter().reusable()).isTrue();
    }

    /** Runs only inside the launcher above: what a run that gave up on a file leaves set. */
    @Disabled("run by LeftThreadsBehindBoundaryTest")
    @Order(1)
    static class LeavesAThreadBehind {

        @Test
        void leaves() {
            CodeFormatter.restoreLeftThreadsBehind(true);
            assertThat(new CodeFormatter().reusable()).isFalse();
        }
    }

    /** Runs only inside the launcher above, after the class that left a thread behind. */
    @Disabled("run by LeftThreadsBehindBoundaryTest")
    @Order(2)
    static class FindsItReusable {

        @Test
        void reusable() {
            assertThat(new CodeFormatter().reusable()).isTrue();
        }
    }
}
