// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The entry points and user-facing sentences of {@link LockOrchestrator} that no other suite reaches:
 * the keep-pins re-lock, the sources pass, the two diagnostics pinned in full, and the
 * materializer's first-failure-wins contract.
 */
class LockOrchestratorEntryPointsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private MavenStub upstream;

    @BeforeEach
    void publishJunitDefaults() {
        upstream = new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    /**
     * A keep-pins re-lock seeds the solver with the existing lock's versions, so a dependency the
     * new constraints do not touch stays where it was even though upstream has published a newer
     * release the selector would otherwise float to.
     */
    @Test
    void lock_conservative_keeps_an_untouched_locked_version_that_a_floating_lock_takes(@TempDir Path dir)
            throws Exception {
        upstream.metadata("com.foo", "lib", "1.0", "1.1")
                .pom("com.foo", "lib", "1.0", MavenStub.emptyPom("com.foo", "lib", "1.0"))
                .pom("com.foo", "lib", "1.1", MavenStub.emptyPom("com.foo", "lib", "1.1"));
        JkBuild project = project(
                Map.of(Scope.MAIN, List.of(new Dependency("com.foo:lib", VersionSelector.parseFloating("1.0")))));
        Lockfile existing = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        "com.foo:lib:jar:",
                        "1.0",
                        "maven-stub+" + http.base() + "/",
                        "sha256:0",
                        null,
                        List.of(Scope.MAIN),
                        List.of(),
                        null)));

        Lockfile floated = new LockOrchestrator(repos(dir.resolve("floated"))).lock(project, "test");
        Lockfile kept = new LockOrchestrator(repos(dir.resolve("kept")))
                .lockConservative(project, existing, "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(version(floated, "com.foo:lib:jar:")).isEqualTo("1.1");
        assertThat(version(kept, "com.foo:lib:jar:")).isEqualTo("1.0");
    }

    /**
     * The sources pass adds a {@code sourcesChecksum} to the Maven rows whose {@code -sources.jar}
     * exists and leaves every other row untouched: a package without sources, and a file dependency
     * that no repository serves at all.
     */
    @Test
    void lock_with_sources_records_a_checksum_only_for_rows_whose_sources_exist(@TempDir Path dir) throws Exception {
        upstream.leaf("com.foo", "documented", "1.0")
                .sourcesJar("com.foo", "documented", "1.0")
                .leaf("com.foo", "bare", "1.0");
        Dependency local = Dependency.file("thing", "com.local:thing", "1.0", "ab".repeat(32));
        JkBuild project = project(Map.of(
                Scope.MAIN,
                List.of(
                        new Dependency("com.foo:documented", VersionSelector.parse("=1.0")),
                        new Dependency("com.foo:bare", VersionSelector.parse("=1.0")),
                        local)));

        LockOrchestrator orchestrator = new LockOrchestrator(repos(dir));
        Lockfile lock =
                orchestrator.attachSources(orchestrator.lock(project, "test", List.of(), true, ResolveObserver.NOOP));

        assertThat(row(lock, "com.foo:documented:jar:").sourcesChecksum()).startsWith("sha256:");
        assertThat(row(lock, "com.foo:bare:jar:").sourcesChecksum()).isNull();
        assertThat(http.requestsFor(MavenStub.path("com.foo", "bare", "1.0", "-sources.jar")))
                .as("the pass asked, and a 404 is a quiet no")
                .isEqualTo(1);
        Lockfile.Artifact file = row(lock, "com.local:thing");
        assertThat(file.source()).isEqualTo(RepoArtifactResolver.JK_LOCAL);
        assertThat(file.checksum()).isEqualTo("sha256:" + "ab".repeat(32));
        assertThat(file.sourcesChecksum()).isNull();
    }

    @Test
    void two_platform_boms_that_disagree_are_named_in_one_sentence_with_both_versions(@TempDir Path dir) {
        upstream.pom(
                        "org.example",
                        "bom-a",
                        "1.0",
                        MavenStub.bom("org.example", "bom-a", "1.0", List.of("com.foo:widget:1.0")))
                .pom(
                        "org.example",
                        "bom-b",
                        "1.0",
                        MavenStub.bom("org.example", "bom-b", "1.0", List.of("com.foo:widget:2.0")));
        JkBuild project = project(Map.of(
                Scope.PLATFORM,
                List.of(
                        Dependency.of("bom-a", "org.example:bom-a", VersionSelector.parse("=1.0")),
                        Dependency.of("bom-b", "org.example:bom-b", VersionSelector.parse("=1.0")))));

        assertThatThrownBy(() -> new LockOrchestrator(repos(dir)).lock(project, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("platform BOM conflict on `com.foo:widget`: org.example:bom-a:1.0 constrains to 1.0,"
                        + " but org.example:bom-b:1.0 constrains to 2.0. Pick one BOM or pin the coord explicitly.");
    }

    /**
     * A package whose POM resolves but whose artifact no repository serves fails the lock with the
     * coordinate, the layout path tried, and every repository consulted — including the type and
     * classifier when the package is not a plain jar.
     */
    @Test
    void an_unfetchable_artifact_names_the_layout_path_and_every_repository_tried(@TempDir Path dir) {
        upstream.metadata("com.foo", "ghost", "1.0").pomWithoutJar("com.foo", "ghost", "1.0");
        String repo = "maven-stub+" + http.base() + "/";

        JkBuild plain =
                project(Map.of(Scope.MAIN, List.of(new Dependency("com.foo:ghost", VersionSelector.parse("=1.0")))));
        assertThatThrownBy(() -> new LockOrchestrator(repos(dir.resolve("plain"))).lock(plain, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("could not fetch artifact com.foo:ghost:1.0 at com/foo/ghost/1.0/ghost-1.0.jar (tried: "
                        + repo
                        + "com/foo/ghost/1.0/ghost-1.0.jar) — the POM resolved but the artifact is missing;"
                        + " check the coordinate and repositories");

        JkBuild tests = project(Map.of(
                Scope.MAIN,
                List.of(new Dependency("com.foo:ghost", VersionSelector.parse("=1.0"))
                        .withKind(DependencyKind.TESTS))));
        assertThatThrownBy(() -> new LockOrchestrator(repos(dir.resolve("tests"))).lock(tests, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("could not fetch artifact com.foo:ghost:1.0 type=test-jar classifier=tests"
                        + " at com/foo/ghost/1.0/ghost-1.0-tests.jar (tried: "
                        + repo
                        + "com/foo/ghost/1.0/ghost-1.0-tests.jar) — the POM resolved but the artifact is missing;"
                        + " check the coordinate and repositories");
    }

    /**
     * Parallel materialize: the first failure decides the outcome and escapes as its real cause,
     * but not before every sibling download has settled — a lock that returned while a sibling was
     * still writing into the store would hand the caller a store it was still mutating.
     */
    @Test
    void the_first_failure_escapes_as_its_real_cause_only_after_every_sibling_has_settled(@TempDir Path dir)
            throws Exception {
        upstream.leaf("com.foo", "slow", "1.0")
                .leaf("com.foo", "quick", "1.0")
                .metadata("com.foo", "ghost", "1.0")
                .pomWithoutJar("com.foo", "ghost", "1.0");
        String slowJar = MavenStub.path("com.foo", "slow", "1.0", ".jar");
        String ghostJar = MavenStub.path("com.foo", "ghost", "1.0", ".jar");
        CountDownLatch slowRequested = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        http.beforeServe(path -> {
            if (!path.equals(slowJar)) return;
            slowRequested.countDown();
            await(releaseSlow);
        });
        // The ghost's miss waits for the sibling to be mid-download, so the failure is always
        // known while a download is still in flight — whatever order the pool started them in.
        http.beforeMiss(path -> {
            if (path.equals(ghostJar)) await(slowRequested);
        });
        JkBuild project = project(Map.of(
                Scope.MAIN,
                List.of(
                        new Dependency("com.foo:slow", VersionSelector.parse("=1.0")),
                        new Dependency("com.foo:quick", VersionSelector.parse("=1.0")),
                        new Dependency("com.foo:ghost", VersionSelector.parse("=1.0")))));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread locking = Thread.ofPlatform().name("locking").start(() -> {
            try {
                new LockOrchestrator(repos(dir)).lock(project, "test");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        assertThat(slowRequested.await(30, TimeUnit.SECONDS)).isTrue();
        Await.until(Duration.ofSeconds(30), () -> http.requestsFor(ghostJar) >= 1);
        locking.join(1_000);
        assertThat(locking.isAlive())
                .as("the failure is known, but the lock waits for the sibling still downloading")
                .isTrue();
        releaseSlow.countDown();
        locking.join(30_000);

        assertThat(locking.isAlive()).isFalse();
        assertThat(thrown.get())
                .as("the real cause, not the abort noise a settled sibling reports")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("could not fetch artifact com.foo:ghost:1.0 ");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("latch never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private RepoGroup repos(Path dir) {
        // No ~/.m2 adoption: the download legs are what these tests observe.
        return RepoGroup.of(new MavenRepo(
                "maven-stub", http.base(), new Http(), new Cas(dir.resolve("cache")), RepoCredential.ANONYMOUS, false));
    }

    private static JkBuild project(Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(copy));
    }

    private static Lockfile.Artifact row(Lockfile lock, String name) {
        return lock.artifacts().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + name + " in " + lock.artifacts()));
    }

    private static String version(Lockfile lock, String name) {
        return row(lock, name).version();
    }
}
