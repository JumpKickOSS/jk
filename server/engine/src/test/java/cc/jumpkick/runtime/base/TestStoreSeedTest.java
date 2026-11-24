// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.LibraryRegistrySync;
import cc.jumpkick.repo.MavenMetadata;
import cc.jumpkick.testing.MavenStub;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sandbox learns the JUnit Platform from the machine's local repositories — its own local
 * repository gets the files, its store the memos that vouch for them and the version lists — so
 * the {@code latest} launcher every fixture lock injects resolves without Central.
 */
class TestStoreSeedTest {

    private static final String LAUNCHER = "org/junit/platform/junit-platform-launcher";

    @TempDir
    Path tmp;

    private @Nullable String priorHome;

    private @Nullable String priorRepoLocal;

    private @Nullable String priorSettings;

    /**
     * The machine: the developer's {@code ~/.m2} is {@code tmp/home/.m2/repository}, nothing real is
     * read. The harness's sandbox user repository and settings give way to it for the test.
     */
    @BeforeEach
    void isolate() {
        priorHome = System.getProperty("user.home");
        priorRepoLocal = System.clearProperty("maven.repo.local");
        priorSettings = System.clearProperty("jk.m2.settings");
        System.setProperty("user.home", tmp.resolve("home").toString());
        System.setProperty("jk.m2.local", tmp.resolve("own").toString());
    }

    @AfterEach
    void restore() {
        if (priorHome != null) System.setProperty("user.home", priorHome);
        if (priorRepoLocal != null) System.setProperty("maven.repo.local", priorRepoLocal);
        if (priorSettings != null) System.setProperty("jk.m2.settings", priorSettings);
    }

    private Path userM2() {
        return tmp.resolve("home/.m2/repository");
    }

