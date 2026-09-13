// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import cc.jumpkick.guard.api.Guard;
import org.junit.jupiter.api.Disabled;
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

    /**
     * An ordinary failing test running with the extension attached, as every test in an autodetecting
     * tier does. {@code @Disabled} keeps the build's own discovery off it; the launcher below
     * deactivates that condition and runs it on purpose.
     */
    @Disabled("a fixture the launcher runs; never a test of its own")
    @ExtendWith(GuardExtension.class)
    static class PlainFailure {
        @Test
        void fails() {
            throw new AssertionError("the assertion the tier must see");
        }
    }

    /**
     * A guard that throws. Outside jk the extension's own condition skips it (no views to hand it);
     * once a runtime is installed in the JVM — by jk, or by another test in the same run — it runs
     * and its exception is recorded for the engine's report. Neither outcome is a failure.
     */
    @Disabled("a fixture the launcher runs; never a test of its own")
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
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
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
        assertThat(summary.getFailures().get(0).getException()).hasMessage("the assertion the tier must see");
    }

    /**
     * The verdict on a guard is the engine's, never JUnit's: whether the runtime is absent (the guard
     * is skipped) or installed (its exception is recorded and swallowed), the tier does not go red.
     * {@link GuardRuntime#install} is process-wide and other tests in this JVM call it, so the test
     * asserts the invariant that holds either way rather than the order it happened to run in.
     */
    @Test
    void a_throwing_guard_is_never_the_tier_s_failure() {
        TestExecutionSummary summary = run(ThrowingGuard.class);
        assertThat(summary.getTestsFoundCount()).isEqualTo(1);
        assertThat(summary.getTestsFailedCount()).isZero();
        assertThat(summary.getTestsSkippedCount() + summary.getTestsSucceededCount())
                .isEqualTo(1);
    }
}
