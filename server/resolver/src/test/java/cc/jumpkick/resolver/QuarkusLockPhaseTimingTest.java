// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * wall-time breakdown of warm Quarkus locks (graph vs materialize). Uses the developer's
 * {@code ~/.cache/jk} so re-runs measure CAS-local materialize, not cold downloads.
 */
@Tag("integration")
class QuarkusLockPhaseTimingTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void warm_quarkus_lock_phases(@TempDir Path tmp) throws Exception {
        assumeTrue(Files.isDirectory(Path.of(System.getProperty("user.home"), ".jk/cache")));
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                name = "q-phase"
                group = "demo"
                version = "0.1.0"
                jdk = "25"
                java = 25

                [quarkus]
                version = "3.38.0"

                [dependencies]
                quarkus-rest = { group = "io.quarkus", name = "quarkus-rest" }
                quarkus-arc  = { group = "io.quarkus", name = "quarkus-arc" }

                [test-dependencies]
                quarkus-junit5 = { group = "io.quarkus", name = "quarkus-junit5" }
                rest-assured   = { group = "io.rest-assured", name = "rest-assured" }
                """);

        JkBuild project = JkBuildParser.parse(tmp.resolve("jk.toml"));
        Cas cas = new Cas(Path.of(System.getProperty("user.home"), ".jk/cache"));
        RepoGroup repos =
                RepoGroup.of(new MavenRepo("central", URI.create("https://repo1.maven.org/maven2/"), new Http(), cas));

        // Warm once (ignore timing).
        new LockOrchestrator(repos).withProjectDir(tmp).lock(project, "phase-warm");

        TimingObserver obs = new TimingObserver();
        long t0 = System.nanoTime();
        Lockfile lock =
                new LockOrchestrator(repos).withProjectDir(tmp).lock(project, "phase-measure", List.of(), true, obs);
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println(
                "TOTAL_MS=" + totalMs + " packages=" + lock.artifacts().size());
        System.out.println("PHASES=" + obs.phases);
        System.out.println(
                "graphPackages=" + obs.graphPackages.get() + " materializePackages=" + obs.materializePackages.get());
        System.out.println("msUntilDownloadPhase=" + obs.msUntilDownloadPhase.get() + " msInDownloadPhase="
                + obs.msInDownloadPhase.get());
        assertThat(lock.artifacts().size()).isGreaterThan(50);
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
