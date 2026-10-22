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
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * protoc as the lock pins it: {@code jk lock} records the sha256 of every platform's binary the
 * repository publishes, and the build fetches the host's against that row — other bytes, or no
 * row at all, fail the fetch instead of running.
 */
class StepToolLockTest {

    private static final Path MANIFEST = RepoRoot.file(StepToolLockTest.class, "plugins/protobuf/jk-plugin.toml");
    private static final String GROUP = "com.google.protobuf";
    private static final String VERSION = "4.33.1";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @TempDir
    Path isolatedStore;

    /** classifier -> the bytes the stub repository serves for it. */
    private final Map<String, byte[]> published = new LinkedHashMap<>();

    /** A locked platform that is not this host, which the repository does not publish. */
    private String unpublished = "";

    @BeforeEach
    void publishProtoc() throws IOException {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        PluginDescriptor manifest = PluginDescriptors.parse(Files.readString(MANIFEST), MANIFEST.toString());
        PluginTableRegistry.putBuiltIn(manifest, null);
        String host = Interpolation.Host.current().osArch();
        MavenStub upstream = new MavenStub(http).metadata(GROUP, "protoc", VERSION);
        for (Interpolation.Host platform : Interpolation.Host.LOCKED) {
            String classifier = platform.osArch();
            if (unpublished.isEmpty() && !classifier.equals(host)) {
                unpublished = classifier;
                continue;
            }
            byte[] body = ("protoc " + classifier).getBytes(StandardCharsets.UTF_8);
            published.put(classifier, body);
            upstream.bytes(exePath(classifier), body);
        }
        if (!published.containsKey(host)) {
            byte[] body = ("protoc " + host).getBytes(StandardCharsets.UTF_8);
            published.put(host, body);
            upstream.bytes(exePath(host), body);
        }
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void the_lock_pins_every_published_platform_and_the_build_fetches_the_hosts_against_it(@TempDir Path tmp)
            throws Exception {
        JkBuild build = project(tmp);
        Lockfile lock = pipeline(tmp, build).pinTools(Lockfile.empty("test"), LockPipeline.Progress.SILENT);

        assertThat(lock.tools()).singleElement().satisfies(row -> {
            assertThat(row.coordinate()).isEqualTo(GROUP + ":protoc");
            assertThat(row.version()).isEqualTo(VERSION);
            assertThat(row.type()).isEqualTo("exe");
            assertThat(row.checksum()).isNull();
            assertThat(row.sha256()).doesNotContainKey(unpublished).hasSize(published.size());
            published.forEach(
                    (classifier, body) -> assertThat(row.sha256()).containsEntry(classifier, Hashing.sha256Hex(body)));
        });

        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(lock, lockFile);
        assertThat(LockfileReader.read(lockFile).tools()).isEqualTo(lock.tools());
        assertThat(LockFreshness.isStale(tmp, lockFile)).isFalse();

        Cas cas = new Cas(tmp.resolve("cas"));
        Path protoc = Objects.requireNonNull(PluginBuild.fetchStepDependencies(build, tmp, cas, lockFile, false)
                .get("protoc"));
        byte[] hostBinary = Objects.requireNonNull(
                published.get(Interpolation.Host.current().osArch()));
        assertThat(Hashing.sha256Hex(protoc)).isEqualTo(Hashing.sha256Hex(hostBinary));
    }

    /**
     * A plain relock keeps the rows it already has: no pinned platform binary is downloaded again.
     * A platform the repository does not publish is asked again, and is still absent.
     */
    @Test
    void a_relock_reuses_the_pinned_digests(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile first = pipeline(tmp, build).pinTools(Lockfile.empty("test"), LockPipeline.Progress.SILENT);
        LockfileWriter.write(first, tmp.resolve("jk-lock.toml"));
        RepoGroup.clearProcessFetchCache();
        http.clearRequests();

        Lockfile again = pipeline(tmp, build).pinTools(Lockfile.empty("test"), LockPipeline.Progress.SILENT);

        assertThat(again.tools()).isEqualTo(first.tools());
        for (String classifier : published.keySet()) {
            assertThat(http.requestsFor(exePath(classifier))).as(classifier).isZero();
        }
    }

    @Test
    void a_lock_without_the_row_is_stale_and_the_fetch_refuses_to_run(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test"), lockFile);

        assertThat(LockFreshness.isStale(tmp, lockFile)).isTrue();
        assertThatThrownBy(() ->
                        PluginBuild.fetchStepDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pins no [[tool]] row for " + GROUP + ":protoc:" + VERSION)
                .hasMessageContaining("run `jk lock`");
    }

    @Test
    void bytes_other_than_the_pinned_ones_are_refused(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile lock = pipeline(tmp, build).pinTools(Lockfile.empty("test"), LockPipeline.Progress.SILENT);
        ToolEntry row = lock.tools().getFirst();
        Map<String, String> tampered = new LinkedHashMap<>(row.sha256());
        tampered.put(Interpolation.Host.current().osArch(), "0".repeat(64));
        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(
                lock.withTools(
                        List.of(new ToolEntry(row.coordinate(), row.version(), row.type(), null, null, tampered))),
                lockFile);
        RepoGroup.clearProcessFetchCache();
        assertThatThrownBy(() ->
                        PluginBuild.fetchStepDependencies(build, tmp, new Cas(tmp.resolve("cas")), lockFile, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-lock.toml pins sha256 " + "0".repeat(64));
    }

    private static String exePath(String classifier) {
        return MavenStub.path(GROUP, "protoc", VERSION, "-" + classifier + ".exe");
    }

    private static LockPipeline pipeline(Path tmp, JkBuild build) {
        return new LockPipeline(tmp, build, tmp.resolve("cache"), null, List.of(), false, new LockMode.Freshen());
    }

    private JkBuild project(Path tmp) throws IOException {
        String toml = """
                name    = "msgs"
                group   = "com.example"
                version = "0.1.0"
                java    = 25

                [protobuf]
                version = "%s"

                [repositories.mirror]
                url = "%s"
                groups = ["com.google.protobuf"]
                """.formatted(VERSION, http.baseUrl());
        Files.writeString(tmp.resolve("jk.toml"), toml);
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}
