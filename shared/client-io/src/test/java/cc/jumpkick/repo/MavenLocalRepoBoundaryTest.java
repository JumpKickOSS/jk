// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

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

/** A class that points the local-repository property at its own directory and never restores it leaves the next class the original. */
class MavenLocalRepoBoundaryTest {

    /** What the property held before the launcher ran; the reading class compares against it. */
    private static volatile Optional<String> original = Optional.empty();

    @Test
    void the_class_after_one_that_leaves_the_local_repository_property_set_sees_the_original_value() {
        original = Optional.ofNullable(System.getProperty(M2Dirs.LOCAL_PROPERTY));
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(SetsLocalRepo.class), selectClass(ReadsLocalRepo.class))
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
        assertThat(Optional.ofNullable(System.getProperty(M2Dirs.LOCAL_PROPERTY)))
                .isEqualTo(original);
    }

    /** Runs only inside the launcher above, ahead of {@link ReadsLocalRepo}. */
    @Disabled("run by MavenLocalRepoBoundaryTest")
    @Order(1)
    static class SetsLocalRepo {

        @Test
        void sets_and_forgets() {
            System.setProperty(M2Dirs.LOCAL_PROPERTY, "/nowhere/a-leaked-repository");
        }
    }

    /** Runs only inside the launcher above, after {@link SetsLocalRepo}. */
    @Disabled("run by MavenLocalRepoBoundaryTest")
    @Order(2)
    static class ReadsLocalRepo {

        @Test
        void sees_the_original() {
            assertThat(Optional.ofNullable(System.getProperty(M2Dirs.LOCAL_PROPERTY)))
                    .isEqualTo(original);
        }
    }
}
