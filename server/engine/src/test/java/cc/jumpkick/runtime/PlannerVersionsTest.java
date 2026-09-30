// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ReleaseSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The suite's classpath reads the versioned classes the way a JDK reads a multi-release jar, and a
 * release set with a module descriptor patches the module the main classes form.
 */
class PlannerVersionsTest {

    @TempDir
    Path dir;

    private JkBuild module() throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "lib"
                version = "1.0"
                java = 17

                [multi-release]
                11 = "src/main/java11"
                21 = "src/main/java21"
                26 = "src/main/java26"
                """);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    @Test
    void the_releases_the_test_jdk_reads_go_ahead_of_main_highest_first(@TempDir Path other) throws Exception {
        JkBuild project = module();
        BuildLayout layout = BuildLayout.of(dir, project);
        Path main = layout.classesDir();
        Path dep = other.resolve("dep.jar");

        List<Path> stamped = PlannerVersions.withOwnVersions(project, layout, List.of(dep));
        assertThat(stamped)
                .as("every declared release is a stamp input, whatever JDK runs the suite")
                .containsExactly(
                        dep,
                        layout.versionedClassesDir(26),
                        layout.versionedClassesDir(21),
                        layout.versionedClassesDir(11));

        assertThat(PlannerVersions.launchClasspath(project, layout, 25, main, stamped))
                .containsExactly(layout.versionedClassesDir(21), layout.versionedClassesDir(11), main, dep);
    }

    @Test
    void an_entry_compiles_at_its_release_raised_to_the_module_s() {
        assertThat(PlannerVersions.compileRelease(new ReleaseSources(11, List.of("a")), 17))
                .isEqualTo(17);
        assertThat(PlannerVersions.compileRelease(new ReleaseSources(21, List.of("a")), 17))
                .isEqualTo(21);
    }

    @Test
    void a_versioned_descriptor_patches_the_main_classes_into_its_module() throws Exception {
        Path root = Files.createDirectories(dir.resolve("src/main/java11"));
        Path descriptor = Files.writeString(root.resolve("module-info.java"), """
                /* the versioned descriptor */
                module org.example.lib {
                    exports org.example;
                }
                """);
        Path source = Files.writeString(root.resolve("Only11.java"), "class Only11 {}\n");
        Path main = dir.resolve("classes");

        CompileRequest modular = PlannerVersions.versionsCompileRequest(
                List.of(source, descriptor),
                List.of(main),
                List.of(),
                dir.resolve("out"),
                17,
                List.of(),
                JavacConfig.EMPTY,
                dir,
                main);
        assertThat(modular.extraOptions())
                .containsSequence("--patch-module", "org.example.lib=" + main.toAbsolutePath());

        CompileRequest plain = PlannerVersions.versionsCompileRequest(
                List.of(source),
                List.of(main),
                List.of(),
                dir.resolve("out"),
                17,
                List.of(),
                JavacConfig.EMPTY,
                dir,
                main);
        assertThat(plain.extraOptions()).doesNotContain("--patch-module");
    }
}
