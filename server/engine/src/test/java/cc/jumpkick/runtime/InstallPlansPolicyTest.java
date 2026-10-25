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
import cc.jumpkick.repo.M2InstallMemo;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModuleOutcome.Installed.Change;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What an install may do to a Maven local repository slot: write it, leave it, replace it, or refuse. */
class InstallPlansPolicyTest {

    private static final String STORE_PROPERTY = "jk.env.JK_STORE_DIR";

    @TempDir
    Path tmp;

    private String previousStore;
    private Path m2Dir;
    private Path cache;

    @BeforeEach
    void isolateTheStore() {
        previousStore = System.getProperty(STORE_PROPERTY);
        System.setProperty(STORE_PROPERTY, tmp.resolve("store").toAbsolutePath().toString());
        m2Dir = tmp.resolve("m2");
        cache = tmp.resolve("cache");
    }

    @AfterEach
    void restoreTheStore() {
        if (previousStore != null) System.setProperty(STORE_PROPERTY, previousStore);
        else System.clearProperty(STORE_PROPERTY);
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

    @Test
    void a_snapshot_jk_did_not_install_is_never_replaced() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        Files.createDirectories(slotJar("1.0.0-SNAPSHOT").getParent());
        Files.writeString(slotJar("1.0.0-SNAPSHOT"), "from maven");

        assertThatThrownBy(() -> install(project, built(project, "v1")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk did not install it");
        assertThat(Files.readString(slotJar("1.0.0-SNAPSHOT"))).isEqualTo("from maven");
    }

    @Test
    void a_snapshot_edited_after_jk_wrote_it_is_not_jk_s_to_replace() throws Exception {
        JkBuild project = project("1.0.0-SNAPSHOT");
        install(project, built(project, "v1"));
        Files.writeString(slotJar("1.0.0-SNAPSHOT"), "hand edited");

        assertThatThrownBy(() -> install(project, built(project, "v2")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk did not install it");
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

    @Test
    void a_release_already_installed_is_refused_before_building() throws Exception {
        JkBuild project = project("1.0.0");
        Path moduleDir = tmp.resolve("mod");
        Files.createDirectories(moduleDir);
        assertThat(InstallPlans.refusedBeforeBuild(project, moduleDir, m2Dir, null))
                .as("an empty slot installs")
                .isNull();

        install(project, built(project, "v1"));

        assertThat(InstallPlans.refusedBeforeBuild(project, moduleDir, m2Dir, null))
                .contains("ex.acme:money:1.0.0 is already installed");
        assertThat(InstallPlans.refusedBeforeBuild(project, moduleDir, m2Dir, InstallDestination.JK))
                .as("the private repository is not the slot that is taken")
                .isNull();
        assertThat(InstallPlans.refusedBeforeBuild(project("1.0.0-SNAPSHOT"), moduleDir, m2Dir, null))
                .as("a snapshot is meant to be reinstalled")
                .isNull();
    }

    @Test
    void the_destination_is_the_flag_then_the_project_then_the_maven_local_repository() {
        Path moduleDir = tmp.resolve("mod");
        JkBuild unset = project("1.0.0");
        JkBuild declared = JkBuild.builder(Project.builder("ex.acme", "money", "1.0.0")
                        .jdkMajor(25)
                        .java(25)
                        .installDestination(InstallDestination.JK)
                        .build())
                .build();

        assertThat(InstallPlans.destinationOf(unset, moduleDir, null)).isEqualTo(InstallDestination.M2);
        assertThat(InstallPlans.destinationOf(declared, moduleDir, null)).isEqualTo(InstallDestination.JK);
        assertThat(InstallPlans.destinationOf(declared, moduleDir, InstallDestination.M2))
                .isEqualTo(InstallDestination.M2);
        assertThat(InstallPlans.destinationOf(unset, moduleDir, InstallDestination.JK))
                .isEqualTo(InstallDestination.JK);
    }
}
