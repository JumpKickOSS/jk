// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.task.FreshnessStamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1298: the compile-main freshness-stamp inputs come from ONE recipe
 * ({@link BuildPipelines#mainStampClasspath}) shared by the live check, {@code write-stamp}, and
 * the forecast. Two hand-maintained copies drifted before: the forecast missed the mixed-language
 * classpath entries (mixed modules never forecast stamp-fresh) and write-stamp missed the
 * processor path (processor modules never checked stamp-fresh).
 */
class MainStampParityTest {

    private static BuildLayout layout(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "mixed"
                version = "1.0.0"
                jdk = 21
                java = 21
                """);
        return BuildLayout.of(dir, JkBuildParser.parse(dir.resolve("jk.toml")));
    }

    /** Backdate an input so same-millisecond creation never trips the {@code >=} mtime check. */
    private static Path aged(Path p) throws Exception {
        Files.setLastModifiedTime(
                p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        return p;
    }

    @Test
    void mixed_kotlin_stamp_written_by_the_recipe_is_fresh_under_the_recipe(@TempDir Path dir) throws Exception {
        BuildLayout layout = layout(dir);
        Path src = dir.resolve("src/main/java/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "public class A {}");
        aged(src);
        Path dep = dir.resolve("dep.jar");
        Files.writeString(dep, "jar");
        aged(dep);
        Path processor = dir.resolve("processor.jar");
        Files.writeString(processor, "proc");
        aged(processor);
        // A compiled mixed module has the sibling compiler's output dir.
        aged(Files.createDirectories(layout.kotlinClassesDir()));
        List<Path> sources = List.of(src);
        Path out = layout.classesDir();

        List<Path> written = BuildPipelines.mainStampClasspath(
                List.of(dep), List.of(processor), true, false, layout, null);
        FreshnessStamp.write(out, FreshnessStamp.JAVA_STAMP, "compile-main", "", sources, written, 21);

        // The forecast/check recompute through the same recipe → fresh.
        List<Path> recomputed = BuildPipelines.mainStampClasspath(
                List.of(dep), List.of(processor), true, false, layout, null);
        assertThat(FreshnessStamp.isFresh(out, FreshnessStamp.JAVA_STAMP, sources, recomputed, 21))
                .isTrue();

        // The pre-fix forecast recipe (base classpath + processors only, no kotlin classes dir)
        // hashes different inputs → never fresh. This is the JK-1296-class divergence the shared
        // recipe closes.
        List<Path> oldForecast = new ArrayList<>(List.of(dep));
        oldForecast.add(processor);
        assertThat(FreshnessStamp.isFresh(out, FreshnessStamp.JAVA_STAMP, sources, oldForecast, 21))
                .isFalse();
    }

    @Test
    void processor_path_busts_the_stamp(@TempDir Path dir) throws Exception {
        BuildLayout layout = layout(dir);
        Path src = dir.resolve("src/main/java/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "public class A {}");
        aged(src);
        Path dep = dir.resolve("dep.jar");
        Files.writeString(dep, "jar");
        aged(dep);
        Path processor = dir.resolve("processor.jar");
        Files.writeString(processor, "proc-v1");
        aged(processor);
        List<Path> sources = List.of(src);
        Path out = layout.classesDir();

        FreshnessStamp.write(
                out,
                FreshnessStamp.JAVA_STAMP,
                "compile-main",
                "",
                sources,
                BuildPipelines.mainStampClasspath(List.of(dep), List.of(processor), false, false, layout, null),
                21);

        assertThat(FreshnessStamp.isFresh(
                        out,
                        FreshnessStamp.JAVA_STAMP,
                        sources,
                        BuildPipelines.mainStampClasspath(
                                List.of(dep), List.of(processor), false, false, layout, null),
                        21))
                .isTrue();

        // A processor bump must invalidate — it is not on the compile classpath.
        Files.writeString(processor, "proc-v2-different-bytes");
        assertThat(FreshnessStamp.isFresh(
                        out,
                        FreshnessStamp.JAVA_STAMP,
                        sources,
                        BuildPipelines.mainStampClasspath(
                                List.of(dep), List.of(processor), false, false, layout, null),
                        21))
                .isFalse();
    }

    @Test
    void mixed_groovy_folds_classes_dir_and_jar(@TempDir Path dir) throws Exception {
        BuildLayout layout = layout(dir);
        Path dep = dir.resolve("dep.jar");
        Path groovyJar = dir.resolve("groovy.jar");

        List<Path> inputs =
                BuildPipelines.mainStampClasspath(List.of(dep), List.of(), false, true, layout, groovyJar);

        assertThat(inputs).containsExactly(dep, layout.groovyClassesDir(), groovyJar);
    }
}
