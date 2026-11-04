// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockClosureInputs;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.util.ToolUse;
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
 * A closure a plugin resolved for the lock, after the lock: the sync puts every pinned file in the
 * store against its digest, a step receives the closure as a Maven-layout repository linked from
 * the store (built once, then found whole without a request), bytes other than the pinned ones are
 * refused, and a relock with nothing changed keeps the row without asking the plugin again.
 */
class LockClosuresTest {

    private static final Path MANIFEST = RepoRoot.file(LockClosuresTest.class, "plugins/quarkus/jk-plugin.toml");
    private static final String QUARKUS = "3.40.1";
    private static final Coordinate DEPLOYMENT = Coordinate.parse("io.quarkus:quarkus-arc-deployment:" + QUARKUS);
    private static final Coordinate DEPLOYMENT_POM =
            Coordinate.parse("io.quarkus:quarkus-arc-deployment:" + QUARKUS + "!pom");
    private static final Coordinate PARENT_POM = Coordinate.parse("io.quarkus:quarkus-parent:" + QUARKUS + "!pom");

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @TempDir
    Path isolatedStore;

    private final Map<Coordinate, byte[]> published = new LinkedHashMap<>();

    @BeforeEach
    void publish() throws IOException {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        System.setProperty("jk.m2.integration", "false");
        RepoGroup.clearProcessFetchCache();
        PluginDescriptor manifest = PluginDescriptors.parse(Files.readString(MANIFEST), MANIFEST.toString());
        PluginTableRegistry.putBuiltIn(manifest, null);
        MavenStub upstream = new MavenStub(http);
        published.put(DEPLOYMENT, "deployment classes".getBytes(StandardCharsets.UTF_8));
        published.put(
                DEPLOYMENT_POM,
                MavenStub.emptyPom("io.quarkus", "quarkus-arc-deployment", QUARKUS)
                        .getBytes(StandardCharsets.UTF_8));
        published.put(
                PARENT_POM,
                MavenStub.emptyPom("io.quarkus", "quarkus-parent", QUARKUS).getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<Coordinate, byte[]> file : published.entrySet()) {
            upstream.bytes(path(file.getKey()), file.getValue());
        }
    }

    @AfterEach
    void releaseOverrides() {
        System.clearProperty("jk.env.JK_STORE_DIR");
        System.clearProperty("jk.m2.integration");
    }

    @Test
    void a_step_receives_the_closure_as_a_repository_built_once(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile lock = lockWithRow(build, tmp);
        Cas cas = JkStores.storeCas();

        Map<String, Path> repos = LockClosures.repositories(build, tmp, tmp, "quarkus", cas, lock, lock);

        assertThat(repos).containsOnlyKeys("lock-closure.deployment");
        Path repo = Objects.requireNonNull(repos.get("lock-closure.deployment"));
        assertThat(repo.getParent()).isEqualTo(ToolClosures.root(cas));
        assertThat(repo.getFileName().toString()).startsWith("lock-quarkus-deployment__");
        // Every hand-out is a use the tools collector counts down from.
        assertThat(ToolUse.ledger(cas.root().resolve("tools")).load())
                .containsKey(ToolUse.key(ToolClosures.FAMILY, repo.getFileName().toString()));
        for (Map.Entry<Coordinate, byte[]> file : published.entrySet()) {
            Path linked = repo.resolve(MavenLayout.artifactPath(file.getKey()));
            assertThat(linked).as(file.getKey().toString()).hasBinaryContent(file.getValue());
        }
        assertThat(ToolClosures.complete(repo)).isTrue();

        http.clearRequests();
        assertThat(LockClosures.repositories(build, tmp, tmp, "quarkus", cas, lock, lock))
                .containsEntry("lock-closure.deployment", repo);
        assertThat(http.requested())
                .as("a whole repository is found, not rebuilt")
                .isEmpty();
        assertThat(LockClosures.repositories(build, tmp, tmp, "spring-boot", cas, lock, lock))
                .as("another plugin's steps receive none")
                .isEmpty();
    }

    @Test
    void the_sync_stages_every_pinned_file_and_a_missing_row_is_a_stale_lock(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile lock = lockWithRow(build, tmp);

        assertThat(LockClosures.stage(build, tmp, tmp, JkStores.storeCas(), lock, lock))
                .containsExactly("quarkus/deployment");
        for (Coordinate file : published.keySet()) {
            assertThat(http.requestsFor(path(file))).as(file.toString()).isEqualTo(1);
        }

        assertThatThrownBy(() -> LockClosures.stage(
                        build, tmp, tmp, JkStores.storeCas(), Lockfile.empty("test"), Lockfile.empty("test")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("quarkus/deployment")
                .hasMessageContaining("run `jk lock`");
    }

    @Test
    void bytes_other_than_the_pinned_ones_are_refused(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Map<Coordinate, String> pinned = digests();
        pinned.put(DEPLOYMENT, "0".repeat(64));
        Lockfile lock = lockWith(build, tmp, pinned);

        assertThatThrownBy(() -> LockClosures.repositories(build, tmp, tmp, "quarkus", JkStores.storeCas(), lock, lock))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("quarkus/deployment");
    }

    /** A plain relock with the module's inputs unchanged keeps the row; no worker is forked to ask. */
    @Test
    void a_relock_with_nothing_changed_keeps_the_row_without_asking(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile previous = lockWithRow(build, tmp);

        Lockfile relocked = LockClosures.pin(
                Lockfile.empty("test"),
                previous,
                tmp,
                Map.of(tmp, build),
                null,
                tmp.resolve("no-worker-jars-here"),
                LockPipeline.Progress.SILENT);

        assertThat(relocked.tools()).containsExactlyElementsOf(previous.tools());
        assertThat(http.requested()).isEmpty();
    }

    @Test
    void a_module_without_the_plugin_is_left_as_it_was(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "group = \"g\"\nname = \"n\"\nversion = \"1\"\n");
        JkBuild plain = JkBuildParser.parse(tmp.resolve("jk.toml"));
        Lockfile lock = Lockfile.empty("test");

        assertThat(LockClosures.pin(lock, null, tmp, Map.of(tmp, plain), null, tmp, LockPipeline.Progress.SILENT))
                .isSameAs(lock);
        assertThat(LockClosures.stage(plain, tmp, tmp, JkStores.storeCas(), lock, lock))
                .isEmpty();
    }

    private Lockfile lockWithRow(JkBuild build, Path dir) {
        return lockWith(build, dir, digests());
    }

    private static Lockfile lockWith(JkBuild build, Path dir, Map<Coordinate, String> files) {
        Lockfile lock = Lockfile.empty("test");
        var closure = PluginContributions.lockClosures(build, dir).getFirst();
        String inputs = LockClosureInputs.of(closure, dir, dir, lock);
        return lock.withTools(List.of(ToolEntry.resolved(closure.resolvedBy(), inputs, files)));
    }

    private Map<Coordinate, String> digests() {
        Map<Coordinate, String> out = new LinkedHashMap<>();
        published.forEach((coord, bytes) -> out.put(coord, Hashing.sha256Hex(bytes)));
        return out;
    }

    /** Where the stub serves {@code coord}: its Maven-layout path from the repository root. */
    private static String path(Coordinate coord) {
        return "/" + MavenLayout.artifactPath(coord);
    }

    private JkBuild project(Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                name    = "app"
                group   = "com.example"
                version = "0.1.0"
                java    = 25

                [quarkus]
                version = "%s"

                [repositories.mirror]
                url = "%s"
                groups = ["io.quarkus"]
                """.formatted(QUARKUS, http.baseUrl()));
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}
