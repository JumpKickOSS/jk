// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.PubGrubSolver;
import cc.jumpkick.resolver.pubgrub.Term;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class QuarkusJunit5ResolveTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void quarkus_junit5_alone_solves_fast(@TempDir Path tmp) throws Exception {
        Path cache = Path.of(System.getProperty("user.home"), ".jk/cache");
        Cas cas = new Cas(cache);
        RepoGroup repos =
                RepoGroup.of(new MavenRepo("central", URI.create("https://repo1.maven.org/maven2/"), new Http(), cas));
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(repos);
        Map<String, String> bom = new LinkedHashMap<>();
        for (var m : pomBuilder
                .build(Coordinate.of("io.quarkus.platform", "quarkus-bom", "3.28.5"))
                .managedDependencies()) {
            if (m.version() != null && !m.version().isBlank()) bom.putIfAbsent(m.module(), m.version());
        }
        AtomicInteger versions = new AtomicInteger();
        AtomicInteger deps = new AtomicInteger();
        MavenPackageSource inner = new MavenPackageSource(repos, pomBuilder, bom);
        var src = new cc.jumpkick.resolver.pubgrub.PackageSource() {
            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                versions.incrementAndGet();
                return inner.versions(pkg);
            }

            @Override
            public Optional<String> preferredVersion(String pkg) {
                return inner.preferredVersion(pkg);
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                deps.incrementAndGet();
                return inner.dependencies(pkg, version);
            }
        };
        String pkg = PackageId.ofGa("io.quarkus:quarkus-junit5").key();
        long t0 = System.nanoTime();
        Map<String, String> sol = new PubGrubSolver(src, 50_000, 15_000L)
                .solve("<root>", "0", List.of(Term.positive(pkg, VersionSet.exact("3.28.5"))));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println(
                "junit5 n=" + sol.size() + " ms=" + ms + " versions=" + versions.get() + " deps=" + deps.get());
        assertThat(sol).isNotEmpty();
        assertThat(ms).isLessThan(15_000L);
    }
}
