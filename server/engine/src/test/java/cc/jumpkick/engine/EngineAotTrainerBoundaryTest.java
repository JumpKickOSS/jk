// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

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

/** A class whose engine shut down leaves no trainer latch set for the next class in the JVM. */
class EngineAotTrainerBoundaryTest {

    private static final EngineAotTrainer.Latches UNSET = new EngineAotTrainer.Latches(false, false);

    @Test
    void a_class_after_an_engine_shutdown_starts_with_the_trainer_latches_unset() {
        EngineAotTrainer.restore(UNSET);
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(StopsTheTrainer.class), selectClass(FindsTheLatchesUnset.class))
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
        assertThat(EngineAotTrainer.latches()).isEqualTo(UNSET);
    }

    /** Runs only inside the launcher above: an engine shutdown, after a recording was tried. */
    @Disabled("run by EngineAotTrainerBoundaryTest")
    @Order(1)
    static class StopsTheTrainer {

        @Test
        void stops() {
            EngineAotTrainer.restore(new EngineAotTrainer.Latches(true, false));
            assertThat(EngineAotTrainer.stop()).isTrue();
            assertThat(EngineAotTrainer.latches()).isEqualTo(new EngineAotTrainer.Latches(true, true));
        }
    }

    /** Runs only inside the launcher above, after the class that stopped the trainer. */
    @Disabled("run by EngineAotTrainerBoundaryTest")
    @Order(2)
    static class FindsTheLatchesUnset {

        @Test
        void unset() {
            assertThat(EngineAotTrainer.latches()).isEqualTo(UNSET);
        }
    }
}
