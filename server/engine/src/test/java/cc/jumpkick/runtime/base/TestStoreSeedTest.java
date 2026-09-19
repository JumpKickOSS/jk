// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.RepositorySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path pom = m2.resolve(LAUNCHER).resolve("6.1.3").resolve("junit-platform-launcher-6.1.3.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project/>");
        Path bom = m2.resolve("org/junit/junit-bom/6.1.3/junit-bom-6.1.3.pom");
        Files.createDirectories(bom.getParent());
        Files.writeString(bom, "<project/>");
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
        Path pom = m2.resolve(LAUNCHER).resolve("6.1.3").resolve("junit-platform-launcher-6.1.3.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project/>");

        assertThat(TestStoreSeed.seed(store, store, m2)).isGreaterThan(0);
        assertThat(store.resolve("repos/central/" + LAUNCHER + "/6.1.3/junit-platform-launcher-6.1.3.pom"))
                .isRegularFile();
        assertThat(Files.readString(metadata(store, LAUNCHER))).contains("<latest>6.1.3</latest>");
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

    private static void artifact(Path store, String origin, String artifactDir, String version, String... extensions)
            throws Exception {
        Path dir = Files.createDirectories(
                store.resolve("repos").resolve(origin).resolve(artifactDir).resolve(version));
        String name = Path.of(artifactDir).getFileName() + "-" + version;
        for (String ext : extensions) Files.writeString(dir.resolve(name + ext), ext);
    }
}
