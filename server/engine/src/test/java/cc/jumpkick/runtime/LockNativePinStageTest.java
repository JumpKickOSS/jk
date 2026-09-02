// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.protocol.OutdatedReport;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk lock} resolves {@code [native] metadata-repository} and pins the answer, so a native
 * build reads a version rather than deciding one.
 *
 * <p>Before there was nothing to resolve: the repository release was a constant in the
 * engine, the lock said nothing about it, and {@code jk update} could not see it. Deps resolve from
 * a hand-written {@code file://} Maven repo, so this never touches the network.
 */
@Tag("integration")
class LockNativePinStageTest {

    /** Minimal empty-zip bytes — a valid jar as far as fetching/hashing is concerned. */
    private static final byte[] EMPTY_ZIP = {
        0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    @BeforeEach
    void resetRepoMemos() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
    }

    /** A caret floor picks the highest stable in range, and the zip's digest rides along. */
    @Test
    void a_floating_selector_is_resolved_and_pinned(@TempDir Path tmp) throws Exception {
        Path repo = repo(tmp);
        Path project = project(tmp, "metadata-repository = \"^1.0.0\"\n");

        lock(project, repo, tmp);

        Lockfile lock = LockfileReader.read(LockPaths.lockFile(project));
        Lockfile.NativeMetadata pin = lock.nativeMetadata();
        assertThat(pin).isNotNull();
        assertThat(pin.version()).isEqualTo("1.1.4");
        assertThat(pin.checksumHex())
                .isEqualTo(Hashing.sha256Hex(
                        repo.resolve(MavenLayout.artifactPath(ReachabilityMetadata.coordinate("1.1.4")))));
    }

    /** No {@code [native]} table means no pin — and therefore no repository fetch at lock time. */
    @Test
    void a_project_without_a_native_table_pins_nothing(@TempDir Path tmp) throws Exception {
        Path repo = repo(tmp);
        Path project = project(tmp, null);

        lock(project, repo, tmp);

        assertThat(LockfileReader.read(LockPaths.lockFile(project)).nativeMetadata())
                .isNull();
    }

    /**
     * {@code jk outdated} reports the pin. A version nobody can see is a version nobody bumps: for
     * as long as it was a constant in the engine, learning that a newer repository existed meant
     * reading jk's source.
     */
    @Test
    void outdated_reports_current_compatible_and_latest_for_the_pin(@TempDir Path tmp) throws Exception {
        Path repo = repo(tmp);
        Path project = project(tmp, "metadata-repository = \"^1.0.0\"\n");
        lock(project, repo, tmp);
        RepoGroup.clearProcessVersionsCache();

        OutdatedReport report = OutdatedPlans.compute(project, tmp.resolve("cache"), repo.toUri());

        assertThat(report.error()).isNull();
        assertThat(report.rows())
                .filteredOn(r -> r.coordinate().equals("org.graalvm.buildtools:graalvm-reachability-metadata"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.current()).isEqualTo("1.1.4");
                    assertThat(r.compatible()).as("newest stable inside ^1.0.0").isEqualTo("1.1.4");
                    assertThat(r.latest())
                            .as("newest stable overall, range or not")
                            .isEqualTo("2.0.0");
                });
    }

    /** A project with no {@code [native]} table contributes no row. */
    @Test
    void outdated_says_nothing_when_no_module_declares_native(@TempDir Path tmp) throws Exception {
        Path repo = repo(tmp);
        Path project = project(tmp, null);
        lock(project, repo, tmp);

        assertThat(OutdatedPlans.compute(project, tmp.resolve("cache"), repo.toUri())
                        .rows())
                .noneMatch(r -> r.coordinate().contains("graalvm-reachability-metadata"));
    }

    /**
     * {@code jk sync} materializes the pin, so an offline native build has its config dirs. Proven
     * the only way that means anything: the remote is deleted afterwards and the metadata still
     * resolves.
     */
    @Test
    void sync_materializes_the_pinned_repository_into_the_store(@TempDir Path tmp) throws Exception {
        Path repo = repo(tmp);
        // A release of its own: the tier's store is shared, and this is the one test that unpacks.
        Path project = project(tmp, "metadata-repository = \"=1.0.0\"\n");
        lock(project, repo, tmp);
        Path extracted = ReachabilityMetadata.repositoryRoot(JkStores.store(), "1.0.0");
        PathUtil.deleteRecursively(extracted);

        BuildPlanResult result = run(SyncPlans.syncBuildPlan(
                project,
                tmp.resolve("cache"),
                tmp.resolve("jdks"),
                repo.toUri(),
                false,
                new AtomicInteger(),
                new AtomicInteger(),
                null,
                false));
        assertThat(result.success()).as("sync errors: %s", result.errors()).isTrue();
        assertThat(extracted.resolve(".complete")).exists();

        // Offline from here: nothing is left to fetch from.
        PathUtil.deleteRecursively(repo);
        RepoGroup.clearProcessFetchCache();
        assertThat(extracted.resolve("com.acme/util/index.json")).exists();
    }

    // ---- fixture -------------------------------------------------------------

    private static void lock(Path project, Path repo, Path tmp) throws IOException {
        BuildPlanResult result = run(LockPlans.lockBuildPlan(
                project,
                JkBuildParser.parse(project.resolve("jk.toml")),
                tmp.resolve("cache"),
                repo.toUri(),
                List.of(),
                true,
                false,
                ResolveObserver.NOOP,
                null));
        assertThat(result.success()).as("lock errors: %s", result.errors()).isTrue();
    }

    private static BuildPlanResult run(BuildPlan plan) {
        return plan.run();
    }

    /** {@code nativeTable} null omits {@code [native]} entirely. */
    private static Path project(Path tmp, String nativeTable) throws IOException {
        Path project = Files.createDirectories(tmp.resolve(nativeTable == null ? "plain" : "native-proj"));
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "demo"
                version = "1.0.0"

                [dependencies]
                util = { group = "com.acme", name = "util", version = "1.0.0" }
                """ + (nativeTable == null ? "" : "\n[native]\n" + nativeTable));
        return project;
    }

    private static Path repo(Path tmp) throws IOException {
        Path repo = tmp.resolve("repo");
        jar(repo, "com.acme", "util", "1.0.0");
        // The engine adds the test-runner infra to every module's closure — stub it.
        jar(repo, "org.junit.platform", "junit-platform-launcher", "1.10.0");
        jar(repo, "org.junit.jupiter", "junit-jupiter", "5.10.0");

        // Three releases advertised; 2.0.0 is out of a `^1` range and 1.1.5-rc1 is not stable.
        for (String v : List.of("1.0.0", "1.1.4", "1.1.5-rc1", "2.0.0")) {
            Path zip = repo.resolve(MavenLayout.artifactPath(ReachabilityMetadata.coordinate(v)));
            Files.createDirectories(zip.getParent());
            Files.write(zip, repositoryZip());
        }
        Path meta = repo.resolve("org/graalvm/buildtools/graalvm-reachability-metadata/maven-metadata.xml");
        Files.writeString(meta, """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>org.graalvm.buildtools</groupId>
                  <artifactId>graalvm-reachability-metadata</artifactId>
                  <versioning>
                    <versions>
                      <version>1.0.0</version>
                      <version>1.1.4</version>
                      <version>1.1.5-rc1</version>
                      <version>2.0.0</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        return repo;
    }

    /**
     * Byte-for-byte identical on every run. The tier's store lives under {@code build/} and outlives
     * a single invocation, so a zip carrying the current clock in its entry header would be fetched
     * once and then disagree with the file this test hashes — green on a clean tree, red on a warm
     * one.
     */
    private static byte[] repositoryZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry index = new ZipEntry("com.acme/util/index.json");
            index.setTime(0L);
            zip.putNextEntry(index);
            zip.write(
                    "[{\"metadata-version\":\"1\",\"tested-versions\":[\"1.0.0\"]}]".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void jar(Path repo, String group, String artifact, String version) throws IOException {
        Path dir = repo.resolve(group.replace('.', '/') + "/" + artifact + "/" + version);
        Files.createDirectories(dir);
        Files.write(dir.resolve(artifact + "-" + version + ".jar"), EMPTY_ZIP);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>jar</packaging>
                </project>
                """.formatted(group, artifact, version));
        Files.writeString(
                dir.getParent().resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <release>%s</release>
                    <versions><version>%s</version></versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, version, version));
    }
}
