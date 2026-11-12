// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An install of a release version that is already in the Maven local repository fails before it builds. */
class WorkspacePreflightInstallRefusalTest {

    @TempDir
    Path tmp;

    private BuildGraph.BuildUnit module(String version) throws Exception {
        Path dir = tmp.resolve("lib");
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(
                dir.resolve("jk.toml"), "group = \"ex\"\nname = \"lib\"\nversion = \"" + version + "\"\njava = 25\n");
        Files.writeString(dir.resolve("src/main/java/ex/Lib.java"), "package ex; public final class Lib {}\n");
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
        return new BuildGraph.BuildUnit(dir, build, "ex:lib", BuildGraph.Origin.MODULE);
    }

    private WorkspaceRequest request(@Nullable InstallDestination target) {
        return new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, true, false, 0, null, true, true)
                .withSpec(WorkspaceSpec.install(Set.of(), Map.of(), tmp.resolve("m2"), target));
    }

    private void occupy(String version) throws Exception {
        Path dir = tmp.resolve("m2/repository/ex/lib").resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("lib-" + version + ".jar"), "someone else's bytes");
    }

    @Test
    void a_release_that_is_already_installed_is_left_to_the_install_step() throws Exception {
        BuildGraph.BuildUnit unit = module("1.0.0");
        occupy("1.0.0");

        assertThat(WorkspacePreflightPhase.installRefusals(request(null), List.of(unit)))
                .as("a rebuild can reproduce the bytes; the install compares them")
                .isEmpty();
    }

    @Test
    void a_snapshot_hidden_by_its_installed_release_is_refused() throws Exception {
        BuildGraph.BuildUnit snapshot = module("1.0.0-SNAPSHOT");
        occupy("1.0.0");

        assertThat(WorkspacePreflightPhase.installRefusals(request(null), List.of(snapshot)))
                .singleElement()
                .asString()
                .contains("would be hidden by the release ex:lib:1.0.0");
    }

    @Test
    void a_snapshot_with_no_release_beside_it_is_not_refused() throws Exception {
        BuildGraph.BuildUnit snapshot = module("1.0.0-SNAPSHOT");
        occupy("1.0.0-SNAPSHOT");

        assertThat(WorkspacePreflightPhase.installRefusals(request(null), List.of(snapshot)))
                .isEmpty();
    }

    @Test
    void with_no_m2_dir_the_configured_local_repository_is_the_one_checked() throws Exception {
        BuildGraph.BuildUnit snapshot = module("1.0.0-SNAPSHOT");
        occupy("1.0.0");
        System.setProperty(M2Dirs.LOCAL_PROPERTY, tmp.resolve("m2/repository").toString());
        WorkspaceRequest request = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 0, null, true, false, 0, null, true, true)
                .withSpec(WorkspaceSpec.install(Set.of(), Map.of(), null, null));

        assertThat(WorkspacePreflightPhase.installRefusals(request, List.of(snapshot)))
                .as("an install with no --m2-dir goes where the configured local repository is")
                .singleElement()
                .asString()
                .contains("would be hidden by the release ex:lib:1.0.0");
    }

    @Test
    void a_module_whose_jar_holds_other_classes_is_rebuilt_with_its_dependents() throws Exception {
        // JK-MAG71: a clean claim skipped the module, and the install shipped a jar packaged earlier.
        BuildGraph.BuildUnit lib = module("1.0.0-SNAPSHOT");
        Path appDir = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(
                appDir.resolve("jk.toml"), "group = \"ex\"\nname = \"app\"\nversion = \"1.0.0\"\njava = 25\n");
        BuildGraph.BuildUnit app = new BuildGraph.BuildUnit(
                appDir, JkBuildParser.parse(appDir.resolve("jk.toml")), "ex:app", BuildGraph.Origin.MODULE);
        BuildLayout layout = BuildLayout.of(lib.dir(), lib.manifest());
        Path cls = layout.classesDir().resolve("ex/Lib.class");
        Files.createDirectories(cls.getParent());
        Files.write(cls, new byte[] {1, 2});
        Files.createDirectories(layout.mainJar().getParent());
        try (OutputStream out = Files.newOutputStream(layout.mainJar());
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("ex/Lib.class"));
            zip.write(new byte[] {1});
            zip.closeEntry();
        }
        var graph =
                new BuildGraph.Result(List.of(lib, app), Map.of(appDir, Set.of(lib.dir())), Map.of(), List.of(), false);
        List<String> notes = new ArrayList<>();
        WorkspaceBuildListener listener = new WorkspaceBuildListener() {
            @Override
            public void onNote(String text) {
                notes.add(text);
            }
        };

        assertThat(WorkspacePreflightPhase.withStaleInstallJars(
                        request(null), graph, List.of(lib, app), Set.of(), listener))
                .containsExactlyInAnyOrder(lib.dir(), appDir);
        assertThat(notes)
                .singleElement()
                .asString()
                .contains(layout.mainJar().getFileName().toString());

        Files.write(cls, new byte[] {1});
        assertThat(WorkspacePreflightPhase.withStaleInstallJars(
                        request(null), graph, List.of(lib, app), Set.of(), listener))
                .as("a jar packaged from its classes is installed as it is")
                .isEmpty();
    }
}
