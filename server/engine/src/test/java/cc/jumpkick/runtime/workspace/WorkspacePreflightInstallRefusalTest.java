// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private Optional<BuildForecasting.Preflight> forecast(BuildGraph.BuildUnit unit, TaskForecast.Status jar) {
        TaskForecast.Module module = new TaskForecast.Module(
                unit.dir(),
                unit.coord(),
                List.of(new TaskForecast.Task(TaskNames.PACKAGE_JAR, jar, "", null)),
                1,
                0,
                true,
                false);
        return Optional.of(
                new BuildForecasting.Preflight(Set.of(unit.dir()), Set.of(), Map.of(), List.of(module), Map.of()));
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
    void a_rebuilt_jar_for_a_release_that_is_already_installed_is_refused() throws Exception {
        BuildGraph.BuildUnit unit = module("1.0.0");
        occupy("1.0.0");

        List<String> refused = WorkspacePreflightPhase.installRefusals(
                request(null), List.of(unit), forecast(unit, TaskForecast.Status.RUN));

        assertThat(refused).singleElement().asString().contains("ex:lib:1.0.0").contains("jk did not install it");
    }

    @Test
    void a_jar_the_forecast_says_is_up_to_date_is_left_to_the_install_step() throws Exception {
        BuildGraph.BuildUnit unit = module("1.0.0");
        occupy("1.0.0");

        assertThat(WorkspacePreflightPhase.installRefusals(
                        request(null), List.of(unit), forecast(unit, TaskForecast.Status.CACHED)))
                .isEmpty();
    }

    @Test
    void a_snapshot_an_empty_slot_and_the_private_target_are_never_refused_early() throws Exception {
        BuildGraph.BuildUnit snapshot = module("1.0.0-SNAPSHOT");
        occupy("1.0.0-SNAPSHOT");
        assertThat(WorkspacePreflightPhase.installRefusals(
                        request(null), List.of(snapshot), forecast(snapshot, TaskForecast.Status.RUN)))
                .isEmpty();

        BuildGraph.BuildUnit release = module("2.0.0");
        assertThat(WorkspacePreflightPhase.installRefusals(
                        request(null), List.of(release), forecast(release, TaskForecast.Status.RUN)))
                .as("nothing is installed at 2.0.0")
                .isEmpty();

        occupy("2.0.0");
        assertThat(WorkspacePreflightPhase.installRefusals(
                        request(InstallDestination.JK), List.of(release), forecast(release, TaskForecast.Status.RUN)))
                .isEmpty();
    }

    @Test
    void a_snapshot_hidden_by_its_installed_release_is_refused_without_a_forecast() throws Exception {
        BuildGraph.BuildUnit snapshot = module("1.0.0-SNAPSHOT");
        occupy("1.0.0");

        assertThat(WorkspacePreflightPhase.installRefusals(request(null), List.of(snapshot), Optional.empty()))
                .singleElement()
                .asString()
                .contains("would be hidden by the release ex:lib:1.0.0");
        assertThat(WorkspacePreflightPhase.installRefusals(
                        request(null), List.of(snapshot), forecast(snapshot, TaskForecast.Status.CACHED)))
                .as("even a jar the forecast says is current")
                .hasSize(1);
    }

    @Test
    void no_forecast_means_no_early_refusal() throws Exception {
        BuildGraph.BuildUnit unit = module("1.0.0");
        occupy("1.0.0");

        assertThat(WorkspacePreflightPhase.installRefusals(request(null), List.of(unit), Optional.empty()))
                .isEmpty();
    }
}
