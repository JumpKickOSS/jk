// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A clean preflight verdict has to mean "the artifacts here are the ones these inputs produce", not
 * "some artifacts are here". It used to mean the second: outputs were only ever tested for
 * existence, so a module could be skipped with a jar built from other sources while the build
 * reported {@code all modules up to date}.
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
        Files.createDirectories(BuildLayout.moduleTargetDir(tmp, tmp));
        return BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
    }

    /** The defect: inputs unchanged, memo clean, but the outputs came from a different build. */
    @Test
    void outputs_from_other_inputs_make_the_module_dirty(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps);
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
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps);
        ModuleInputProvenance.record(tmp, graph, fps);

        var forecast = BuildForecasting.forecastWithFingerprints(graph, tmp.resolve("cache"), false, tmp);
        assertThat(forecast.dirty()).isEmpty();
    }

    /**
     * No record is not evidence of staleness. Every target dir built by an earlier jk has none, and
     * reading absence as dirty would make one upgrade recompile the world.
     */
    @Test
    void a_missing_record_is_not_a_rebuild(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps);

        var forecast = BuildForecasting.forecastWithFingerprints(graph, tmp.resolve("cache"), false, tmp);
        assertThat(forecast.dirty()).isEmpty();
    }
}
