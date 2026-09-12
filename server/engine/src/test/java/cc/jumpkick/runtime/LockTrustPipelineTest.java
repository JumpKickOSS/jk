// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manifest's two trust opt-ins as {@code jk lock} sees them: a plaintext repository is refused
 * where the manifest is read, and with {@code allow-insecure} / {@code allow-unverified} the plan
 * resolves and reports what its downloads were checked against.
 */
class LockTrustPipelineTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private MavenStub upstream;

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void isolateStoreAndPublish() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        upstream = new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void a_plaintext_repository_without_allow_insecure_fails_where_the_manifest_is_read(@TempDir Path tmp)
            throws IOException {
        // A remote host: loopback repositories have no network path and need no opt-in.
        String remote = "http://nexus.corp.example/maven";
        project(tmp, remote, "");
        assertThatThrownBy(() -> LockPlans.lockScope(tmp))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.mirror uses plaintext http:// (" + remote + ")")
                .hasMessageContaining("allow-insecure = true");
    }

    @Test
    void allow_insecure_resolves_and_the_summary_names_the_repository(@TempDir Path tmp) throws Exception {
        upstream.leaf("com.foo", "lib", "1.0");
        project(tmp, "allow-insecure = true");

        BuildPlan plan = plan(tmp);
        BuildPlanResult result = plan.run();

        assertThat(result.success()).as(result.errors().toString()).isTrue();
        Lockfile lock = plan.get(LockPlans.LOCKFILE).orElseThrow();
        RepoGroup.TrustSummary trust = plan.get(LockPlans.TRUST).orElseThrow();
        assertThat(trust.insecureRepos()).containsExactly("mirror");
        assertThat(trust.verified()).isEqualTo(rowsWithChecksum(lock)).isPositive();
        assertThat(trust.unverifiedAllowed()).isZero();
    }

    @Test
    void a_sidecarless_repository_fails_the_lock_unless_it_allows_unverified(@TempDir Path tmp) throws Exception {
        upstream.withoutChecksums().leaf("com.foo", "lib", "1.0");
        project(tmp, "allow-insecure = true");

        BuildPlanResult refused = plan(tmp).run();
        assertThat(refused.success()).isFalse();
        assertThat(refused.errors()).anySatisfy(d -> assertThat(d.message())
                .contains("no upstream checksum for ")
                .contains(" from mirror (")
                .contains("allow-unverified = true on [repositories.mirror]"));

        project(tmp, "allow-insecure = true\nallow-unverified = true");
        RepoGroup.clearProcessFetchCache();
        BuildPlan plan = plan(tmp);
        BuildPlanResult result = plan.run();

        assertThat(result.success()).as(result.errors().toString()).isTrue();
        Lockfile lock = plan.get(LockPlans.LOCKFILE).orElseThrow();
        RepoGroup.TrustSummary trust = plan.get(LockPlans.TRUST).orElseThrow();
        assertThat(trust.unverifiedAllowed()).isEqualTo(rowsWithChecksum(lock)).isPositive();
        assertThat(trust.verified()).isZero();
        assertThat(trust.insecureRepos()).containsExactly("mirror");
    }

    private BuildPlan plan(Path tmp) throws IOException {
        return LockPlans.plan(
                tmp,
                JkBuildParser.parse(tmp.resolve("jk.toml")),
                tmp.resolve("cache"),
                null,
                List.of(),
                true,
                new LockMode.Latest(false),
                ResolveObserver.NOOP,
                null);
    }

    private static long rowsWithChecksum(Lockfile lock) {
        return lock.artifacts().stream().filter(a -> a.checksum() != null).count();
    }

    /** The only remote is the loopback stub; it claims every group the lock will ask for. */
    private void project(Path tmp, String trustKeys) throws IOException {
        project(tmp, http.baseUrl(), trustKeys);
    }

    private void project(Path tmp, String url, String trustKeys) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                java = 25

                [dependencies]
                lib = { group = "com.foo", name = "lib", version = "=1.0" }

                [repositories.mirror]
                url = "%s"
                groups = ["com.foo", "org.junit.*"]
                %s
                """.formatted(url, trustKeys));
    }
}
