// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Editing a file back to a state jk has already built, then forward again, used to leave a
 * dependent module holding the previous run's artifact while the build reported
 * {@code all modules up to date}. Every step was in the action cache, so nothing was scheduled;
 * nothing was missing, so nothing was restored; and the jar on disk was the earlier one.
 *
 * <p>A revert, a rebase, a stash pop and a branch switch all produce that shape, which is why this
 * walks the cycle rather than making a single edit.
 */
@Tag("integration")
class IncrementalCycleTest {

    private static final String LIB_ONE = """
            package ex;
            public class Lib {
                public static String alpha() { return "alpha"; }
            }
            """;
    private static final String LIB_TWO = """
            package ex;
            public class Lib {
                public static String alpha() { return "alpha"; }
                public static String betamethod() { return "beta"; }
            }
            """;
    private static final String APP_ONE = """
            package ex;
            public class App {
                public static String use() { return Lib.alpha(); }
            }
            """;
    private static final String APP_TWO = """
            package ex;
            public class App {
                public static String use() { return Lib.alpha() + Lib.betamethod(); }
            }
            """;

    @Test
    void a_dependent_module_never_keeps_an_artifact_from_other_sources(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "root"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = Files.createDirectories(dir.resolve("lib/src/main/java/ex"));
        Path app = Files.createDirectories(dir.resolve("app/src/main/java/ex"));
        Files.writeString(dir.resolve("lib/jk.toml"), "name = \"lib\"\n");
        Files.writeString(dir.resolve("app/jk.toml"), """
                name = "app"

                [dependencies]
                lib.workspace = true
                """);

        state(lib, app, LIB_ONE, APP_ONE);
        build(dir);
        state(lib, app, LIB_TWO, APP_TWO);
        build(dir);
        assertThat(appCallsBeta(dir))
                .as("the two-method build is what it says it is")
                .isTrue();

        // Back to a state the action cache has already seen, then forward again. Both keys are
        // hits; neither may be answered with the artifacts the other left behind.
        state(lib, app, LIB_ONE, APP_ONE);
        build(dir);
        assertThat(appCallsBeta(dir))
                .as("back at one method: the jar must not still call the other")
                .isFalse();

        state(lib, app, LIB_TWO, APP_TWO);
        build(dir);
        assertThat(appCallsBeta(dir))
                .as("forward again: a cached action key is not a current artifact")
                .isTrue();
    }

    private static void state(Path lib, Path app, String libSrc, String appSrc) throws IOException {
        Files.writeString(lib.resolve("Lib.java"), libSrc);
        Files.writeString(app.resolve("App.java"), appSrc);
    }

    private static void build(Path dir) {
        assertThat(run("build", "-C", dir.toString(), "--cache-dir", SharedTestCache.arg()))
                .isEqualTo(0);
    }

    /**
     * Whether {@code app}'s packaged class references {@code Lib.betamethod} — read out of the jar,
     * never out of the source tree, because the tree is what the build was told and the jar is what
     * it actually produced. The callee's name reaches the constant pool as a UTF-8 entry, so a
     * distinctive name makes a substring scan an honest test without a bytecode parser.
     */
    private static boolean appCallsBeta(Path dir) throws IOException {
        // Found rather than spelled: the jars dir sits under the module's own target tree, and a
        // test that hardcodes that shape fails for the layout rather than for the defect.
        Optional<Path> jar;
        try (var walk = Files.walk(dir.resolve("target"))) {
            jar = walk.filter(p -> p.getFileName().toString().equals("app-1.0.0.jar"))
                    .findFirst();
        }
        assertThat(jar).as("app's packaged jar under target/").isPresent();
        try (JarFile jf = new JarFile(jar.orElseThrow().toFile())) {
            var entry = jf.getJarEntry("ex/App.class");
            assertThat(entry).isNotNull();
            byte[] bytes;
            try (var in = jf.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            return new String(bytes, StandardCharsets.ISO_8859_1).contains("betamethod");
        }
    }
}
