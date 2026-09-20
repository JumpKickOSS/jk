// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.RepositorySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sandbox store learns the JUnit Platform from the host store, version lists included, so the
 * {@code latest} launcher every fixture lock injects resolves without Central.
 */
class TestStoreSeedTest {

    private static final String LAUNCHER = "org/junit/platform/junit-platform-launcher";

    @Test
    void a_seeded_store_answers_central_layout_paths_bodies_and_version_lists(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host");
        artifact(host, RepositorySpec.CENTRAL, LAUNCHER, "6.1.3", ".pom", ".jar");
        Path sandbox = tmp.resolve("sandbox");
        TestStoreSeed.seed(host, sandbox);

        assertThat(TestStoreSeed.seeded(sandbox, LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.jar"))
                .isPresent();
        assertThat(TestStoreSeed.seeded(sandbox, LAUNCHER + "/maven-metadata.xml")
                        .map(xml -> new String(xml, StandardCharsets.UTF_8)))
                .hasValueSatisfying(xml -> assertThat(xml).contains("<latest>6.1.3</latest>"));
        assertThat(TestStoreSeed.seeded(sandbox, LAUNCHER + "/6.0.3/junit-platform-launcher-6.0.3.jar"))
                .isEmpty();
        assertThat(TestStoreSeed.seeded(sandbox, "org/scala-sbt/zinc_3/maven-metadata.xml"))
                .isEmpty();
        assertThat(TestStoreSeed.seeded(sandbox, "../../metadata/x")).isEmpty();
    }

    @Test
    void the_junit_closure_and_its_version_lists_come_from_the_host_store(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host");
        artifact(host, RepositorySpec.CENTRAL, LAUNCHER, "6.1.3", ".pom", ".jar", ".jk", ".pom.jk");
        artifact(host, RepositorySpec.CENTRAL, LAUNCHER, "6.0.3", ".pom", ".jar");
        artifact(host, RepositorySpec.CENTRAL, "org/opentest4j/opentest4j", "1.3.0", ".pom", ".jar");
        artifact(host, RepositorySpec.CENTRAL, "org/scala-sbt/zinc_3", "2.0.4", ".pom", ".jar");
        artifact(host, "google", "com/android/tools/r8", "8.9.35", ".pom", ".jar");
        Path sandbox = tmp.resolve("sandbox");

        int seeded = TestStoreSeed.seed(host, sandbox);

        Path central = sandbox.resolve("repos").resolve(RepositorySpec.CENTRAL);
        assertThat(central.resolve(LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.jar"))
                .isRegularFile();
        assertThat(central.resolve(LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.pom.jk"))
                .isRegularFile();
        assertThat(central.resolve(LAUNCHER + "/6.0.3/junit-platform-launcher-6.0.3.pom"))
                .isRegularFile();
        assertThat(central.resolve("org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar"))
                .isRegularFile();
        assertThat(central.resolve("org/scala-sbt/zinc_3"))
                .as("only the JUnit Platform is the suite's to warm; the rest is exact-pinned and fetched once")
                .doesNotExist();
        assertThat(sandbox.resolve("repos/google")).doesNotExist();

        String launcherList = Files.readString(metadata(sandbox, LAUNCHER));
        assertThat(launcherList)
                .contains("<groupId>org.junit.platform</groupId>")
                .contains("<artifactId>junit-platform-launcher</artifactId>")
                .contains("<latest>6.1.3</latest>")
                .contains("<release>6.1.3</release>")
                .contains("<version>6.0.3</version>")
                .contains("<version>6.1.3</version>");
        assertThat(launcherList.indexOf("<version>6.0.3</version>"))
                .as("ascending, as Central writes it")
                .isLessThan(launcherList.indexOf("<version>6.1.3</version>"));
        assertThat(metadata(sandbox, "org/opentest4j/opentest4j")).isRegularFile();
        // 6 launcher files + 2 opentest4j files + 2 version lists
        assertThat(seeded).isEqualTo(10);
    }

    /** A version whose POM the host never fetched cannot be solved from, so the list does not offer it. */
    @Test
    void a_version_without_a_pom_is_linked_but_not_advertised(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host");
        artifact(host, "central", LAUNCHER, "6.1.3", ".pom", ".jar");
        artifact(host, "central", LAUNCHER, "6.1.4", ".jar");

        TestStoreSeed.seed(host, tmp.resolve("sandbox"));

        assertThat(tmp.resolve("sandbox/repos/central/" + LAUNCHER + "/6.1.4/junit-platform-launcher-6.1.4.jar"))
                .isRegularFile();
        assertThat(Files.readString(metadata(tmp.resolve("sandbox"), LAUNCHER)))
                .contains("<latest>6.1.3</latest>")
                .doesNotContain("6.1.4");
    }

    /**
     * Maven-local adoption leaves the jar in the host store and the POM in {@code ~/.m2}. Completing
     * the POM from that tree is what lets {@code latest} resolve without Central.
     */
    @Test
    void a_jar_the_host_stored_without_a_pom_is_completed_from_m2(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host");
        artifact(host, RepositorySpec.CENTRAL, LAUNCHER, "6.1.3", ".jar");
        Path m2 = tmp.resolve("m2");
        m2File(m2, LAUNCHER, "6.1.3", ".pom", RepositorySpec.CENTRAL);
        m2File(m2, "org/junit/junit-bom", "6.1.3", ".pom", RepositorySpec.CENTRAL);
        Path sandbox = tmp.resolve("sandbox");

        TestStoreSeed.seed(host, sandbox, m2);

        assertThat(sandbox.resolve("repos/central/" + LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.pom"))
                .isRegularFile();
        assertThat(sandbox.resolve("repos/central/org/junit/junit-bom/6.1.3/junit-bom-6.1.3.pom"))
                .isRegularFile();
        assertThat(Files.readString(metadata(sandbox, LAUNCHER))).contains("<latest>6.1.3</latest>");
    }

    @Test
    void a_same_store_seed_completes_missing_poms_from_m2(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        artifact(store, RepositorySpec.CENTRAL, LAUNCHER, "6.1.3", ".jar");
        Path m2 = tmp.resolve("m2");
        m2File(m2, LAUNCHER, "6.1.3", ".pom", RepositorySpec.CENTRAL);

        assertThat(TestStoreSeed.seed(store, store, m2)).isGreaterThan(0);
        assertThat(store.resolve("repos/central/" + LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.pom"))
                .isRegularFile();
        assertThat(Files.readString(metadata(store, LAUNCHER))).contains("<latest>6.1.3</latest>");
    }

    /**
     * The gate's shared test-m2 ({@code JK_M2_LOCAL}) holds POMs a jar-only {@code ~/.m2} lacks.
     * {@link TestStoreSeed#complete} must read that tree first, or fixture locks dial Central.
     */
    @Test
    void complete_takes_junit_poms_from_jk_m2_local_when_user_m2_has_only_jars(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        artifact(store, RepositorySpec.CENTRAL, LAUNCHER, "6.1.3", ".jar");

        Path home = tmp.resolve("home");
        Path userJunit = home.resolve(".m2/repository/org/junit");
        Files.createDirectories(userJunit);
        Path userJar = home.resolve(".m2/repository")
                .resolve(LAUNCHER)
                .resolve("6.1.3")
                .resolve("junit-platform-launcher-6.1.3.jar");
        Files.createDirectories(userJar.getParent());
        Files.writeString(userJar, "jar");

        Path local = tmp.resolve("test-m2");
        m2File(local, LAUNCHER, "6.1.3", ".pom", RepositorySpec.CENTRAL);

        String prevHome = System.getProperty("user.home");
        String prevM2 = System.getProperty("jk.m2.local");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("jk.m2.local", local.toString());
            assertThat(TestStoreSeed.complete(store)).isGreaterThan(0);
            assertThat(store.resolve("repos/central/" + LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.pom"))
                    .isRegularFile();
            assertThat(Files.readString(metadata(store, LAUNCHER))).contains("<latest>6.1.3</latest>");
        } finally {
            if (prevHome != null) System.setProperty("user.home", prevHome);
            else System.clearProperty("user.home");
            if (prevM2 != null) System.setProperty("jk.m2.local", prevM2);
            else System.clearProperty("jk.m2.local");
        }
    }

    /**
     * A local repository holds what any repository answered under a coordinate: a lock test's stub
     * server publishes an empty jar as {@code org.junit.support:testng-engine} and jk's write-through
     * records it under Central's path with the stub's id. Seeded as Central's, those bytes would be
     * pinned in every sandbox and the forked runner would find no engine in the jar. Only a file
     * whose hint names Central is Central's; a body nobody vouches for is not either.
     */
    @Test
    void a_body_another_repository_served_or_nobody_vouches_for_is_not_seeded_as_centrals(@TempDir Path tmp)
            throws Exception {
        Path host = tmp.resolve("host");
        artifact(host, RepositorySpec.CENTRAL, LAUNCHER, "6.1.3", ".pom", ".jar");
        artifact(host, RepositorySpec.CENTRAL, "org/junit/support/testng-engine", "1.1.0", ".jar");
        Path m2 = tmp.resolve("m2");
        m2File(m2, "org/junit/support/testng-engine", "1.1.0", ".jar", "local");
        m2File(m2, "org/junit/support/testng-engine", "1.1.0", ".pom", "local");
        m2File(m2, "org/junit/jupiter/junit-jupiter", "6.1.3", ".pom", null);
        m2File(m2, "org/junit/vintage/junit-vintage-engine", "6.1.3", ".jar", RepositorySpec.CENTRAL);
        Path sandbox = tmp.resolve("sandbox");

        TestStoreSeed.seed(host, sandbox, m2);

        Path central = sandbox.resolve("repos").resolve(RepositorySpec.CENTRAL);
        assertThat(central.resolve("org/junit/support/testng-engine/1.1.0/testng-engine-1.1.0.pom"))
                .as("a POM another repository served does not complete the host's jar")
                .doesNotExist();
        assertThat(metadata(sandbox, "org/junit/support/testng-engine"))
                .as("so the version is not advertised either")
                .doesNotExist();
        assertThat(central.resolve("org/junit/jupiter/junit-jupiter/6.1.3/junit-jupiter-6.1.3.pom"))
                .as("no hint, no provenance")
                .doesNotExist();
        assertThat(central.resolve("org/junit/vintage/junit-vintage-engine/6.1.3/junit-vintage-engine-6.1.3.jar"))
                .isRegularFile();
    }

    /** A second seed finds nothing to link, and an index the sandbox fetched itself is kept. */
    @Test
    void a_second_seed_finds_nothing_to_do_and_a_real_index_already_there_is_kept(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host");
        artifact(host, RepositorySpec.CENTRAL, LAUNCHER, "6.1.3", ".pom", ".jar");
        Path sandbox = tmp.resolve("sandbox");
        Path body = metadata(sandbox, LAUNCHER);
        Files.createDirectories(body.getParent());
        Files.writeString(body, "<metadata>the store's own fetch</metadata>");

        assertThat(TestStoreSeed.seed(host, sandbox))
                .as("the two launcher files alone")
                .isEqualTo(2);
        assertThat(Files.readString(body)).isEqualTo("<metadata>the store's own fetch</metadata>");
        assertThat(TestStoreSeed.seed(host, sandbox)).isZero();
    }

    /**
     * A slot seeded when the host knew one launcher version must lock a version the host fetched
     * later: the synthesised list follows the host, where an index the store fetched itself is
     * not touched.
     */
    @Test
    void a_synthesised_version_list_follows_the_host_store(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host");
        artifact(host, RepositorySpec.CENTRAL, LAUNCHER, "6.1.3", ".pom", ".jar");
        Path sandbox = tmp.resolve("sandbox");
        TestStoreSeed.seed(host, sandbox);
        assertThat(Files.readString(metadata(sandbox, LAUNCHER)))
                .contains(TestStoreSeed.SEED_MARK)
                .contains("<latest>6.1.3</latest>")
                .doesNotContain("6.1.4");

        artifact(host, RepositorySpec.CENTRAL, LAUNCHER, "6.1.4", ".pom", ".jar");
        assertThat(TestStoreSeed.seed(host, sandbox))
                .as("two new files and the rewritten list")
                .isEqualTo(3);
        assertThat(Files.readString(metadata(sandbox, LAUNCHER)))
                .contains("<version>6.1.3</version>")
                .contains("<version>6.1.4</version>")
                .contains("<latest>6.1.4</latest>");
        assertThat(TestStoreSeed.seed(host, sandbox)).as("settled").isZero();
    }

    @Test
    void one_store_named_twice_or_a_host_without_central_is_a_no_op(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        artifact(store, "central", LAUNCHER, "6.1.3", ".pom", ".jar");
        assertThat(TestStoreSeed.seed(store, tmp.resolve("./store"))).isZero();
        assertThat(TestStoreSeed.seed(tmp.resolve("empty"), tmp.resolve("sandbox")))
                .isZero();
        assertThat(tmp.resolve("sandbox")).doesNotExist();
    }

    /** The key is the one the metadata cache derives from the Central URL. */
    @Test
    void the_version_list_sits_under_the_metadata_caches_key() {
        assertThat(TestStoreSeed.metadataKey(Path.of(LAUNCHER)))
                .isEqualTo(
                        Hashing.sha256Hex("https://repo.maven.apache.org/maven2/" + LAUNCHER + "/maven-metadata.xml"));
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
        Path file = Files.writeString(dir.resolve(name), ext);
        if (servedBy != null) {
            Files.writeString(
                    dir.resolve("_remote.repositories"),
                    name + ">" + servedBy + "=\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        return file;
    }

    private static void artifact(Path store, String origin, String artifactDir, String version, String... extensions)
            throws Exception {
        Path dir = Files.createDirectories(
                store.resolve("repos").resolve(origin).resolve(artifactDir).resolve(version));
        String name = Path.of(artifactDir).getFileName() + "-" + version;
        for (String ext : extensions) Files.writeString(dir.resolve(name + ext), ext);
    }
}
