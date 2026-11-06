// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Profile;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * The integration tier of this checkout ({@code [profiles.integration]} in the root {@code jk.toml})
 * stops a hung test: the test fails, named, at the tier's default timeout, even when it is blocked
 * where an interrupt cannot reach. The fixture runs on a nested launcher with the tier's own JUnit
 * parameters and only the timeout shortened, so the proof costs a second.
 */
class IntegrationTierTimeoutTest {

    private static final String TIMEOUT = "junit.jupiter.execution.timeout.default";
    private static final String THREAD_MODE = "junit.jupiter.execution.timeout.thread.mode.default";
    private static final String THREAD_DUMP = "junit.jupiter.execution.timeout.threaddump.enabled";

    /** How long the fixture's blocked read lasts when nothing stops it. */
    private static final int HANG_MS = 60_000;

    private static volatile @Nullable ServerSocket door;

    @Test
    void a_hung_integration_test_fails_at_the_tier_timeout_naming_the_test() throws IOException {
        Map<String, String> tier = junitParameters(integrationProfile().jvmArgs());
        assertThat(tier).containsKey(TIMEOUT).containsEntry(THREAD_MODE, "SEPARATE_THREAD");
        Map<String, String> params = new LinkedHashMap<>(tier);
        params.put(TIMEOUT, "1s");
        params.remove(THREAD_DUMP);
        params.put("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition");
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(HangsOnABlockingAccept.class))
                .enableImplicitConfigurationParameters(false)
                .configurationParameters(params)
                .build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();

        long start = System.nanoTime();
        try {
            LauncherFactory.create().execute(request, listener);
        } finally {
            ServerSocket open = door;
            if (open != null) open.close();
        }
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        TestExecutionSummary summary = listener.getSummary();
        assertThat(summary.getTestsFailedCount()).isEqualTo(1);
        TestExecutionSummary.Failure failure = summary.getFailures().getFirst();
        assertThat(failure.getTestIdentifier().getDisplayName()).isEqualTo("hangs()");
        assertThat(failure.getException()).hasMessageContaining("hangs() timed out after 1 second");
        assertThat(tookMs)
                .describedAs("the run waited for the blocked accept instead of failing the test at its timeout")
                .isLessThan(HANG_MS / 2);
    }

    private static Profile integrationProfile() throws IOException {
        Profile profile = JkBuildParser.parse(RepoRoot.file(IntegrationTierTimeoutTest.class, "jk.toml"))
                .profiles()
                .byName()
                .get("integration");
        return Objects.requireNonNull(profile, "the root jk.toml declares no integration profile");
    }

    /** The {@code junit.*} system properties among {@code jvmArgs}, as JUnit configuration parameters. */
    private static Map<String, String> junitParameters(List<String> jvmArgs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String arg : jvmArgs) {
            if (!arg.startsWith("-Djunit.")) continue;
            int eq = arg.indexOf('=');
            if (eq > 0) out.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return out;
    }

    /** Runs only inside the launcher above: blocked in {@code accept}, which an interrupt does not wake. */
    @Disabled("run by IntegrationTierTimeoutTest")
    static class HangsOnABlockingAccept {

        @Test
        void hangs() throws IOException {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                door = socket;
                socket.setSoTimeout(HANG_MS);
                socket.accept().close();
            }
        }
    }
}
