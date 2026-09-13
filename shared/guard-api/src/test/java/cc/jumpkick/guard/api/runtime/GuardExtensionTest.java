// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import cc.jumpkick.guard.api.Guard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * The extension is autodetected in tiers that run far more than guards. A plain test's failure
 * must survive it; only a {@link Guard}'s exception is the engine's to report.
 */
class GuardExtensionTest {

    /** An ordinary failing test running with the extension attached, as every test in an autodetecting tier does. */
    @ExtendWith(GuardExtension.class)
    static class PlainFailure {
        @Test
        void fails() {
            throw new AssertionError("the assertion the tier must see");
        }
    }

    /** A guard that throws, outside jk: the condition disables it rather than letting it run without views. */
    @ExtendWith(GuardExtension.class)
    static class ThrowingGuard {
        @Guard(id = "probe", why = "probe", instead = "probe")
        @Test
        void throwsAsAGuard() {
            throw new IllegalStateException("scanner failed");
        }
    }

    private static TestExecutionSummary run(Class<?> testClass) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(testClass))
                .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);
        return listener.getSummary();
    }

    @Test
    void a_plain_test_s_failure_is_still_a_failure_under_the_extension() {
        TestExecutionSummary summary = run(PlainFailure.class);
        assertThat(summary.getTestsFailedCount()).isEqualTo(1);
        assertThat(summary.getFailures().getFirst().getException()).hasMessage("the assertion the tier must see");
    }

    @Test
    void a_guard_outside_jk_is_skipped_not_failed() {
        TestExecutionSummary summary = run(ThrowingGuard.class);
        assertThat(summary.getTestsFailedCount()).isZero();
        assertThat(summary.getTestsSkippedCount()).isEqualTo(1);
    }
}
