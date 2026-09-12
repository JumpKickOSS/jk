// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.repo.RepoTransports;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a lock pins is only as good as what vouched for the bytes: a repository that publishes no
 * checksum sidecar cannot be locked from unless its table says so, and the run's summary then
 * counts every artifact pinned that way beside the verified ones.
 */
class LockTrustTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private MavenStub upstream;

    @BeforeEach
    void publishJunitDefaults() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        upstream = new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    @Test
    void a_repository_publishing_checksums_locks_and_counts_every_artifact_verified(@TempDir Path dir)
            throws Exception {
        upstream.leaf("com.foo", "lib", "1.0");
        RepoGroup repos = repos(dir, false);

        Lockfile lock = new LockOrchestrator(repos).lock(project("com.foo:lib", "=1.0"), "test");

        RepoGroup.TrustSummary trust = repos.trust();
        assertThat(trust.verified()).isEqualTo(rowsWithChecksum(lock)).isPositive();
        assertThat(trust.unverifiedAllowed()).isZero();
        assertThat(trust.insecureRepos())
                .as("a loopback stub needs no opt-in and is not reported as insecure")
                .isEmpty();
    }

    @Test
    void a_repository_that_opted_into_plaintext_is_named_in_the_summary(@TempDir Path dir) throws Exception {
        upstream.leaf("com.foo", "lib", "1.0");
        RepoGroup repos = repos(dir, false, true);
        new LockOrchestrator(repos).lock(project("com.foo:lib", "=1.0"), "test");
        assertThat(repos.trust().insecureRepos()).containsExactly("maven-stub");
    }

    @Test
    void a_repository_publishing_no_checksums_fails_the_lock_with_the_named_error(@TempDir Path dir) {
        upstream.withoutChecksums().leaf("com.foo", "lib", "1.0");
        RepoGroup repos = repos(dir, false);

        assertThatThrownBy(() -> new LockOrchestrator(repos).lock(project("com.foo:lib", "=1.0"), "test"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no upstream checksum for ")
                .hasMessageContaining(" from maven-stub (")
                .hasMessageContaining("allow-unverified = true on [repositories.maven-stub]");
        assertThat(repos.trust().unverifiedAllowed()).isZero();
    }

    @Test
    void allow_unverified_locks_and_counts_the_unverified_rows_separately(@TempDir Path dir) throws Exception {
        upstream.withoutChecksums().leaf("com.foo", "lib", "1.0");
        RepoGroup repos = repos(dir, true);

        Lockfile lock = new LockOrchestrator(repos).lock(project("com.foo:lib", "=1.0"), "test");

        RepoGroup.TrustSummary trust = repos.trust();
        assertThat(trust.unverifiedAllowed()).isEqualTo(rowsWithChecksum(lock)).isPositive();
        assertThat(trust.verified()).isZero();
    }

    private RepoGroup repos(Path dir, boolean allowUnverified) {
        return repos(dir, allowUnverified, false);
    }

    private RepoGroup repos(Path dir, boolean allowUnverified, boolean allowInsecure) {
        Http client = new Http();
        return RepoGroup.of(MavenRepo.overTransport(
                "maven-stub",
                http.base(),
                RepoTransports.forUrl(http.base(), client),
                new Cas(dir.resolve("cache")),
                RepoCredential.ANONYMOUS,
                client,
                false,
                allowUnverified,
                allowInsecure));
    }

    private static long rowsWithChecksum(Lockfile lock) {
        return lock.artifacts().stream().filter(a -> a.checksum() != null).count();
    }

    private static JkBuild project(String ga, String selector) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(Map.of(Scope.MAIN, List.of(new Dependency(ga, VersionSelector.parse(selector)))));
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }
}
