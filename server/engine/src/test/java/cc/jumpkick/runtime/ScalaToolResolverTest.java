// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.resolver.VersionSelectors;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalaToolResolverTest {

    @Test
    void validated_closure_hits_only_when_marker_and_jar_count_agree(@TempDir Path dir) throws IOException {
        Path libDir = Files.createDirectories(dir.resolve("lib"));
        Path cacheFile = dir.resolve("closure.shas");
        Files.writeString(libDir.resolve("scala3-compiler_3-3.8.4.jar"), "c");
        Files.writeString(libDir.resolve("scala-library-3.8.4.jar"), "l");
        ScalaToolResolver.writeCachedClosure(cacheFile, List.of("sha-c", "sha-l"));

        List<Path> jars = ScalaToolResolver.readValidatedClosure(libDir, cacheFile);
        assertThat(jars).hasSize(2);
    }

    @Test
    void partial_lib_dir_without_completion_marker_is_a_miss(@TempDir Path dir) throws IOException {
        // JK-2290: a resolve that copied some jars then died leaves jars but no closure.shas — it
        // must NOT be trusted, or scalac launches against an incomplete closure.
        Path libDir = Files.createDirectories(dir.resolve("lib"));
        Path cacheFile = dir.resolve("closure.shas");
        Files.writeString(libDir.resolve("scala3-compiler_3-3.8.4.jar"), "c");
        assertThat(ScalaToolResolver.readValidatedClosure(libDir, cacheFile)).isNull();

        // Marker present but jar count disagrees (an extra/orphan or missing jar) → also a miss.
        ScalaToolResolver.writeCachedClosure(cacheFile, List.of("sha-c", "sha-l"));
        assertThat(ScalaToolResolver.readValidatedClosure(libDir, cacheFile)).isNull();
    }

    @Test
    void library_jars_prefer_scala_library_over_the_scala3_stub() {
        List<Path> cp = List.of(
                Path.of("scala3-compiler_3-3.8.4.jar"),
                Path.of("scala3-library_3-3.8.4.jar"),
                Path.of("scala-library-3.8.4.jar"));
        assertThat(ScalaToolResolver.libraryJars(cp))
                .extracting(p -> p.getFileName().toString())
                .containsExactly("scala-library-3.8.4.jar", "scala3-library_3-3.8.4.jar");
    }

    @Test
    void rejects_scala_2() {
        assertThatThrownBy(() -> ScalaToolResolver.requireSupportedVersion("2.13.16"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scala 3");
        ScalaToolResolver.requireSupportedVersion("3.8.4");
        ScalaToolResolver.requireSupportedVersion("3.0.0");
    }

    @Test
    void pick_scala_version_prefers_stable_highest() {
        VersionSet set = VersionSelectors.toVersionSet(VersionSelector.parseFloating("3"));
        assertThat(LockPipeline.pickVersion(set, List.of("3.8.2", "3.8.4", "3.9.0-RC6", "2.13.16")))
                .isEqualTo("3.8.4");
        assertThat(LockPipeline.pickVersion(set, List.of("3.8.4", "3.8.4"))).isEqualTo("3.8.4");
    }

    @Test
    void exact_scala_selector_does_not_need_the_catalog() {
        JkBuild build = JkBuild.of(Project.builder("com.example", "app", "1.0.0")
                .scala(VersionSelector.parse("=3.8.4"))
                .build());
        assertThat(LockPipeline.resolveScalaVersion(build, null)).isEqualTo("3.8.4");
        JkBuild javaOnly = JkBuild.of(
                Project.builder("com.example", "app", "1.0.0").java(25).build());
        assertThat(LockPipeline.resolveScalaVersion(javaOnly, null)).isNull();
    }
}
