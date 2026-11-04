// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockClosureStamp;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.Symlinks;
import cc.jumpkick.util.ToolUse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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

        List<LockClosures.Repo> planned = LockClosures.planned(build, tmp, tmp, "quarkus", cas, lock, lock);
        assertThat(planned)
                .extracting(LockClosures.Repo::extra)
                .containsExactly("lock-closure.deployment", "lock-closure.test-deployment");
        // Each closure reaches the step that reads it alone, so only that step is keyed by it.
        assertThat(planned.get(0).reaches("quarkus-augment")).isTrue();
        assertThat(planned.get(0).reaches("quarkus-test-model")).isFalse();
        assertThat(planned.get(1).reaches("quarkus-test-model")).isTrue();
        assertThat(ToolClosures.root(cas)).as("planning builds nothing").doesNotExist();

        Map<String, Path> repos = LockClosures.withRepositories(Map.of(), planned);
        Path repo = Objects.requireNonNull(repos.get("lock-closure.deployment"));
        assertThat(planned.getFirst().token()).isEqualTo("lock-closure.deployment:" + repo.getFileName());
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
        assertThat(LockClosures.withRepositories(
                        Map.of(), LockClosures.planned(build, tmp, tmp, "quarkus", cas, lock, lock)))
                .containsEntry("lock-closure.deployment", repo);
        assertThat(http.requested())
                .as("a whole repository is found, not rebuilt")
                .isEmpty();
        assertThat(LockClosures.planned(build, tmp, tmp, "spring-boot", cas, lock, lock))
                .as("another plugin's steps receive none")
                .isEmpty();
    }

    @Test
    void the_sync_stages_every_pinned_file_and_a_missing_row_is_a_stale_lock(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile lock = lockWithRow(build, tmp);

        assertThat(LockClosures.stage(build, tmp, tmp, JkStores.storeCas(), lock, lock))
                .containsExactly("quarkus/deployment", "quarkus/test-deployment");
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

        assertThatThrownBy(() -> LockClosures.withRepositories(
                        Map.of(), LockClosures.planned(build, tmp, tmp, "quarkus", JkStores.storeCas(), lock, lock)))
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

    /**
     * The inputs are read off the lock as it reads back from disk: a row the writer drops an empty
     * field of (no scopes reads back as main) still matches the row a pin computed, so a fresh lock
     * is never taken for stale and asked again.
     */
    @Test
    void the_inputs_are_the_written_locks(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Lockfile inMemory = Lockfile.empty("test")
                .withArtifacts(List.of(new Lockfile.Artifact(
                        "io.quarkus:quarkus-arc",
                        QUARKUS,
                        "central+https://repo.maven.apache.org/maven2/",
                        "sha256:" + "5".repeat(64),
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null)));
        Lockfile previous = lockWith(inMemory, build, tmp, digests());

        Lockfile relocked = LockClosures.pin(
                inMemory,
                previous,
                tmp,
                Map.of(tmp, build),
                null,
                tmp.resolve("no-worker-jars-here"),
                LockPipeline.Progress.SILENT);

        assertThat(relocked.tools()).containsExactlyElementsOf(previous.tools());
    }

    /**
     * The inputs are the module's own: its path under the lock, and its rows in the scopes the
     * closure reads — a test-only row moves the test model's closure and not the augment's.
     */
    @Test
    void the_inputs_are_the_modules_own_rows_in_the_closures_scopes(@TempDir Path tmp) throws Exception {
        JkBuild build = project(tmp);
        Files.writeString(tmp.resolve("jk.toml"), Files.readString(tmp.resolve("jk.toml")) + """

                [test-dependencies]
                junit = "org.junit.jupiter:junit-jupiter:6.1.3"
                """);
        build = JkBuildParser.parse(tmp.resolve("jk.toml"));
        Lockfile without = Lockfile.empty("test");
        Lockfile with = without.withArtifacts(List.of(new Lockfile.Artifact(
                "org.junit.jupiter:junit-jupiter",
                "6.1.3",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + "6".repeat(64),
                null,
                List.of(Scope.TEST),
                List.of(),
                null,
                null,
                null)));
        var closures = PluginContributions.lockClosures(build, tmp);
        var deployment = closures.get(0);
        var testDeployment = closures.get(1);

        assertThat(LockClosureInputs.of(deployment, build, tmp, tmp, with))
                .as("a test-only row is no input of the production closure")
                .isEqualTo(LockClosureInputs.of(deployment, build, tmp, tmp, without));
        assertThat(LockClosureInputs.of(testDeployment, build, tmp, tmp, with))
                .isNotEqualTo(LockClosureInputs.of(testDeployment, build, tmp, tmp, without));
        assertThat(LockClosureInputs.of(deployment, build, tmp, tmp.resolve("api"), without))
                .as("the module is an input")
                .isNotEqualTo(LockClosureInputs.of(deployment, build, tmp, tmp.resolve("web"), without));
        assertThat(LockClosureInputs.module(tmp, tmp)).isEmpty();
        assertThat(LockClosureInputs.module(tmp, tmp.resolve("services/api"))).isEqualTo("services/api");
    }

    /**
     * What the plugin's resolver downloaded is taken in from where it lies — inside the worker's
     * scratch or seed, a regular file and no link — and pinned by its digest; anything else is
     * fetched, and a digest the lock already pins wins over the bytes.
     */
    @Test
    void a_file_the_resolver_holds_is_taken_in_only_from_inside_its_own_directories(@TempDir Path tmp)
            throws Exception {
        JkBuild build = project(tmp);
        Cas cas = JkStores.storeCas();
        RepoGroup repos = RepoGroupBuilder.buildFor(build, null, cas, BuildEnv.forModule(tmp));
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path held = Files.writeString(scratch.resolve("held.jar"), "held bytes");
        Path secret = Files.writeString(outside.resolve("secret"), "not the plugin's");
        Path link = Symlinks.create(scratch.resolve("link.jar"), secret);

        assertThat(LockClosures.within(held, List.of(scratch))).isTrue();
        assertThat(LockClosures.within(link, List.of(scratch)))
                .as("a link out of the scratch")
                .isFalse();
        assertThat(LockClosures.within(scratch.resolve("../outside/secret"), List.of(scratch)))
                .as("a .. out of the scratch")
                .isFalse();

        Map<Coordinate, String> known = new LinkedHashMap<>();
        known.put(PARENT_POM, "7".repeat(64));
        Map<Coordinate, @Nullable Path> answered = new LinkedHashMap<>();
        answered.put(Coordinate.parse("x:held:1"), held);
        answered.put(PARENT_POM, held);
        answered.put(DEPLOYMENT, link);
        Map<Coordinate, String> digests = LockClosures.digests(answered, known, repos, cas, List.of(scratch));

        assertThat(digests.get(Coordinate.parse("x:held:1"))).isEqualTo(Hashing.sha256Hex(held));
        assertThat(cas.pathFor(Hashing.sha256Hex(held)))
                .as("taken into the store")
                .hasContent("held bytes");
        assertThat(digests.get(PARENT_POM)).as("the lock's pin wins").isEqualTo("7".repeat(64));
        assertThat(digests.get(DEPLOYMENT))
                .as("the link is not followed; the file is fetched")
                .isEqualTo(Hashing.sha256Hex(Objects.requireNonNull(published.get(DEPLOYMENT))));
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
        return lockWith(Lockfile.empty("test"), build, dir, files);
    }

    /** {@code base} with a row for each closure of the module, read off {@code base} as it reads back from disk. */
    private static Lockfile lockWith(Lockfile base, JkBuild build, Path dir, Map<Coordinate, String> files) {
        Lockfile written = LockfileReader.parse(LockfileWriter.render(base));
        List<ToolEntry> rows = new ArrayList<>(base.tools());
        for (var closure : PluginContributions.lockClosures(build, dir)) {
            rows.add(ToolEntry.resolved(
                    closure.resolvedBy(),
                    null,
                    LockClosureStamp.of(closure.manifest()),
                    LockClosureInputs.of(closure, build, dir, dir, written),
                    files));
        }
        return base.withTools(rows);
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
