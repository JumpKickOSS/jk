// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The scaladoc argfile: the output, the project, a site root that is never the module's, the classpath, the classes. */
class ScaladocToolTest {

    @Test
    void the_arguments_name_the_output_the_project_the_classpath_and_the_classes() {
        Path out = Path.of("/ws/lib/target/apidocs").toAbsolutePath();
        Path classes = Path.of("/ws/lib/target/classes").toAbsolutePath();
        Path stdlib = Path.of("/cas/scala-library.jar").toAbsolutePath();
        List<String> args = ScaladocTool.arguments(out, "lib", "1.0.0", classes, List.of(stdlib));

        assertThat(args.subList(0, 6))
                .containsExactly("-d", q(out), "-project", "\"lib\"", "-project-version", "\"1.0.0\"");
        assertThat(args.get(6)).isEqualTo("-siteroot");
        assertThat(args.get(7)).isEqualTo(q(out.resolve("no-site")));
        assertThat(args.subList(8, 10)).containsExactly("-classpath", q(stdlib));
        assertThat(args.getLast()).isEqualTo(q(classes));
    }

    @Test
    void warn_lines_are_the_warnings() {
        assertThat(ScaladocTool.warnings("[info] generating\n[warn] No DRI found for link\nok\n"))
                .containsExactly("No DRI found for link");
    }

    private static String q(Path p) {
        return "\"" + p.toString().replace("\\", "\\\\") + "\"";
    }
}
