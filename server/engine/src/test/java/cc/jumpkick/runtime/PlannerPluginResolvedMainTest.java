// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin step's entry point: the declared main, else the one main the compiled classes carry,
 * else none. A Quarkus app has no {@code main} of its own (Quarkus generates its entry point), so
 * finding none is an answer, not an error.
 */
class PlannerPluginResolvedMainTest {

    private static final String QUARKUS = """
            name = "svc"
            group = "com.example"
            version = "1.0.0"
            java = 25

            [quarkus]
            version = "3.40.1"
            """;

    /** A class with a launchable {@code main}, copied into a classes tree by its bytes. */
    static final class Launcher {
        public static void main(String[] args) {}
    }

    /** A class without one. */
    static final class Resource {
        String hello() {
            return "hello";
        }
    }

    @Test
    void classes_without_a_main_resolve_to_none(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("target/classes");
        copyClass(Resource.class, classes);
        assertThat(PlannerPlugin.resolvedMain(JkBuildParser.parse(QUARKUS), dir, classes))
                .isNull();
    }

    @Test
    void the_one_compiled_main_is_found_when_none_is_declared(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("target/classes");
        copyClass(Launcher.class, classes);
        copyClass(Resource.class, classes);
        assertThat(PlannerPlugin.resolvedMain(JkBuildParser.parse(QUARKUS), dir, classes))
                .isEqualTo(Launcher.class.getName());
    }

    @Test
    void a_declared_main_wins(@TempDir Path dir) throws IOException {
        JkBuild build = JkBuildParser.parse(QUARKUS + """

                [application]
                main = "com.example.Declared"
                """);
        assertThat(PlannerPlugin.resolvedMain(build, dir, dir.resolve("target/classes")))
                .isEqualTo("com.example.Declared");
    }

    private static void copyClass(Class<?> type, Path classes) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        Path out = classes.resolve(entry);
        Files.createDirectories(Objects.requireNonNull(out.getParent()));
        try (InputStream in = Objects.requireNonNull(type.getClassLoader().getResourceAsStream(entry))) {
            Files.copy(in, out);
        }
    }
}
