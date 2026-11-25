// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.repo.M2CompatWriter;
import cc.jumpkick.repo.M2InstallMemo;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModuleOutcome.Installed.Change;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What an install may do to a Maven local repository slot: write it, leave it, replace it, or refuse. */
class InstallPlansPolicyTest {

    private static final String STORE_PROPERTY = "jk.env.JK_STORE_DIR";

    @TempDir
    Path tmp;

    private Path m2Dir;
    private Path cache;

    @BeforeEach
    void isolateTheStore() {
        System.setProperty(STORE_PROPERTY, tmp.resolve("store").toAbsolutePath().toString());
        m2Dir = tmp.resolve("m2");
        cache = tmp.resolve("cache");
    }

    @AfterEach
    void forgetTheMode() {
        System.clearProperty("jk.env.JK_M2_INTEGRATION");
    }

    private JkBuild project(String version) {
        return JkBuild.builder(Project.builder("ex.acme", "money", version)
                        .jdkMajor(25)
                        .java(25)
                        .build())
                .build();
    }

    /** A built module whose thin jar holds {@code jarBytes}. */
    private BuildLayout built(JkBuild project, String jarBytes) throws IOException {
        Path mod = tmp.resolve("mod");
        Files.createDirectories(mod);
        BuildLayout layout = BuildLayout.of(mod, project);
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), jarBytes);
        return layout;
    }

    private ModuleOutcome.Installed install(JkBuild project, BuildLayout layout) throws IOException {
        return InstallPlans.cacheInstallArtifact(project, layout, cache, m2Dir, InstallDestination.M2);
    }

    private Path slotJar(String version) {
        return m2Dir.resolve("repository")
                .resolve(MavenLayout.artifactPath(Coordinate.of("ex.acme", "money", version)));
    }

    @Test
    void an_empty_slot_is_written_with_maven_sidecars_and_remembered() throws Exception {
        JkBuild project = project("1.0.0");
        BuildLayout layout = built(project, "v1");

        ModuleOutcome.Installed out = install(project, layout);

        assertThat(out.change()).isEqualTo(Change.NEW);
        assertThat(out.destination()).isEqualTo(InstallDestination.M2);
        assertThat(Files.readString(slotJar("1.0.0"))).isEqualTo("v1");
        assertThat(slotJar("1.0.0").resolveSibling("money-1.0.0.jar.sha1")).exists();
        assertThat(slotJar("1.0.0").resolveSibling("money-1.0.0.pom")).exists();
        assertThat(Files.readAllLines(slotJar("1.0.0").resolveSibling("_remote.repositories")))
                .as("Maven's own installs name no repository, so a build without one still accepts the files")
                .contains("money-1.0.0.jar>=", "money-1.0.0.pom>=");
        Path memo = M2InstallMemo.file(
                tmp.resolve("store"), m2Dir.resolve("repository"), Coordinate.of("ex.acme", "money", "1.0.0"));
        assertThat(M2InstallMemo.read(memo)).isPresent();
        assertThat(tmp.resolve("store/repos/jk-local"))
                .as("nothing is written to the private repository")
                .doesNotExist();
    }

    @Test
    void maven_local_installs_library_attachments_beside_the_thin_jar() throws Exception {
        JkBuild project = project("1.0.0");
        BuildLayout layout = built(project, "THIN");
        install(project, layout);
        Files.writeString(layout.sourcesJar(), "SOURCES");
        Files.writeString(layout.javadocJar(), "JAVADOC");
        Files.writeString(layout.assemblyJar(), "ASSEMBLY");
        Files.writeString(layout.minifiedJar(), "MINIFIED");

        assertThat(InstallPlans.installedShas(project, layout, cache, m2Dir, InstallDestination.M2))
                .isNull();
        install(project, layout);

        Path jar = slotJar("1.0.0");
        assertThat(Files.readString(jar)).isEqualTo("THIN");
        for (Path source : List.of(layout.sourcesJar(), layout.javadocJar())) {
            Path installed = jar.resolveSibling(source.getFileName());
            assertThat(Files.mismatch(source, installed)).isEqualTo(-1);
            assertThat(installed.resolveSibling(installed.getFileName() + ".sha1"))
                    .exists();
            assertThat(installed.resolveSibling(installed.getFileName() + ".md5"))
                    .exists();
        }
        assertThat(jar.resolveSibling(layout.assemblyJar().getFileName())).doesNotExist();
        assertThat(jar.resolveSibling(layout.minifiedJar().getFileName())).doesNotExist();
        assertThat(InstallPlans.installedShas(project, layout, cache, m2Dir, InstallDestination.M2))
                .isNotNull();
    }

    @Test
    void the_same_bytes_again_change_nothing() throws Exception {
        JkBuild project = project("1.0.0");
        BuildLayout layout = built(project, "v1");
        install(project, layout);

        assertThat(install(project, layout).change()).isEqualTo(Change.UNCHANGED);
    }

    @Test
    void a_release_with_other_bytes_is_refused_and_left_alone() throws Exception {
        JkBuild project = project("1.0.0");
        install(project, built(project, "v1"));

        BuildLayout rebuilt = built(project, "v2");

        assertThatThrownBy(() -> install(project, rebuilt))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ex.acme:money:1.0.0 is already installed")
                .hasMessageContaining("raise the version in jk.toml");
        assertThat(Files.readString(slotJar("1.0.0"))).isEqualTo("v1");
    }

    @Test
    void a_snapshot_jk_installed_is_replaced() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        install(project, built(project, "v1"));

        ModuleOutcome.Installed out = install(project, built(project, "v2"));

        assertThat(out.change()).isEqualTo(Change.REPLACED);
        assertThat(Files.readString(slotJar("1.0.0-SNAPSHOT"))).isEqualTo("v2");
        assertThat(out.jarSha256()).isEqualTo(Hashing.sha256Hex(slotJar("1.0.0-SNAPSHOT")));
    }

    /**
     * A store sharing the Maven local repository judges a file by its memo's size and mtime, so a
     * replaced snapshot of the same size is stamped after the file it replaces, under the lock
     * every store takes to publish there.
     */
    @Test
    void a_replaced_snapshot_is_published_under_the_share_lock_and_newer_than_the_file_it_replaces() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        install(project, built(project, "v1"));
        Path jar = slotJar("1.0.0-SNAPSHOT");
        Path pom = jar.resolveSibling("money-1.0.0-SNAPSHOT.pom");
        long ahead = System.currentTimeMillis() + 3_600_000;
        Files.setLastModifiedTime(jar, FileTime.fromMillis(ahead));
        Files.setLastModifiedTime(pom, FileTime.fromMillis(ahead));

        assertThat(install(project, built(project, "v2")).change()).isEqualTo(Change.REPLACED);

        assertThat(Files.readString(jar)).isEqualTo("v2");
        assertThat(Files.getLastModifiedTime(jar).toMillis()).isGreaterThan(ahead);
        assertThat(Files.getLastModifiedTime(pom).toMillis()).isGreaterThan(ahead);
        assertThat(m2Dir.resolve("repository").resolve(RepoArtifactStore.M2_LOCK_NAME))
                .exists();
    }

    @Test
    void a_snapshot_jk_did_not_install_is_never_replaced() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        Files.createDirectories(slotJar("1.0.0-SNAPSHOT").getParent());
        Files.writeString(slotJar("1.0.0-SNAPSHOT"), "from maven");

        assertThatThrownBy(() -> install(project, built(project, "v1")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("JumpKick did not install it");
        assertThat(Files.readString(slotJar("1.0.0-SNAPSHOT"))).isEqualTo("from maven");
    }

    @Test
    void a_snapshot_edited_after_jk_wrote_it_is_not_jk_s_to_replace() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        install(project, built(project, "v1"));
        Files.writeString(slotJar("1.0.0-SNAPSHOT"), "hand edited");

        assertThatThrownBy(() -> install(project, built(project, "v2")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("JumpKick did not install it");
        assertThat(Files.readString(slotJar("1.0.0-SNAPSHOT"))).isEqualTo("hand edited");
    }

    @Test
    void identical_bytes_in_a_slot_nobody_recorded_are_adopted() throws Exception {
        JkBuild project = project("1.0.0");
        BuildLayout layout = built(project, "v1");
        install(project, layout);
        Path memo = M2InstallMemo.file(
                tmp.resolve("store"), m2Dir.resolve("repository"), Coordinate.of("ex.acme", "money", "1.0.0"));
        Files.delete(memo);

        assertThat(install(project, layout).change()).isEqualTo(Change.UNCHANGED);
        assertThat(M2InstallMemo.read(memo)).isPresent();
    }

    private Path memoOf(String version) {
        return M2InstallMemo.file(
                tmp.resolve("store"), m2Dir.resolve("repository"), Coordinate.of("ex.acme", "money", version));
    }

    /** Write {@code name} with its {@code .sha1} and {@code .md5} sidecars, as Maven's checksum contract names them. */
    private static void withSidecars(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        M2CompatWriter.MavenHashes hashes = M2CompatWriter.hashesOf(file);
        Files.writeString(dir.resolve(name + ".sha1"), hashes.sha1());
        Files.writeString(dir.resolve(name + ".md5"), hashes.md5());
    }

    @Test
    void a_snapshot_slot_an_earlier_jk_install_left_with_no_memo_is_replaced() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        Path dir = Files.createDirectories(slotJar("1.0.0-SNAPSHOT").getParent());
        withSidecars(dir, "money-1.0.0-SNAPSHOT.jar", "v1");
        withSidecars(dir, "money-1.0.0-SNAPSHOT.pom", "<project/>");
        Files.writeString(
                dir.resolve("_remote.repositories"),
                "#NOTE: This is a jk-written provenance hint for Maven tooling.\n"
                        + "money-1.0.0-SNAPSHOT.jar>=\n"
                        + "money-1.0.0-SNAPSHOT.pom>=\n");

        ModuleOutcome.Installed out = install(project, built(project, "v2"));

        assertThat(out.change()).isEqualTo(Change.REPLACED);
        assertThat(Files.readString(slotJar("1.0.0-SNAPSHOT"))).isEqualTo("v2");
        assertThat(M2InstallMemo.read(memoOf("1.0.0-SNAPSHOT")))
                .as("the replaced slot is remembered again")
                .isPresent();
    }

    @Test
    void a_snapshot_maven_installed_is_refused_with_no_memo() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        Path dir = Files.createDirectories(slotJar("1.0.0-SNAPSHOT").getParent());
        withSidecars(dir, "money-1.0.0-SNAPSHOT.jar", "from maven");
        withSidecars(dir, "money-1.0.0-SNAPSHOT.pom", "<project/>");
        Files.writeString(
                dir.resolve("_remote.repositories"),
                "#NOTE: This is a Maven Resolver internal implementation file, its format can be changed without"
                        + " prior notice.\n#Wed Nov 18 10:00:00 UTC 2026\n"
                        + "money-1.0.0-SNAPSHOT.jar>=\n"
                        + "money-1.0.0-SNAPSHOT.pom>=\n");

        assertThatThrownBy(() -> install(project, built(project, "v1")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("JumpKick did not install it");
        assertThat(Files.readString(slotJar("1.0.0-SNAPSHOT"))).isEqualTo("from maven");
    }

    @Test
    void a_snapshot_overwritten_after_jk_wrote_it_is_refused_with_no_memo() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        install(project, built(project, "v1"));
        Files.delete(memoOf("1.0.0-SNAPSHOT"));
        Files.writeString(slotJar("1.0.0-SNAPSHOT"), "from gradle");

        assertThatThrownBy(() -> install(project, built(project, "v2")))
                .as("the jar no longer matches the .sha1 jk wrote beside it")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("JumpKick did not install it");
        assertThat(Files.readString(slotJar("1.0.0-SNAPSHOT"))).isEqualTo("from gradle");
    }

    // ---- the private repository (repos/jk-local) follows the same rules --------------------------

    private ModuleOutcome.Installed installPrivate(JkBuild project, BuildLayout layout) throws IOException {
        return InstallPlans.cacheInstallArtifact(project, layout, cache, m2Dir, InstallDestination.JK);
    }

    private Path shelfJar(String version) {
        return tmp.resolve("store/repos/jk-local")
                .resolve(MavenLayout.artifactPath(Coordinate.of("ex.acme", "money", version)));
    }

    @Test
    void the_private_repository_writes_an_empty_slot_and_leaves_the_maven_repo_alone() throws Exception {
        JkBuild project = project("1.0.0");

        ModuleOutcome.Installed out = installPrivate(project, built(project, "v1"));

        assertThat(out.change()).isEqualTo(Change.NEW);
        assertThat(out.destination()).isEqualTo(InstallDestination.JK);
        assertThat(Files.readString(shelfJar("1.0.0"))).isEqualTo("v1");
        assertThat(m2Dir.resolve("repository")).doesNotExist();
    }

    @Test
    void a_release_in_the_private_repository_is_installed_once() throws Exception {
        JkBuild project = project("1.0.0");
        installPrivate(project, built(project, "v1"));
        BuildLayout rebuilt = built(project, "v2");

        assertThatThrownBy(() -> installPrivate(project, rebuilt))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ex.acme:money:1.0.0 is already installed")
                .hasMessageContaining("raise the version in jk.toml");
        assertThat(Files.readString(shelfJar("1.0.0"))).isEqualTo("v1");
    }

    @Test
    void a_snapshot_jk_wrote_to_the_private_repository_is_replaced() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        installPrivate(project, built(project, "v1"));

        ModuleOutcome.Installed out = installPrivate(project, built(project, "v2"));

        assertThat(out.change()).isEqualTo(Change.REPLACED);
        assertThat(Files.readString(shelfJar("1.0.0-SNAPSHOT"))).isEqualTo("v2");
    }

    @Test
    void a_file_nobody_recorded_in_the_private_repository_is_never_replaced() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        Files.createDirectories(shelfJar("1.0.0-SNAPSHOT").getParent());
        Files.writeString(shelfJar("1.0.0-SNAPSHOT"), "copied in by hand");

        assertThatThrownBy(() -> installPrivate(project, built(project, "v1")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("JumpKick did not install it");
        assertThat(Files.readString(shelfJar("1.0.0-SNAPSHOT"))).isEqualTo("copied in by hand");
    }

    @Test
    void a_snapshot_whose_release_is_installed_is_refused_as_invisible() throws Exception {
        // A machine that shares with Maven installs into the local repository when nothing names a destination.
        System.setProperty("jk.env.JK_M2_INTEGRATION", "share");
        JkBuild release = project("1.0.0");
        install(release, built(release, "v1"));
        JkBuild snapshot = project("1.0.0-SNAPSHOT");
        Path moduleDir = tmp.resolve("mod");

        assertThat(InstallPlans.shadowedByRelease(snapshot, moduleDir, m2Dir, null))
                .contains("ex.acme:money:1.0.0-SNAPSHOT would be hidden by the release ex.acme:money:1.0.0")
                .contains("raise the version in jk.toml");
        assertThat(InstallPlans.shadowedByRelease(release, moduleDir, m2Dir, null))
                .as("a release is not shadowed by anything")
                .isNull();
        assertThat(InstallPlans.shadowedByRelease(project("2.0.0-SNAPSHOT"), moduleDir, m2Dir, null))
                .as("no 2.0.0 is installed")
                .isNull();
        assertThat(InstallPlans.shadowedByRelease(snapshot, moduleDir, m2Dir, InstallDestination.JK))
                .as("the release is in the Maven local repository, not the private one")
                .isNull();
    }

    @Test
    void the_destination_is_the_flag_then_the_project_then_what_the_machines_m2_integration_implies() {
        Path moduleDir = tmp.resolve("mod");
        JkBuild unset = project("1.0.0");
        JkBuild declared = JkBuild.builder(Project.builder("ex.acme", "money", "1.0.0")
                        .jdkMajor(25)
                        .java(25)
                        .installDestination(InstallDestination.JK)
                        .build())
                .build();

        assertThat(InstallPlans.destinationOf(unset, moduleDir, null))
                .as("nothing is written into ~/.m2 unasked")
                .isEqualTo(InstallDestination.JK);
        assertThat(InstallPlans.destinationOf(unset, moduleDir, InstallDestination.M2))
                .as("the flag targets the Maven local repository without a config change")
                .isEqualTo(InstallDestination.M2);
        System.setProperty("jk.env.JK_M2_INTEGRATION", "share");
        assertThat(InstallPlans.destinationOf(unset, moduleDir, null)).isEqualTo(InstallDestination.M2);
        assertThat(InstallPlans.destinationOf(declared, moduleDir, null)).isEqualTo(InstallDestination.JK);
        assertThat(InstallPlans.destinationOf(declared, moduleDir, InstallDestination.M2))
                .isEqualTo(InstallDestination.M2);
        assertThat(InstallPlans.destinationOf(unset, moduleDir, InstallDestination.JK))
                .isEqualTo(InstallDestination.JK);
    }
}
