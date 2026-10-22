// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.Interpolation;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin tool at a URL is a {@code [[tool]]} row: {@code jk lock} pins the sha256 of the file at
 * each URL — one per OS when the URL names {@code ${host.os}} — and the build fetches this host's
 * file against it. A lock with no row is stale and the fetch refuses to run.
 */
class UrlToolLockTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void publish() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id      = "rules"
                        table   = "rules"
                        version = "1.0.0"

                        [schema]
                        base = { type = "string" }

                        [[contribute.step-dependency]]
                        artifact = "rules"
                        url      = "${config.base}/rules-${host.os}.xml"
                        """, "rules"), null);
        String host = Interpolation.Host.current().os();
        http.serve("/rules-" + host + ".xml", "rules for " + host);
        // Another OS's file is published too; the third OS's is not, and gets no row.
        http.serve("/rules-" + (host.equals("linux") ? "osx" : "linux") + ".xml", "rules elsewhere");
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void each_url_is_pinned_and_the_build_fetches_the_hosts_against_it(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile lock = ToolLockFixtures.writeWithTools(tmp, tmp.resolve("cache"), Lockfile.empty("test"));

        String hostUrl = url(Interpolation.Host.current().os());
        assertThat(lock.tools()).hasSize(2).allSatisfy(row -> assertThat(row.url())
                .isNotNull());
        assertThat(lock.urlSha256(hostUrl))
                .isEqualTo(Hashing.sha256Hex(
                        "rules for " + Interpolation.Host.current().os()));
        Path lockFile = tmp.resolve("jk-lock.toml");
        assertThat(LockfileReader.read(lockFile).tools()).isEqualTo(lock.tools());
        assertThat(LockFreshness.isStale(tmp, lockFile)).isFalse();

        Path rules = Objects.requireNonNull(
                PluginBuild.fetchStepDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile, false)
                        .get("rules"));
        assertThat(Files.readString(rules))
                .isEqualTo("rules for " + Interpolation.Host.current().os());
    }

    /** A plain relock downloads no pinned file again; the unpublished OS's URL is asked again. */
    @Test
    void a_relock_reuses_the_pinned_digests(@TempDir Path tmp) throws Exception {
        project(tmp);
        Lockfile first = ToolLockFixtures.writeWithTools(tmp, tmp.resolve("cache"), Lockfile.empty("test"));
        http.clearRequests();

        Lockfile again = ToolLockFixtures.writeWithTools(tmp, tmp.resolve("cache"), Lockfile.empty("test"));

        assertThat(again.tools()).isEqualTo(first.tools());
        for (ToolEntry row : first.tools()) {
            String path = Objects.requireNonNull(row.url())
                    .substring(http.baseUrl().replaceAll("/$", "").length());
            assertThat(http.requestsFor(path)).as(path).isZero();
        }
    }

    @Test
    void a_lock_without_the_row_is_stale_and_the_fetch_refuses(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test"), lockFile);

        assertThat(LockFreshness.isStale(tmp, lockFile)).isTrue();
        assertThatThrownBy(() ->
                        PluginBuild.fetchStepDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pins no [[tool]] row for "
                        + url(Interpolation.Host.current().os()));
    }

    @Test
    void bytes_other_than_the_pinned_ones_are_refused(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Path lockFile = tmp.resolve("jk-lock.toml");
        String hostUrl = url(Interpolation.Host.current().os());
        LockfileWriter.write(
                Lockfile.empty("test").withTools(List.of(ToolEntry.atUrl(hostUrl, "0".repeat(64)))), lockFile);

        assertThatThrownBy(() ->
                        PluginBuild.fetchStepDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-lock.toml pins sha256 " + "0".repeat(64) + " for " + hostUrl);
    }

    private String url(String os) {
        return http.baseUrl().replaceAll("/$", "") + "/rules-" + os + ".xml";
    }

    private JkBuild project(Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                name    = "app"
                group   = "com.example"
                version = "0.1.0"
                java    = 25

                [rules]
                base = "%s"
                """.formatted(http.baseUrl().replaceAll("/$", "")));
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}
