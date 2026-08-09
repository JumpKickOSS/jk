// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Local-only NIA lock phase timing (developer store). Not for CI by default. */
@Tag("integration")
class NiaWarmLockTimingTest {

    @Test
    @Timeout(120)
    void warm_nia_lock_phases() throws Exception {
        Path nia = Path.of("/home/bsant/src/oss/jk-examples/android/nowinandroid/overlay");
        assumeTrue(Files.isRegularFile(nia.resolve("jk.toml")));
        Path store = Path.of(System.getProperty("user.home"), ".local/share/jk/store");
        assumeTrue(Files.isDirectory(store));

        JkBuild root = JkBuildParser.parse(nia.resolve("jk.toml"));
        var modules = WorkspaceLoader.loadModules(nia, root);
        JkBuild project = WorkspaceMerge.merge(root, modules.values());
        String jvmEnv = PluginContributions.jvmEnvironment(project, nia);
        System.out.println("JVM_ENV=" + jvmEnv + " modules=" + modules.size());

        Cas cas = new Cas(store);
        Http http = new Http();
        RepoGroup repos = new RepoGroup(List.of(
                new MavenRepo("central", URI.create("https://repo1.maven.org/maven2/"), http, cas),
                new MavenRepo("google", URI.create("https://dl.google.com/dl/android/maven2/"), http, cas)));

        System.setProperty("jk.resolve.profile", "true");

        // Pass A: true first-in-process (process caches cold).
        cc.jumpkick.repo.EffectivePomBuilder.clearProcessCache();
        cc.jumpkick.repo.GradleModuleMetadata.clearParseCache();
        cc.jumpkick.repo.RepoGroup.clearProcessFetchCache();
        KmpRedirects.clearProcessCache();
        cc.jumpkick.resolve.ResolveProfile.reset();
        TimingObserver coldObs = new TimingObserver();
        long coldT0 = System.nanoTime();
        Lockfile cold = new LockOrchestrator(repos)
                .withProjectDir(nia)
                .withJvmEnvironment(jvmEnv)
                .lock(project, "nia-cold", List.of(), true, coldObs);
        long coldMs = (System.nanoTime() - coldT0) / 1_000_000L;
        System.out.println("COLD_TOTAL_MS=" + coldMs + " packages=" + cold.artifacts().size());
        System.out.println("COLD_graphMs=" + coldObs.msUntilDownloadPhase.get()
                + " COLD_matMs=" + coldObs.msInDownloadPhase.get());
        System.out.println("COLD_" + cc.jumpkick.resolve.ResolveProfile.report());

        // Pass B: process caches hot.
        cc.jumpkick.resolve.ResolveProfile.reset();
        TimingObserver hotObs = new TimingObserver();
        long hotT0 = System.nanoTime();
        Lockfile hot = new LockOrchestrator(repos)
                .withProjectDir(nia)
                .withJvmEnvironment(jvmEnv)
                .lock(project, "nia-hot", List.of(), true, hotObs);
        long hotMs = (System.nanoTime() - hotT0) / 1_000_000L;
        System.out.println("HOT_TOTAL_MS=" + hotMs + " packages=" + hot.artifacts().size());
        System.out.println("HOT_graphMs=" + hotObs.msUntilDownloadPhase.get()
                + " HOT_matMs=" + hotObs.msInDownloadPhase.get());
        System.out.println("HOT_" + cc.jumpkick.resolve.ResolveProfile.report());

        assertThat(cold.artifacts().size()).isGreaterThan(200);
        assertThat(hot.artifacts().size()).isGreaterThan(200);
        // Hot re-lock must stay well under the 10s UX bar.
        assertThat(hotMs).as("hot re-lock").isLessThan(10_000L);
        // First-in-process (warm disk, cold process caches) targets ~8s; allow headroom for
        // shared-machine noise. Fail hard only if we regress toward the old ~25–40s path.
        assertThat(coldMs).as("first-in-process cold lock").isLessThan(15_000L);
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
