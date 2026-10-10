// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin step's {@code contributesTestClasspath} path joins the test classpath as itself when it
 * is a directory, as one entry per non-blank line when it is a file, and not at all when the step
 * did not write it.
 */
class PluginTestClasspathTest {

    private static TaskDecl decl(String name, List<String> testClasspath) {
        return new TaskDecl(
                name,
                List.of(),
                List.of(),
                List.of("out"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                testClasspath,
                List.of(),
                null,
                null,
                false,
                null);
    }

    @Test
    void a_directory_is_one_entry_and_a_file_lists_its_entries(@TempDir Path tmp) throws Exception {
        BuildLayout layout =
                BuildLayout.of(tmp, JkBuildParser.parse("group = \"g\"\nname = \"app\"\nversion = \"1\"\n"));
        Path dir = Files.createDirectories(
                PluginBuild.taskScratch(layout, "config").resolve("out/cp"));
        Path listing = PluginBuild.taskScratch(layout, "env").resolve("out/classpath");
        Files.createDirectories(listing.getParent());
        Path a = tmp.resolve("ide/plugins/x/lib/a.jar");
        Path b = tmp.resolve("ide/plugins/x/lib/modules/b.jar");
        Files.writeString(listing, a + "\n\n  " + b + "  \n");

        List<Path> classpath = PlannerKsp.pluginTestClasspath(
                layout,
                new PluginDeclarations(
                        List.of(
                                decl("config", List.of("out/cp")),
                                decl("env", List.of("out/classpath")),
                                decl("silent", List.of("out/never-written"))),
                        null,
                        List.of()));

        assertThat(classpath).containsExactly(dir, a, b);
    }
}
