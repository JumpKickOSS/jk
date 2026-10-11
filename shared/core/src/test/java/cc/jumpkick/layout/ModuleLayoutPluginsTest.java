// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleLayoutPluginsTest {

    @Test
    void declared_roots_resolve_extra_src_test_extra_src_and_fixtures(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25

                [build]
                extra-src = ["grails-app/services", "missing"]

                [test]
                extra-src = ["shared-tests/java", "one/Parser.java"]
                fixtures = "fixtures/java"
                """);
        Files.createDirectories(tmp.resolve("grails-app/services"));
        Files.createDirectories(tmp.resolve("shared-tests/java"));
        Files.createDirectories(tmp.resolve("fixtures/java"));
        Files.writeString(Files.createDirectories(tmp.resolve("one")).resolve("Parser.java"), "class Parser {}");
        JkBuild build = JkBuildParser.parse(tmp.resolve("jk.toml"));

        assertThat(ModuleLayoutPlugins.declaredRoots(tmp, build, false))
                .containsExactly(
                        tmp.resolve("grails-app/services"),
                        tmp.resolve("shared-tests/java"),
                        tmp.resolve("one/Parser.java"),
                        tmp.resolve("fixtures/java"));
        assertThat(ModuleLayoutPlugins.declaredRoots(tmp, build, true))
                .as("test-tier roots are left out of a skip-tests fingerprint")
                .containsExactly(tmp.resolve("grails-app/services"));
    }
}
