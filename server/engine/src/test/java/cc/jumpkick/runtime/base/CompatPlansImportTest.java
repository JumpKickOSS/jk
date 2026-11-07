// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ProjectImport;
import cc.jumpkick.gradle.GradleBuildImport;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk import} runs as staged steps, each reporting what it does, and a Gradle import checks no pins. */
class CompatPlansImportTest {

    private static final String MODEL = """
            {"gradle":"9.8.0","rootName":"shop","settingsRepositories":[],"projects":[
              {"path":":","projectName":"shop","dir":"","group":"com.acme","version":"1.0","plugins":[],"pluginClasses":[],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[]},
              {"path":":core","projectName":"core","dir":"core","group":"com.acme","version":"1.0",
               "plugins":["java"],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],
               "sourceSets":[{"name":"main","java":["src/main/java"],"resources":["src/main/resources"],"kotlin":[]}],
               "configurations":[],"tasks":[]}
            ]}
            """;

    @Test
    void a_gradle_import_reads_writes_skips_the_pin_check_and_reports(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\"core\")\n");
        Files.writeString(root.resolve("build.gradle.kts"), "");
        List<String> notes = Collections.synchronizedList(new ArrayList<>());
        List<String> raised = new ArrayList<>();
        GradleBuildImport gradle = GradleBuildImport.withModel((dir, plugins, progress) -> {
            progress.milestone("Gradle 9.8.0 on JDK 25 · evaluating shop");
            progress.grow(4);
            progress.advance(4);
            progress.detail("resolved :core (2/2)");
            return MODEL;
        });

        BuildPlan plan = CompatPlans.importBuildPlan(
                importer(root),
                ctx -> gradle,
                root.resolve("build.gradle.kts"),
                root.resolve("jk.toml"),
                root,
                root.resolve("tmp"),
                false,
                false,
                null,
                (lockDir, progress) -> {
                    raised.add(lockDir.toString());
                    return List.of();
                },
                (kind, text) -> notes.add(kind + ":" + text));
        Recorder recorder = new Recorder();
        plan.addListener(recorder);
        BuildPlanResult result = plan.run();

        assertThat(result.success()).isTrue();
        assertThat(plan.get(CompatPlans.EXIT)).contains(0);
        assertThat(recorder.started)
                .containsExactly(CompatPlans.READ, CompatPlans.WRITE, CompatPlans.CHECK_PINS, CompatPlans.REPORT);
        assertThat(plan.steps())
                .extracting(Task::label)
                .containsExactly("Evaluate with Gradle", "Write manifests", "Check pins", "Report");
        assertThat(recorder.labels)
                .contains(
                        CompatPlans.READ + "=resolved :core (2/2)",
                        CompatPlans.WRITE + "=core/jk.toml",
                        CompatPlans.CHECK_PINS + "=Gradle resolved every pin");
        assertThat(raised).as("Gradle's pins are the versions it resolved").isEmpty();
        assertThat(notes)
                .contains(
                        "note:Gradle 9.8.0 on JDK 25 · evaluating shop",
                        "wrote:" + root.resolve("jk.toml").toAbsolutePath(),
                        "wrote:" + root.resolve("core/jk.toml").toAbsolutePath());
    }

    @Test
    void a_refused_overwrite_ends_the_import_before_anything_is_written(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\"core\")\n");
        Files.writeString(root.resolve("build.gradle.kts"), "");
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve("core/jk.toml"), "name = \"prior\"\n");
        List<String> notes = Collections.synchronizedList(new ArrayList<>());

        BuildPlan plan = CompatPlans.importBuildPlan(
                importer(root),
                ctx -> GradleBuildImport.withModel((dir, plugins, progress) -> MODEL),
                root.resolve("build.gradle.kts"),
                root.resolve("jk.toml"),
                root,
                root.resolve("tmp"),
                false,
                false,
                null,
                ProjectImport.PinRaise.NONE,
                (kind, text) -> notes.add(kind + ":" + text));
        plan.run();

        assertThat(plan.get(CompatPlans.EXIT)).contains(Exit.CANT_CREATE);
        assertThat(plan.get(CompatPlans.ERROR).orElse("")).contains("already exists; nothing written");
        assertThat(notes).noneMatch(n -> n.startsWith("wrote:"));
        assertThat(root.resolve("jk.toml")).doesNotExist();
    }

    private static PomImporter importer(Path root) throws Exception {
        Path repo = Files.createDirectories(root.resolve("no-repo"));
        Cas cas = new Cas(root.resolve("cache"));
        return new PomImporter(RepoGroup.of(new MavenRepo("fixture", repo.toUri(), new Http(), cas)), cas);
    }

    /** The plan's step starts and labels, as a console listener sees them. */
    private static final class Recorder implements BuildPlanListener {
        final List<String> started = Collections.synchronizedList(new ArrayList<>());
        final List<String> labels = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void stepStart(String step, @Nullable String group, int ticks) {
            started.add(step);
        }

        @Override
        public void label(String step, String label) {
            labels.add(step + "=" + label);
        }
    }
}
