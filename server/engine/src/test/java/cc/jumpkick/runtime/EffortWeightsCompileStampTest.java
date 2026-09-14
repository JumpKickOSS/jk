// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.FreshnessStamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plan-time weights read each compile's freshness stamp from {@link
 * BuildLayout#compileStampDir}, the directory the write-stamp steps write into. A Kotlin module
 * whose stamp is current is weighed as a stamp skip, not as a compile about to run, so the
 * progress bar and the ETA reserve nothing for it and the main jar is not expected to change.
 */
class EffortWeightsCompileStampTest {

    @Test
    void a_kotlin_stamp_written_where_write_stamp_kotlin_writes_it_weighs_the_module_fresh(@TempDir Path tmp)
            throws Exception {
        Path dir = kotlinProject(tmp);
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(dir, build);
        List<Path> sources = PlannerCompile.mainKotlinSources(build, dir, ModuleLayout.isCompact(dir));
        assertThat(sources).isNotEmpty();
        // The stamp predicate treats a source written in the stamp's millisecond as changed; pin
        // the source into the past instead of racing the clock.
        agedByAnHour(sources.get(0));
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar");

        assertThat(EffortWeights.mainJarWillChange(dir))
                .as("no stamp anywhere: the compile is expected to run")
                .isTrue();

        // Exactly what write-stamp-kotlin does: the stamp lands in the merged classes tree.
        Files.createDirectories(layout.classesDir());
        FreshnessStamp.write(
                layout.classesDir(),
                BuildStamps.KOTLIN,
                TaskNames.COMPILE_KOTLIN,
                "",
                sources,
                FreshnessStamp.ClasspathTokens.of(List.of()),
                25,
                "");

        assertThat(layout.compileStampDir())
                .as("the reader's directory is the writer's")
                .isEqualTo(layout.classesDir());
        assertThat(EffortWeights.mainJarWillChange(dir))
                .as("the weight finds the stamp where the build wrote it")
                .isFalse();
    }

    @Test
    void kotlincs_own_output_dir_never_carries_the_stamp(@TempDir Path tmp) throws Exception {
        Path dir = kotlinProject(tmp);
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(dir, build);
        assertThat(layout.compileStampDir()).isNotEqualTo(layout.kotlinClassesDir());
        assertThat(layout.compileStampDir()).isNotEqualTo(layout.groovyClassesDir());
    }

    private static void agedByAnHour(Path file) throws Exception {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
    }

    private static Path kotlinProject(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(dir.resolve("jk.toml"), """
            group   = "com.example"
            name    = "proj"
            version = "1.0.0"
            kotlin  = "2.4.10"
            """);
        Path src = Files.createDirectories(dir.resolve("src/app"));
        Files.writeString(src.resolve("A.kt"), "package app\ninternal object A\n");
        return dir;
    }
}
