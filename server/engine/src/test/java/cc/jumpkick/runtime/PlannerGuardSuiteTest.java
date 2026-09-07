// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The guard suite is found by its directory alone, and compiles against main, the test classpath and the library. */
class PlannerGuardSuiteTest {

    @Test
    void a_src_guard_directory_declares_the_suite_with_no_manifest_edit(@TempDir Path module) throws Exception {
        Files.writeString(module.resolve("jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njdk = 25\n");
        assertThat(PlannerGuardSuite.declared(module, false)).isFalse();
        Path src = Files.createDirectories(module.resolve("src/guard/java/rules"));
        Files.writeString(src.resolve("HouseRules.java"), "package rules; class HouseRules {}\n");
        assertThat(PlannerGuardSuite.declared(module, false)).isTrue();
        assertThat(PlannerGuardSuite.sources(module, false)).singleElement().satisfies(p -> assertThat(p)
                .endsWith(Path.of("HouseRules.java")));
        assertThat(PlannerGuardSuite.forecastSources(module, false)).hasSize(1);
    }

    @Test
    void the_classpath_is_main_then_test_deps_then_own_fixtures_then_the_library(@TempDir Path module)
            throws Exception {
        Files.writeString(module.resolve("jk.toml"), """
                group = "t"
                name = "m"
                version = "0.0.1"
                jdk = 25

                [test]
                fixtures = true
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, project);
        Path junit = module.resolve("junit.jar");
        Path lib = module.resolve("jk-guards-junit.jar");
        List<Path> cp = PlannerGuardSuite.classpath(project, layout, List.of(junit, layout.classesDir()), lib);
        assertThat(cp).containsExactly(layout.classesDir(), junit, layout.testFixturesClassesDir(), lib);
        assertThat(layout.guardClassesDir())
                .isEqualTo(layout.moduleTargetDir().resolve("guard").resolve("classes"));
        // the request the forecast keys and the request the build runs are one body
        var req = PlannerGuardSuite.guardCompileRequest(
                List.of(), cp, List.of(), layout.guardClassesDir(), 25, List.of(), module);
        assertThat(req.outputDir()).isEqualTo(layout.guardClassesDir());
        assertThat(req.classpath()).isEqualTo(cp);
    }
}
