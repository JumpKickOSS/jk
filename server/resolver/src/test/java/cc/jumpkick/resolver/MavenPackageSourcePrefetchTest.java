// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolve.ResolveProcessCacheControl;
import cc.jumpkick.resolver.pubgrub.Term;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The speculative warm-up runs ahead of the solver: every edge's catalog and likeliest POM are
 * read once the parent expands, and the roots are read before the first decide, so the solver's own
 * calls are memo hits. The queue is drained by a bounded set of workers, and the warm-up runs under
 * the caller's session, so {@code --offline} keeps it off the network.
 */
class MavenPackageSourcePrefetchTest {

    private static final String LIB_META = MavenStub.metadataPath("com.foo", "lib");
    private static final String LIB_POM = MavenStub.path("com.foo", "lib", "2.0", ".pom");
    private static final String LIB_DECLARED_POM = MavenStub.path("com.foo", "lib", "1.0", ".pom");

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void clear() {
        ResolveProcessCacheControl.clearAll();
        SessionContext.reset();
    }

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void an_expanded_edge_warms_the_child_catalog_and_the_version_its_declaration_steers_to(@TempDir Path tmp)
            throws Exception {
        upstream.pomOnly("com.foo", "widget", "1.0", pomDependingOnLib());
        upstream.metadata("com.foo", "lib", "1.0", "2.0");
        upstream.pomOnly("com.foo", "lib", "1.0", MavenStub.emptyPom("com.foo", "lib", "1.0"));
        upstream.pomOnly("com.foo", "lib", "2.0", MavenStub.emptyPom("com.foo", "lib", "2.0"));
        MavenPackageSource src = source(tmp);

        src.dependencies("com.foo:widget", "1.0");
        Await.until(Duration.ofSeconds(10), () -> http.requestsFor(LIB_DECLARED_POM) > 0);
        src.quiesce();

        assertThat(http.requestsFor(LIB_META)).as("the catalog was read ahead").isEqualTo(1);
        assertThat(http.requestsFor(LIB_DECLARED_POM))
                .as("the POM of the plain version the edge wrote was read ahead")
                .isEqualTo(1);
        assertThat(http.requestsFor(LIB_POM))
                .as("the newest release is not the solver's pick")
                .isZero();
        // The solver's own calls are memo hits.
        assertThat(src.versions("com.foo:lib:jar:")).containsExactly("2.0", "1.0");
        src.dependencies("com.foo:lib:jar:", "1.0");
        assertThat(http.requestsFor(LIB_META)).isEqualTo(1);
        assertThat(http.requestsFor(LIB_DECLARED_POM)).isEqualTo(1);
    }

    @Test
    void roots_are_warmed_before_the_first_decide(@TempDir Path tmp) throws Exception {
        upstream.metadata("com.foo", "lib", "1.0", "2.0");
        upstream.pomOnly("com.foo", "lib", "2.0", MavenStub.emptyPom("com.foo", "lib", "2.0"));
        MavenPackageSource src = source(tmp);

        src.prefetchRoots(List.of(Term.positive("com.foo:lib:jar:", VersionSet.atLeast("1.0", true))));
        Await.until(Duration.ofSeconds(10), () -> http.requestsFor(LIB_POM) > 0);
        src.quiesce();

        assertThat(http.requestsFor(LIB_META)).isEqualTo(1);
        assertThat(http.requestsFor(LIB_POM)).isEqualTo(1);
    }

    @Test
    void a_widening_pass_reads_every_catalog_at_once_and_then_from_memo(@TempDir Path tmp) throws Exception {
        upstream.metadata("com.foo", "lib", "1.0", "2.0");
        upstream.metadata("com.foo", "other", "3.0");
        MavenPackageSource src = source(tmp);

        src.warmExpandedVersions(List.of("com.foo:lib:jar:", "com.foo:other:jar:"));
        assertThat(src.expandedVersions("com.foo:lib:jar:")).containsExactly("2.0", "1.0");
        assertThat(src.expandedVersions("com.foo:other:jar:")).containsExactly("3.0");

        assertThat(http.requestsFor(LIB_META)).isEqualTo(1);
        assertThat(http.requestsFor(MavenStub.metadataPath("com.foo", "other"))).isEqualTo(1);
    }

