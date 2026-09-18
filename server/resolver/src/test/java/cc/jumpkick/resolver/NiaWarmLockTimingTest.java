// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.host.HostProcessors;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolve.ResolveProfile;
import cc.jumpkick.testing.SysProps;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Local-only NIA lock phase timing (developer store). Not for CI by default.
 *
 * <p>Point {@value #OVERLAY_ENV} at a {@code nowinandroid} overlay directory (the one holding
 * {@code jk.toml}) to run it. Unset, it skips and says what to set.
 *
 * <p>The wall-clock budgets are judged only on a JVM that sees the whole host and a host that is
 * idle. {@code jk test} pins every test JVM to its share of the cores ({@code
 * -XX:ActiveProcessorCount}), which serialises the resolver's parallel prefetch and {@code .module}
 * parse into a machine no user has; a run under that pin prints its phase timings and says why the
 * budget was not applied. To measure plainly, lift the pin for this one class:
 *
 * <pre>{@code
 * JK_NIA_OVERLAY=/path/to/nowinandroid/overlay \
 *   jk test --profile network -m server/resolver -w 1 \
 *     --class cc.jumpkick.resolver.NiaWarmLockTimingTest \
 *     --jvm-arg -XX:ActiveProcessorCount=$(nproc)
 * }</pre>
 *
 * <p>The lock cost's gate is the wall-band harness, not this assert.
 */
@Tag("network")
@ExtendWith(SysProps.class)
class NiaWarmLockTimingTest {

    /** Absolute path to a local nowinandroid overlay checkout. No default: there is no right one. */
    private static final String OVERLAY_ENV = "JK_NIA_OVERLAY";

    @Test
    @Timeout(120)
    void warm_nia_lock_phases() throws Exception {
        String overlay = System.getenv(OVERLAY_ENV);
        assumeTrue(
                overlay != null && !overlay.isBlank(),
                OVERLAY_ENV + " is unset — set it to a local nowinandroid overlay dir to run this");
        Path nia = Path.of(overlay);
        assumeTrue(Files.isRegularFile(nia.resolve("jk.toml")), OVERLAY_ENV + "=" + overlay + " has no jk.toml");
        Path store = Path.of(System.getProperty("user.home"), ".jk/store");
        assumeTrue(Files.isDirectory(store));

        // Wall-clock budgets are only meaningful on an uncontended machine; a full parallel
        // suite run (~16 workers sharing CPU, disk, and network) blows them by 5x+ without any
        // resolver regression. Sample the load before the passes start, against the host's
        // processors: the JVM's own count is the launcher's pin, not the machine.
        double startLoad = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        int jvmCpus = Runtime.getRuntime().availableProcessors();
        int hostCpus = HostProcessors.count();
        System.out.println("JVM_CPUS=" + jvmCpus + " HOST_CPUS=" + hostCpus + " LOADAVG=" + startLoad);
        String budgetSkipReason = budgetSkipReason(startLoad, jvmCpus, hostCpus);

        // The overlay lives in jk-examples, not this repo — when its jk.toml lags a manifest
        // format change, that is fixture bit-rot, not a resolver regression: skip, don't fail.
        JkBuild root;
        Map<Path, JkBuild> modules;
        JkBuild project;
        try {
            root = JkBuildParser.parse(nia.resolve("jk.toml"));
            modules = WorkspaceLoader.loadModules(nia, root);
            project = WorkspaceMerge.merge(root, modules.values());
        } catch (JkBuildParseException e) {
            assumeTrue(false, "stale local NIA overlay: " + e.getMessage());
            return;
        }
        String jvmEnv = PluginContributions.jvmEnvironment(project, nia);
        System.out.println("JVM_ENV=" + jvmEnv + " modules=" + modules.size());

        Cas cas = new Cas(store);
        Http http = new Http();
        RepoGroup repos = new RepoGroup(List.of(
                new MavenRepo("central", URI.create("https://repo1.maven.org/maven2/"), http, cas),
                new MavenRepo("google", URI.create("https://dl.google.com/dl/android/maven2/"), http, cas)));

        System.setProperty("jk.resolve.profile", "true");

        // Pass A: true first-in-process (process caches cold).
        EffectivePomBuilder.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        KmpRedirects.clearProcessCache();
        ResolveProfile.reset();
        TimingObserver coldObs = new TimingObserver();
        long coldT0 = System.nanoTime();
        Lockfile cold = new LockOrchestrator(repos)
                .withProjectDir(nia)
                .withJvmEnvironment(jvmEnv)
                .lock(project, "nia-cold", List.of(), true, coldObs);
        long coldMs = (System.nanoTime() - coldT0) / 1_000_000L;
        System.out.println(
                "COLD_TOTAL_MS=" + coldMs + " packages=" + cold.artifacts().size());
        System.out.println("COLD_graphMs=" + coldObs.msUntilDownloadPhase.get() + " COLD_matMs="
                + coldObs.msInDownloadPhase.get());
        System.out.println("COLD_" + ResolveProfile.report());

        // Pass B: process caches hot.
        ResolveProfile.reset();
        TimingObserver hotObs = new TimingObserver();
        long hotT0 = System.nanoTime();
        Lockfile hot = new LockOrchestrator(repos)
                .withProjectDir(nia)
                .withJvmEnvironment(jvmEnv)
                .lock(project, "nia-hot", List.of(), true, hotObs);
        long hotMs = (System.nanoTime() - hotT0) / 1_000_000L;
        System.out.println(
                "HOT_TOTAL_MS=" + hotMs + " packages=" + hot.artifacts().size());
        System.out.println(
                "HOT_graphMs=" + hotObs.msUntilDownloadPhase.get() + " HOT_matMs=" + hotObs.msInDownloadPhase.get());
        System.out.println("HOT_" + ResolveProfile.report());

        assertThat(cold.artifacts().size()).isGreaterThan(200);
        assertThat(hot.artifacts().size()).isGreaterThan(200);
        if (budgetSkipReason == null) {
            // Hot re-lock must stay well under the 10s UX bar.
            assertThat(hotMs).as("hot re-lock").isLessThan(10_000L);
            // First-in-process (warm disk, cold process caches) targets ~8s; allow headroom for
            // shared-machine noise. Fail hard only if we regress toward the old ~25–40s path.
            assertThat(coldMs).as("first-in-process cold lock").isLessThan(15_000L);
        } else {
            System.out.println("TIMING_ASSERTS_SKIPPED " + budgetSkipReason);
        }
    }

