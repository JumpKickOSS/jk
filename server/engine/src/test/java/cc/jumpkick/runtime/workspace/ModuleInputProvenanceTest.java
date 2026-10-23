// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.runtime.PreflightMemo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A clean preflight verdict means "the artifacts here are the ones these inputs produce", never
 * merely "some artifacts are here". Existence alone cannot carry that: a jar built from other
 * sources exists too, and a module skipped on that basis leaves a stale artifact behind a green
 * build. The provenance record is what separates the two, so these pin both directions of it.
 */
class ModuleInputProvenanceTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static BuildGraph.Result project(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);
        Path src = tmp.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        Files.createDirectories(BuildLayout.moduleTargetDir(tmp));
        return BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
    }

    /** The defect: inputs unchanged, memo clean, but the outputs came from a different build. */
    @Test
    void outputs_from_other_inputs_make_the_module_dirty(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps.fingerprints());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        ModuleInputProvenance.record(tmp, graph, Map.of(tmp.toAbsolutePath().normalize(), "adifferentfingerprint"));

        var forecast = BuildForecasting.forecastWithFingerprints(graph, tmp.resolve("cache"), false, tmp);
        assertThat(forecast.dirty()).contains(tmp.toAbsolutePath().normalize());
    }

    /** The ordinary case stays free: the record agrees, so nothing is scheduled. */
    @Test
    void matching_provenance_stays_up_to_date(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps.fingerprints());
        ModuleInputProvenance.record(tmp, graph, fps.fingerprints());

        var forecast = BuildForecasting.forecastWithFingerprints(graph, tmp.resolve("cache"), false, tmp);
        assertThat(forecast.dirty()).isEmpty();
    }

    /** Nothing vouches for outputs no successful build recorded: the module is scheduled to restore. */
    @Test
    void a_missing_record_schedules_the_module(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps.fingerprints());

        var forecast = BuildForecasting.forecastWithFingerprints(graph, tmp.resolve("cache"), false, tmp);
        assertThat(forecast.dirty()).contains(tmp.toAbsolutePath().normalize());
    }

    /**
     * A class replaced or added in target/classes after the build is not jk's output. One that is
     * gone is a restore, judged against the compile record.
     */
    @Test
    void a_class_changed_or_added_after_the_build_schedules_the_module(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        Path classes =
                BuildLayout.of(tmp, graph.topoOrder().getFirst().manifest()).classesDir();
        Path app = Files.createDirectories(classes).resolve("App.class");
        Files.write(app, new byte[] {1, 2, 3});
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps.fingerprints());
        ModuleInputProvenance.record(tmp, graph, fps.fingerprints());
        Path module = tmp.toAbsolutePath().normalize();
        assertThat(BuildForecasting.forecastWithFingerprints(graph, tmp.resolve("cache"), false, tmp)
                        .dirty())
                .isEmpty();

        Thread.sleep(20);
        Files.write(app, new byte[] {9, 9, 9});
        assertThat(BuildForecasting.forecastWithFingerprints(graph, tmp.resolve("cache"), false, tmp)
                        .dirty())
                .as("a replaced class")
                .contains(module);

        Files.write(app, new byte[] {1, 2, 3});
        Files.write(classes.resolve("Foreign.class"), new byte[] {4});
        assertThat(BuildForecasting.forecastWithFingerprints(graph, tmp.resolve("cache"), false, tmp)
                        .dirty())
                .as("an added class")
                .contains(module);
    }
}