    @Test
    void a_burst_of_submissions_runs_on_a_bounded_set_of_workers(@TempDir Path tmp) throws Exception {
        for (int i = 0; i < 200; i++) upstream.pomWithoutJar("com.foo", "lib" + i, "1.0");
        MavenPackageSource src = source(tmp);
        List<Term> roots = new ArrayList<>();
        for (int i = 0; i < 200; i++) roots.add(Term.positive("com.foo:lib" + i + ":jar:", VersionSet.exact("1.0")));

        src.prefetchRoots(roots);
        assertThat(src.prefetchWorkersRunning()).isLessThanOrEqualTo(32);
        // Draining, not dropping: quiesce discards what has not started, so wait for the workers,
        // which leave only once the queue is empty.
        Await.until(Duration.ofSeconds(30), () -> src.prefetchWorkersRunning() == 0);
        src.quiesce();

        assertThat(src.prefetchWorkersRunning()).isZero();
        for (int i = 0; i < 200; i++) {
            assertThat(http.requestsFor(MavenStub.path("com.foo", "lib" + i, "1.0", ".pom")))
                    .isEqualTo(1);
        }
    }

    @Test
    void an_offline_session_keeps_the_warm_up_off_the_network(@TempDir Path tmp) throws Exception {
        upstream.metadata("com.foo", "lib", "1.0", "2.0");
        upstream.pomOnly("com.foo", "lib", "2.0", MavenStub.emptyPom("com.foo", "lib", "2.0"));
        Cas cas = new Cas(tmp.resolve("cache"));
        MavenRepo repo = new MavenRepo("local", http.base(), new Http(), cas, RepoCredential.ANONYMOUS, false);
        // The parent is in the store; only its child would reach out.
        String pom = pomDependingOnLib();
        Path source = Files.writeString(tmp.resolve("widget.pom"), pom);
        RepoArtifactStore.forRepository(cas.root(), "local", repo.baseUrl())
                .materialize(
                        MavenLayout.pomPath(Coordinate.of("com.foo", "widget", "1.0")),
                        source,
                        Hashing.sha256Hex(pom.getBytes(StandardCharsets.UTF_8)));
        MavenPackageSource src = new MavenPackageSource(RepoGroup.of(repo), new EffectivePomBuilder(repo));

        Session offline = Session.defaults().withConfig(JkConfig.empty().withOffline(true));
        List<Term> edges = SessionContext.where(offline, () -> {
            List<Term> out = src.dependencies("com.foo:widget", "1.0");
            // Let the queued item run before asking what it touched.
            Await.until(Duration.ofSeconds(10), () -> src.prefetchWorkersRunning() == 0);
            src.quiesce();
            return out;
        });

        assertThat(edges).extracting(Term::pkg).containsExactly("com.foo:lib:jar:");
        assertThat(http.requested()).as("no warm-up leg reached the network").isEmpty();
    }

    private static String pomDependingOnLib() {
        return """
                <project>
                  <groupId>com.foo</groupId><artifactId>widget</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.foo</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """;
    }

    @Test
    void the_warm_queue_is_drained_by_half_as_many_workers_as_there_are_download_slots() {
        assertThat(MavenPackageSource.prefetchWorkers(64)).isEqualTo(32);
        assertThat(MavenPackageSource.prefetchWorkers(16)).isEqualTo(8);
        assertThat(MavenPackageSource.prefetchWorkers(1))
                .as("never fewer than one")
                .isEqualTo(1);
        assertThat(MavenPackageSource.prefetchWorkers(4096))
                .as("the cap holds on a host whose slots outnumber what a repository wants asked")
                .isEqualTo(MavenPackageSource.PREFETCH_WORKERS_CAP);
    }

    private MavenPackageSource source(Path tmp) {
        Cas cas = new Cas(tmp.resolve("cache"));
        MavenRepo repo = new MavenRepo("local", http.base(), new Http(), cas, RepoCredential.ANONYMOUS, false);
        return new MavenPackageSource(repo, new EffectivePomBuilder(repo));
    }
}