    /**
     * Why the budget does not apply to this run, or {@code null} when it does: the JVM must see
     * every processor of the host (a pinned JVM measures a machine no user has) and the host must
     * be idle (a load average past half its processors is a shared box, not a regression).
     */
    static @Nullable String budgetSkipReason(double loadAvg, int jvmCpus, int hostCpus) {
        if (jvmCpus < hostCpus) {
            return "jvm-cpus=" + jvmCpus + " host-cpus=" + hostCpus
                    + " — the test JVM is pinned below the host; run with --jvm-arg -XX:ActiveProcessorCount="
                    + hostCpus;
        }
        if (loadAvg < 0 || loadAvg >= hostCpus * 0.5) {
            return "loadavg=" + loadAvg + " host-cpus=" + hostCpus + " — not a quiet machine";
        }
        return null;
    }

    private static final class TimingObserver implements ResolveObserver {
        final List<String> phases = new ArrayList<>();
        final AtomicInteger graphPackages = new AtomicInteger();
        final AtomicInteger materializePackages = new AtomicInteger();
        final AtomicLong msUntilDownloadPhase = new AtomicLong(-1);
        final AtomicLong msInDownloadPhase = new AtomicLong(-1);
        final long start = System.nanoTime();
        volatile long downloadPhaseStart = -1;

        @Override
        public void onTotal(int total) {}

        @Override
        public void onPhase(String label) {
            phases.add(label);
            long now = System.nanoTime();
            if (label != null && label.startsWith("Downloading") && downloadPhaseStart < 0) {
                downloadPhaseStart = now;
                msUntilDownloadPhase.set((now - start) / 1_000_000L);
            }
        }

        @Override
        public void onGraphPackage(String module, String version) {
            graphPackages.incrementAndGet();
        }

        @Override
        public void onPackage(String module, String version) {
            materializePackages.incrementAndGet();
            if (downloadPhaseStart > 0) {
                msInDownloadPhase.set((System.nanoTime() - downloadPhaseStart) / 1_000_000L);
            }
        }
    }
}