    @Test
    void a_seeded_sandbox_answers_central_layout_paths_bodies_and_version_lists() throws Exception {
        m2File(userM2(), LAUNCHER, "6.1.3", ".pom", "central");
        m2File(userM2(), LAUNCHER, "6.1.3", ".jar", "central");
        Path store = tmp.resolve("store");
        Path sandboxM2 = tmp.resolve("sandbox-m2");

        TestStoreSeed.seed(store, sandboxM2);
        System.setProperty("jk.m2.local", sandboxM2.toString());

        assertThat(TestStoreSeed.seeded(store, LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.jar"))
                .isPresent();
        assertThat(TestStoreSeed.seeded(store, LAUNCHER + "/maven-metadata.xml")
                        .map(xml -> new String(xml, StandardCharsets.UTF_8)))
                .hasValueSatisfying(xml -> assertThat(xml).contains("<latest>6.1.3</latest>"));
        assertThat(TestStoreSeed.seeded(store, LAUNCHER + "/6.0.3/junit-platform-launcher-6.0.3.jar"))
                .isEmpty();
        assertThat(TestStoreSeed.seeded(store, "org/scala-sbt/zinc_3/maven-metadata.xml"))
                .isEmpty();
        assertThat(TestStoreSeed.seeded(store, "../../metadata/x")).isEmpty();
    }

    @Test
    void a_file_the_pool_already_holds_takes_the_hint_so_the_sandbox_still_gets_its_version_list() throws Exception {
        Path pom = m2File(userM2(), LAUNCHER, "6.1.3", ".pom", "central");
        Path sandboxM2 = tmp.resolve("sandbox-m2");
        // an earlier suite fetched the same bytes into the pool and recorded no provenance
        Path held = sandboxM2.resolve(LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.pom");
        Files.createDirectories(held.getParent());
        Files.copy(pom, held);
        Path store = tmp.resolve("store");

        TestStoreSeed.seed(store, sandboxM2);
        System.setProperty("jk.m2.local", sandboxM2.toString());

        assertThat(TestStoreSeed.seeded(store, LAUNCHER + "/maven-metadata.xml")
                        .map(xml -> new String(xml, StandardCharsets.UTF_8)))
                .hasValueSatisfying(xml -> assertThat(xml).contains("<latest>6.1.3</latest>"));
    }

    @Test
    void the_files_are_copied_into_the_sandbox_local_repository_and_vouched_for_in_its_store() throws Exception {
        Path source = m2File(userM2(), LAUNCHER, "6.1.3", ".jar", "central");
        m2File(userM2(), LAUNCHER, "6.1.3", ".pom", "central");
        m2File(userM2(), "org/opentest4j/opentest4j", "1.3.0", ".jar", "central");
        m2File(userM2(), "org/scala-sbt/zinc_3", "2.0.4", ".jar", "central");
        Path store = tmp.resolve("store");
        Path sandboxM2 = tmp.resolve("sandbox-m2");

        int seeded = TestStoreSeed.seed(store, sandboxM2);

        String rel = LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.jar";
        Path copy = sandboxM2.resolve(rel);
        assertThat(copy).hasSameBinaryContentAs(source);
        assertThat(Files.isSameFile(source, copy))
                .as("a copy, never a link into the developer's local repository")
                .isFalse();
        assertThat(sandboxM2.resolve("org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar"))
                .isRegularFile();
        assertThat(sandboxM2.resolve("org/scala-sbt"))
                .as("only the JUnit trees")
                .doesNotExist();
        Path memos = store.resolve("repos").resolve(RepositorySpec.CENTRAL);
        assertThat(ArtifactMemo.read(ArtifactMemo.jkPath(memos, rel)))
                .get()
                .extracting(ArtifactMemo::sha256)
                .isEqualTo(Hashing.sha256Hex(copy));
        assertThat(memos.resolve(rel))
                .as("the store holds the memo and no second copy")
                .doesNotExist();
        assertThat(seeded).isPositive();
    }

    @Test
    void a_body_another_repository_served_or_nobody_vouches_for_is_not_seeded_as_centrals() throws Exception {
        m2File(userM2(), LAUNCHER, "6.1.3", ".pom", "nexus");
        m2File(userM2(), LAUNCHER, "6.1.3", ".jar", null);
        Path sandboxM2 = tmp.resolve("sandbox-m2");

        assertThat(TestStoreSeed.seed(tmp.resolve("store"), sandboxM2)).isZero();
        assertThat(sandboxM2).doesNotExist();
    }

    @Test
    void a_pom_in_a_version_dir_central_served_is_taken_without_its_own_hint_line() throws Exception {
        Path pom = m2File(userM2(), LAUNCHER, "6.1.3", ".pom", null);
        Files.writeString(
                pom.resolveSibling("_remote.repositories"),
                "junit-platform-launcher-6.1.3.jar>central=\n",
                StandardOpenOption.CREATE);
        Path sandboxM2 = tmp.resolve("sandbox-m2");

        TestStoreSeed.seed(tmp.resolve("store"), sandboxM2);

        assertThat(sandboxM2.resolve(LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.pom"))
                .isRegularFile();
    }

    @Test
    void a_version_without_a_pom_is_copied_but_not_advertised() throws Exception {
        m2File(userM2(), LAUNCHER, "6.1.3", ".pom", "central");
        m2File(userM2(), LAUNCHER, "6.1.3", ".jar", "central");
        m2File(userM2(), LAUNCHER, "6.1.4", ".jar", "central");
        Path store = tmp.resolve("store");
        Path sandboxM2 = tmp.resolve("sandbox-m2");

        TestStoreSeed.seed(store, sandboxM2);

        assertThat(sandboxM2.resolve(LAUNCHER + "/6.1.4/junit-platform-launcher-6.1.4.jar"))
                .isRegularFile();
        assertThat(Files.readString(metadata(store, LAUNCHER)))
                .contains("<version>6.1.3</version>")
                .doesNotContain("6.1.4");
    }

    @Test
    void a_hollow_stand_in_gives_way_to_the_real_file() throws Exception {
        Path real = m2File(userM2(), LAUNCHER, "6.1.3", ".jar", "central");
        Path sandboxM2 = tmp.resolve("sandbox-m2");
        Path slot = sandboxM2.resolve(LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.jar");
        Files.createDirectories(slot.getParent());
        Files.write(slot, MavenStub.EMPTY_JAR);

        TestStoreSeed.seed(tmp.resolve("store"), sandboxM2);

        assertThat(slot).hasSameBinaryContentAs(real);
    }

    /** A second seed finds nothing to do, and an index the sandbox fetched itself is kept. */
    @Test
    void a_second_seed_finds_nothing_to_do_and_a_real_index_already_there_is_kept() throws Exception {
        m2File(userM2(), LAUNCHER, "6.1.3", ".pom", "central");
        m2File(userM2(), LAUNCHER, "6.1.3", ".jar", "central");
        Path store = tmp.resolve("store");
        Path sandboxM2 = tmp.resolve("sandbox-m2");
        Path body = metadata(store, LAUNCHER);
        Files.createDirectories(body.getParent());
        Files.writeString(body, "<metadata>the store's own fetch</metadata>");

        assertThat(TestStoreSeed.seed(store, sandboxM2)).isPositive();
        assertThat(Files.readString(body)).isEqualTo("<metadata>the store's own fetch</metadata>");
        assertThat(TestStoreSeed.seed(store, sandboxM2)).isZero();
    }

    /** A list seeded when the machine knew one launcher version must follow it to the next. */
    @Test
    void a_synthesised_version_list_follows_the_machine() throws Exception {
        m2File(userM2(), LAUNCHER, "6.1.3", ".pom", "central");
        Path store = tmp.resolve("store");
        Path sandboxM2 = tmp.resolve("sandbox-m2");
        TestStoreSeed.seed(store, sandboxM2);
        assertThat(Files.readString(metadata(store, LAUNCHER)))
                .contains(TestStoreSeed.SEED_MARK)
                .contains("<latest>6.1.3</latest>")
                .doesNotContain("6.1.4");

        m2File(userM2(), LAUNCHER, "6.1.4", ".pom", "central");
        assertThat(TestStoreSeed.seed(store, sandboxM2)).isPositive();

        assertThat(Files.readString(metadata(store, LAUNCHER)))
                .contains("<version>6.1.3</version>")
                .contains("<version>6.1.4</version>")
                .contains("<latest>6.1.4</latest>");
        assertThat(TestStoreSeed.seed(store, sandboxM2)).as("settled").isZero();
    }

    /** The key is the one the metadata cache derives from the Central URL. */
    @Test
    void the_version_list_sits_under_the_metadata_caches_key() {
        assertThat(TestStoreSeed.metadataKey(Path.of(LAUNCHER)))
                .isEqualTo(
                        Hashing.sha256Hex("https://repo.maven.apache.org/maven2/" + LAUNCHER + "/maven-metadata.xml"));
    }

    /**
     * Every fixture lock asks Google's repository and JumpKick's for each JUnit artifact's version
     * list too: the seed answers for them, as lists a repository that lacks the artifact serves.
     */
    @Test
    void the_other_public_repositories_list_no_version_of_a_seeded_artifact() throws Exception {
        m2File(userM2(), LAUNCHER, "6.1.3", ".pom", "central");
        Path store = tmp.resolve("store");

        TestStoreSeed.seed(store, tmp.resolve("sandbox-m2"));

        for (String base : List.of("https://dl.google.com/dl/android/maven2/", "https://jumpkick.build/repo/")) {
            Path body = store.resolve("metadata").resolve(Hashing.sha256Hex(base + LAUNCHER + "/maven-metadata.xml"));
            assertThat(MavenMetadata.parse(Files.readAllBytes(body), "org.junit.platform", "junit-platform-launcher")
                            .versions())
                    .as(base)
                    .isEmpty();
        }
    }

    /** A synthesised list the machine still agrees with is stamped fresh, so a kept slot never revalidates it. */
    @Test
    void a_second_seed_stamps_a_synthesised_list_fresh() throws Exception {
        m2File(userM2(), LAUNCHER, "6.1.3", ".pom", "central");
        Path store = tmp.resolve("store");
        Path sandboxM2 = tmp.resolve("sandbox-m2");
        TestStoreSeed.seed(store, sandboxM2);
        Path body = metadata(store, LAUNCHER);
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(3)));
        Files.setLastModifiedTime(body, old);

        assertThat(TestStoreSeed.seed(store, sandboxM2)).isZero();

        assertThat(Files.getLastModifiedTime(body)).isGreaterThan(old);
    }

    /** The sandbox reads the registry the engine reads, stamped fresh, so no lock asks the registry's origin. */
    @Test
    void the_sandbox_takes_the_engines_library_registry_unless_it_holds_a_fresh_one() throws Exception {
        Path engine = Files.writeString(
                Files.createDirectories(tmp.resolve("engine-store")).resolve("libs.global.toml"), "[libraries]\n");
        Files.setLastModifiedTime(engine, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Path store = tmp.resolve("store");
        Path sandbox = store.resolve("libs.global.toml");

        assertThat(TestStoreSeed.seedRegistry(store, engine)).isTrue();
        assertThat(sandbox).hasSameTextualContentAs(engine);
        assertThat(Duration.between(Files.getLastModifiedTime(sandbox).toInstant(), Instant.now()))
                .isLessThan(LibraryRegistrySync.FRESH_FOR);

        Files.writeString(sandbox, "[libraries]\nfetched-by-the-sandbox = \"a:b\"\n");
        assertThat(TestStoreSeed.seedRegistry(store, engine))
                .as("a fresh copy of its own")
                .isFalse();
        assertThat(Files.readString(sandbox)).contains("fetched-by-the-sandbox");

        Files.setLastModifiedTime(sandbox, FileTime.from(Instant.now().minus(Duration.ofDays(1))));
        assertThat(TestStoreSeed.seedRegistry(store, engine)).as("a stale copy").isTrue();
        assertThat(sandbox).hasSameTextualContentAs(engine);

        assertThat(TestStoreSeed.seedRegistry(tmp.resolve("other"), tmp.resolve("none.toml")))
                .as("an engine with no registry")
                .isFalse();
        assertThat(tmp.resolve("other")).doesNotExist();
    }

    @Test
    void a_local_repository_filled_from_a_lock_holds_jars_but_not_the_test_roots() throws Exception {
        Path own = tmp.resolve("own");
        m2File(own, "org/junit/platform/junit-platform-launcher", "6.1.3", ".jar", "central");
        m2File(own, "org/junit/jupiter/junit-jupiter", "6.1.3", ".jar", "central");
        assertThat(TestStoreSeed.holdsTestRoots()).isFalse();

        m2File(own, "org/junit/platform/junit-platform-launcher", "6.1.3", ".pom", "central");
        assertThat(TestStoreSeed.holdsTestRoots()).isFalse();

        m2File(own, "org/junit/jupiter/junit-jupiter", "6.1.3", ".pom", "central");
        assertThat(TestStoreSeed.holdsTestRoots()).isTrue();
    }

    private static Path metadata(Path store, String artifactDir) {
        return store.resolve("metadata").resolve(TestStoreSeed.metadataKey(Path.of(artifactDir)));
    }

    /**
     * A file in a Maven local repository, with the {@code _remote.repositories} line naming the
     * repository that served it when {@code servedBy} is given.
     */
    private static Path m2File(Path m2, String artifactDir, String version, String ext, @Nullable String servedBy)
            throws Exception {
        Path dir = Files.createDirectories(m2.resolve(artifactDir).resolve(version));
        String name = Path.of(artifactDir).getFileName() + "-" + version + ext;
        Path file = Files.writeString(dir.resolve(name), ext + version);
        if (servedBy != null) {
            Files.writeString(
                    dir.resolve("_remote.repositories"),
                    name + ">" + servedBy + "=\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        return file;
    }
}
