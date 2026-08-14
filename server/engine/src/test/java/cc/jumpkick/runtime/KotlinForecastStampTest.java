// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.FreshnessStamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk explain} must read the Kotlin freshness stamp from the directory the build
 * writes it to.
 *
 * <p>{@code BuildPlanner} writes {@code.kstamp} beside the merged classes ({@link
 * BuildLayout#classesDir}); the forecast read {@link BuildLayout#kotlinClassesDir} — kotlinc's
 * incremental workspace — where no stamp is ever written. It therefore never found one, and every
 * Kotlin module forecast a full compile regardless of how cached the build actually was.
 *
 * <p>This pins the contract between the two directly: a stamp written where the build writes it is
 * seen by the predicate the forecast uses.
 */
class KotlinForecastStampTest {

    @Test
    void the_build_writes_the_kotlin_stamp_where_the_forecast_reads_it(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(dir, build);
        List<Path> sources = List.of(dir.resolve("src/app/A.kt"));
        // newerThan uses >= on purpose, so a source written in the same millisecond as the stamp
        // counts as changed. Pin the source into the past rather than racing the clock.
        agedByAnHour(sources.get(0));

        // Nothing compiled yet: no stamp anywhere, so the forecast must predict work.
        assertThat(FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.KOTLIN_STAMP, sources))
                .isFalse();

        // Exactly what compile-kotlin's stamp step does (BuildPlanner: MAIN_CLASSES).
        Files.createDirectories(layout.classesDir());
        FreshnessStamp.write(
                layout.classesDir(), FreshnessStamp.KOTLIN_STAMP, "compile-kotlin", "", sources, List.of(), 25);

        assertThat(FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.KOTLIN_STAMP, sources))
                .isTrue();

        // The directory the forecast used to read holds no stamp — reading it can only ever
        // return "not fresh", which is precisely the bug.
        assertThat(FreshnessStamp.looksFresh(layout.kotlinClassesDir(), FreshnessStamp.KOTLIN_STAMP, sources))
                .isFalse();
    }

    @Test
    void touching_a_source_invalidates_the_stamp(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(dir, build);
        Path source = dir.resolve("src/app/A.kt");
        List<Path> sources = List.of(source);
        agedByAnHour(source);

        Files.createDirectories(layout.classesDir());
        FreshnessStamp.write(
                layout.classesDir(), FreshnessStamp.KOTLIN_STAMP, "compile-kotlin", "", sources, List.of(), 25);
        assertThat(FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.KOTLIN_STAMP, sources))
                .isTrue();

        Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis() + 3_600_000));
        assertThat(FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.KOTLIN_STAMP, sources))
                .isFalse();
    }

    /** Push a file's mtime an hour back so stamp comparisons don't race the wall clock. */
    private static void agedByAnHour(Path file) throws Exception {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
    }

    private static Path project(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "proj"
                version = "1.0.0"
                kotlin  = "2.4.10"
                layout  = "simple"
                """);
        Path src = Files.createDirectories(dir.resolve("src/app"));
        Files.writeString(src.resolve("A.kt"), "package app\ninternal object A\n");
        return dir;
    }
}
