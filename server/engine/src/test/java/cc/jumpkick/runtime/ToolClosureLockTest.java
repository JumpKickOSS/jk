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
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tool fetched as its runtime closure is pinned whole: {@code jk lock} resolves the closure once
 * and writes a closure row naming every jar, each jar a {@code [[tool]]} row with its sha256. The
 * build resolves nothing — it fetches exactly those jars against their digests — and a lock
 * without the closure row is stale and refused.
 */
class ToolClosureLockTest {

    private static final String GROUP = "com.example.gen";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void publish() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id      = "gen"
                        table   = "gen"
                        version = "1.0.0"

                        [schema]
                        version = { type = "string", default = "1.0" }

                        [[contribute.step-dependency]]
                        artifact   = "generator"
                        coordinate = "com.example.gen:gen-cli:${config.version}"
                        transitive = true
                        """, "gen"), null);
        new MavenStub(http)
                .metadata(GROUP, "gen-cli", "1.0")
                .pom(GROUP, "gen-cli", "1.0", """
                        <project><modelVersion>4.0.0</modelVersion>
                          <groupId>com.example.gen</groupId><artifactId>gen-cli</artifactId><version>1.0</version>
                          <dependencies>
                            <dependency><groupId>com.example.gen</groupId><artifactId>gen-core</artifactId>
                              <version>2.0</version></dependency>
                          </dependencies>
                        </project>""")
                .bytes(MavenStub.path(GROUP, "gen-cli", "1.0", ".jar"), bytes("cli"))
                .metadata(GROUP, "gen-core", "2.0")
                .pom(GROUP, "gen-core", "2.0", """
                        <project><modelVersion>4.0.0</modelVersion>
                          <groupId>com.example.gen</groupId><artifactId>gen-core</artifactId><version>2.0</version>
                        </project>""")
                .bytes(MavenStub.path(GROUP, "gen-core", "2.0", ".jar"), bytes("core"));
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void the_lock_pins_every_jar_of_the_closure_and_the_build_fetches_exactly_those(@TempDir Path tmp)
            throws Exception {
        JkBuild build = project(tmp);
        Lockfile lock = ToolLockFixtures.writeWithTools(tmp, tmp.resolve("cache"), Lockfile.empty("test"));

        ToolEntry closure =
                Objects.requireNonNull(ToolEntry.closureIn(lock.tools(), GROUP + ":gen-cli:1.0", List.of(), null));
        assertThat(closure.closure()).containsExactly(GROUP + ":gen-cli:1.0", GROUP + ":gen-core:2.0");
        assertThat(lock.toolRow(Coordinate.parse(GROUP + ":gen-core:2.0")))
                .isNotNull()
                .extracting(ToolEntry::checksum)
                .isEqualTo("sha256:" + Hashing.sha256Hex(bytes("core")));
        Path lockFile = tmp.resolve("jk-lock.toml");
        assertThat(LockfileReader.read(lockFile).tools()).containsExactlyInAnyOrderElementsOf(lock.tools());
        assertThat(LockFreshness.isStale(tmp, lockFile)).isFalse();

        http.clearRequests();
        Path dir = Objects.requireNonNull(
                PluginBuild.fetchStepDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile, false)
                        .get("generator"));
        assertThat(dir.resolve("gen-cli-1.0.jar")).hasBinaryContent(bytes("cli"));
        assertThat(dir.resolve("gen-core-2.0.jar")).hasBinaryContent(bytes("core"));
        assertThat(http.requested())
                .as("the build resolves nothing")
                .noneSatisfy(path -> assertThat(path).endsWith(".pom"))
                .noneSatisfy(path -> assertThat(path).endsWith("maven-metadata.xml"));
    }

    @Test
    void a_relock_keeps_the_pinned_closure(@TempDir Path tmp) throws Exception {
        project(tmp);
        Lockfile first = ToolLockFixtures.writeWithTools(tmp, tmp.resolve("cache"), Lockfile.empty("test"));
        RepoGroup.clearProcessFetchCache();
        http.clearRequests();

        Lockfile again = ToolLockFixtures.writeWithTools(tmp, tmp.resolve("cache"), Lockfile.empty("test"));

        assertThat(again.tools()).containsExactlyInAnyOrderElementsOf(first.tools());
        assertThat(http.requested()).isEmpty();
    }

    @Test
    void a_lock_without_the_closure_row_is_stale_and_the_fetch_refuses(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test"), lockFile);

        assertThat(LockFreshness.isStale(tmp, lockFile)).isTrue();
        assertThatThrownBy(() ->
                        PluginBuild.fetchStepDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pins no [[tool]] closure for " + GROUP + ":gen-cli:1.0");
    }

    @Test
    void a_closure_jar_with_other_bytes_than_its_row_is_refused(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile lock = ToolLockFixtures.writeWithTools(tmp, tmp.resolve("cache"), Lockfile.empty("test"));
        List<ToolEntry> tampered = new ArrayList<>();
        for (ToolEntry row : lock.tools()) {
            tampered.add(
                    "com.example.gen:gen-core".equals(row.coordinate()) && !row.isClosure()
                            ? ToolEntry.single(Coordinate.parse(GROUP + ":gen-core:2.0"), "0".repeat(64))
                            : row);
        }
        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(lock.withTools(tampered), lockFile);
        RepoGroup.clearProcessFetchCache();

        assertThatThrownBy(() ->
                        PluginBuild.fetchStepDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-lock.toml pins sha256 " + "0".repeat(64));
    }

    private static byte[] bytes(String what) {
        return ("jar " + what).getBytes(StandardCharsets.UTF_8);
    }

    private JkBuild project(Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                name    = "app"
                group   = "com.example"
                version = "0.1.0"
                java    = 25

                [gen]

                [repositories.gen]
                url = "%s"
                groups = ["com.example.gen"]
                """.formatted(http.baseUrl()));
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}
