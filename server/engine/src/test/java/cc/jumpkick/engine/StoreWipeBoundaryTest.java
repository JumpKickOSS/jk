// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import cc.jumpkick.util.StoreWriteGate;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/** A class that wipes the store leaves no wiped gate behind for the next class in the JVM. */
class StoreWipeBoundaryTest {

    @Test
    void a_feed_refresh_class_after_a_store_wipe_class_still_fetches() {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(WipesTheStore.class), selectClass(StoreFeedRefreshTest.class))
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
        assertThat(summary.getTestsSucceededCount())
                .as("the wipe and every feed refresh case ran")
                .isGreaterThan(1);
        assertThat(StoreWriteGate.wipedSinceStart()).isFalse();
    }

    /** Runs only inside the launcher above, ahead of the feed refresh class. */
    @Disabled("run by StoreWipeBoundaryTest")
    @Order(1)
    static class WipesTheStore {

        @Test
        void wipes() {
            try (var held = StoreWriteGate.wipe()) {
                assertThat(StoreWriteGate.wipedSinceStart()).isTrue();
            }
        }
    }
}
