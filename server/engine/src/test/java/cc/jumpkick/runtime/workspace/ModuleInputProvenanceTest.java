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
import java.util.List;
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

    /**
     * A classes transform's output is what the module runs from, and the compile goal rewrites it
     * (a cheaper recompile path): bytes there that are not the recorded ones schedule the module,
     * whose step restores its own output. A record taken before the tree was covered schedules it
     * once too.
     */
    @Test
    void a_rewritten_classes_transform_output_schedules_the_module(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        BuildGraph.BuildUnit unit = graph.topoOrder().getFirst();
        Path module = tmp.toAbsolutePath().normalize();
        Path transformed = BuildLayout.of(tmp, unit.manifest())
                .pluginStepScratch("binding")
                .resolve("classes");
        Path binding = Files.createDirectories(transformed).resolve("Binding.class");
        Files.write(binding, new byte[] {1, 2, 3});
        ModuleInputProvenance.RunTrees runTrees = (build, layout) -> List.of(transformed);
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        String fp = fps.fingerprints().get(module);

        ModuleInputProvenance.record(tmp, graph, fps.fingerprints());
        assertThat(ModuleInputProvenance.outputsFromOtherInputs(tmp, module, unit.manifest(), fp, runTrees))
                .as("a record that never covered the tree vouches for none of it")
                .isTrue();

        ModuleInputProvenance.record(tmp, graph, fps.fingerprints(), runTrees);
        assertThat(ModuleInputProvenance.outputsFromOtherInputs(tmp, module, unit.manifest(), fp, runTrees))
                .isFalse();

        Thread.sleep(20);
        Files.write(binding, new byte[] {7});
        assertThat(ModuleInputProvenance.outputsFromOtherInputs(tmp, module, unit.manifest(), fp, runTrees))
                .as("the recompile's output in place of the build's")
                .isTrue();
    }

    /**
     * A build at another version writes its jar under another name, so a jar already at this
     * version's name is not the one the record vouches for, however old: the module is scheduled,
     * not skipped over a stale jar that the fat jar and native image would then link.
     */
    @Test
    void a_jar_at_this_versions_name_left_by_an_older_build_schedules_the_module(@TempDir Path tmp) throws Exception {
        BuildGraph.Result release = project(tmp);
        Path module = tmp.toAbsolutePath().normalize();
        BuildLayout releaseLayout =
                BuildLayout.of(tmp, release.topoOrder().getFirst().manifest());
        Files.write(Files.createDirectories(releaseLayout.classesDir()).resolve("App.class"), new byte[] {1, 2, 3});
        Files.write(releaseLayout.mainJar(), new byte[] {1, 2, 3, 4});
        var fps = PreflightMemo.snapshotFingerprints(release, false);
        PreflightMemo.storeDirty(tmp, release, false, Set.of(), fps.fingerprints());
        ModuleInputProvenance.record(tmp, release, fps.fingerprints());

        BuildGraph.Result snapshot = BuildGraph.resolve(
                tmp,
                JkBuildParser.parse(
                        Files.readString(tmp.resolve("jk.toml")).replace("\"0.1.0\"", "\"0.1.0-SNAPSHOT\"")));
        var snapshotBuild = snapshot.topoOrder().getFirst().manifest();
        Path snapshotJar = BuildLayout.of(tmp, snapshotBuild).mainJar();
        assertThat(snapshotJar).isNotEqualTo(releaseLayout.mainJar());
        String fp = fps.fingerprints().get(module);
        assertThat(ModuleInputProvenance.outputsFromOtherInputs(tmp, module, snapshotBuild, fp))
                .as("no jar at this version's name yet: the restore's to bring back")
                .isFalse();

        Files.write(snapshotJar, new byte[] {9, 9});
        assertThat(ModuleInputProvenance.outputsFromOtherInputs(tmp, module, snapshotBuild, fp))
                .isTrue();
        assertThat(ModuleInputProvenance.outputsFromOtherInputs(
                        tmp, module, release.topoOrder().getFirst().manifest(), fp))
                .as("the release build's own jar is still the recorded one")
                .isFalse();
        assertThat(BuildForecasting.forecastWithFingerprints(snapshot, tmp.resolve("cache"), false, tmp)
                        .dirty())
                .contains(module);
    }
}
