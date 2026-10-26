// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
}
