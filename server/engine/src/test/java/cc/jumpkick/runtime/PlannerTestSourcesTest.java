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

/**
 * compile-test hands javac an explicit file list, so every selected Java source — the other suites'
 * roots and {@code [test] extra-src} as much as the primary root — must be on it, each once. A
 * source left off the list compiles to nothing and its suite silently runs nothing; a source on it
 * twice puts a duplicate into the run-tests stamp.
 */
class PlannerTestSourcesTest {

    @Test
    void namesEverySelectedSourceOnce(@TempDir Path module) throws Exception {
        Path unit = write(module, "src/test/java/app/WidgetTest.java");
        Path integration = write(module, "src/integration/java/app/WidgetIT.java");
        Path extra = write(module, "shared/Helper.java");
        Path scala = write(module, "src/test/scala/app/WidgetSpec.scala");
        JkBuild project = manifest(module, "[test]\nextra-src = [\"shared\", \"shared/Helper.java\"]\n");

        var sources = PlannerTest.TestSources.collect(
                project, module, false, List.of("test", "integration"), BuildLayout.of(module, project), null);

        assertThat(sources.javaTestSrc()).isEqualTo(module.resolve("src/test/java"));
        assertThat(sources.javaTest())
                .as("the extra-src root and the file named inside it are the same source once")
                .containsExactly(unit, integration, extra);
        assertThat(sources.javacSources()).containsExactly(unit, integration, extra, scala);
        assertThat(sources.all()).doesNotHaveDuplicates().contains(scala);
    }

    @Test
    void aSingleSuiteSelectionNamesThatSuiteAlone(@TempDir Path module) throws Exception {
        write(module, "src/test/java/app/WidgetTest.java");
        Path it = write(module, "src/integration/java/app/WidgetIT.java");

        JkBuild project = manifest(module, "");
        var sources = PlannerTest.TestSources.collect(
                project, module, false, List.of("integration"), BuildLayout.of(module, project), null);

        assertThat(sources.javaTestSrc()).isEqualTo(module.resolve("src/integration/java"));
        assertThat(sources.javacSources()).containsExactly(it);
    }

    private static JkBuild manifest(Path module, String tail) throws Exception {
        Files.writeString(module.resolve("jk.toml"), """
                group = "ex"
                name = "m"
                version = "1.0"
                jdk = 25
                """ + tail);
        return JkBuildParser.parse(module.resolve("jk.toml"));
    }

    private static Path write(Path module, String rel) throws Exception {
        Path f = module.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "package app;\nclass T {}\n");
        return f;
    }
}
