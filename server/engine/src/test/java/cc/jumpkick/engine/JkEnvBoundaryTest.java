// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.Optional;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/** A class that sets {@code jk.env.*} overlays and never restores them leaves none behind for the next class. */
class JkEnvBoundaryTest {

    private static final String STORE = "jk.env.JK_STORE_DIR";
    private static final String ADDED = "jk.env.JK_ENV_BOUNDARY_PROBE";

    /** What {@link #STORE} held before the launcher ran; the reading class compares against it. */
    private static volatile Optional<String> original = Optional.empty();

    @Test
    void the_class_after_one_that_leaves_an_overlay_set_sees_the_original_value() {
        original = Optional.ofNullable(System.getProperty(STORE));
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(SetsOverlays.class), selectClass(ReadsOverlays.class))
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
        assertThat(summary.getTestsSucceededCount()).as("both classes ran").isEqualTo(2);
        assertThat(Optional.ofNullable(System.getProperty(STORE))).isEqualTo(original);
        assertThat(System.getProperty(ADDED)).isNull();
    }

    /** Runs only inside the launcher above, ahead of {@link ReadsOverlays}. */
    @Disabled("run by JkEnvBoundaryTest")
    @Order(1)
    static class SetsOverlays {

        @Test
        void sets_and_forgets() {
            System.setProperty(STORE, "/nowhere/a-leaked-store");
            System.setProperty(ADDED, "leaked");
        }
    }

    /** Runs only inside the launcher above, after {@link SetsOverlays}. */
    @Disabled("run by JkEnvBoundaryTest")
    @Order(2)
    static class ReadsOverlays {

        @Test
        void sees_the_original() {
            assertThat(Optional.ofNullable(System.getProperty(STORE))).isEqualTo(original);
            assertThat(System.getProperty(ADDED)).isNull();
        }
    }
}
