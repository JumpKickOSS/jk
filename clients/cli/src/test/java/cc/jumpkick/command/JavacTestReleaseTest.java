// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [javac.test] release}: a {@code java = 17} library whose suite uses a JDK 21 API. The
 * test compile alone is lifted to 21 — its classes are 65.0 — while the main classes stay at 61.0;
 * without the key the same suite is held to 17 and fails to compile, and a release below the
 * module's level is refused by name.
 */
@Tag("integration")
class JavacTestReleaseTest {

    /** Class-file major of a {@code java = 17} compile and of a {@code --release 21} compile. */
    private static final int JAVA_17 = 61;

    private static final int JAVA_21 = 65;

    @Test
    void the_suite_compiles_at_21_while_main_stays_at_17(@TempDir Path dir) throws Exception {
        project(dir, "[javac.test]\nrelease = 21\n");

        Run run = test(dir);
        assertThat(run.exit()).as("jk test\n%s", run.output()).isEqualTo(0);
        assertThat(major(classFile(dir, "Lib.class"))).isEqualTo(JAVA_17);
        assertThat(major(classFile(dir, "LibTest.class"))).isEqualTo(JAVA_21);
    }

    @Test
    void without_the_key_the_suite_is_held_to_the_module_s_level(@TempDir Path dir) throws Exception {
        project(dir, "");

        Run run = test(dir);
        assertThat(run.exit())
                .as("List.reversed() is a JDK 21 API; a --release 17 compile of the suite must fail\n%s", run.output())
                .isNotEqualTo(0);
        assertThat(run.output()).as("the failure is the test compile's").contains("reversed");
    }

    @Test
    void a_release_below_the_module_s_level_is_refused_by_name(@TempDir Path dir) throws Exception {
        project(dir, "[javac.test]\nrelease = 11\n");

        Run run = test(dir);
        assertThat(run.exit()).isNotEqualTo(0);
        assertThat(run.output()).contains("[javac.test] release = 11 is below the module's java = 17");
    }

    private record Run(int exit, String output) {}

    /** {@code jk test} on the fixture, both streams kept: a wedge lands on stdout, a refusal on stderr. */
    private static Run test(Path dir) {
        int[] exit = new int[1];
        Capture.Streams streams =
                Capture.both(() -> exit[0] = run("test", "-C", dir.toString(), "--cache-dir", SharedTestCache.arg()));
        return new Run(exit[0], "stdout:\n" + streams.out() + "\nstderr:\n" + streams.err());
    }

    /**
     * A JDK 17 library whose one test uses a JDK 21 API. {@code jdk = "25"} names the toolchain that
     * compiles both releases so no JDK is fetched; the library's level is the {@code java} key. JUnit
     * resolves through the tier's shared cache, as every compilation test's closure does.
     */
    private static void project(Path dir, String javacTest) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("jk.toml"),
                "group = \"com.example\"\nname = \"lib\"\nversion = \"0.1.0\"\njdk = \"25\"\njava = 17\n\n"
                        + "[test-dependencies]\njunit-jupiter = \"latest\"\n\n"
                        + javacTest);
        Path main = dir.resolve("src/main/java/example/Lib.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, """
                package example;

                public final class Lib {
                    private Lib() {}

                    public static String greet() {
                        return "hi";
                    }
                }
                """);
        Path test = dir.resolve("src/test/java/example/LibTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
                package example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import java.util.List;
                import org.junit.jupiter.api.Test;

                class LibTest {
                    /** List.reversed() exists from JDK 21 on: this line is what the release lifts. */
                    @Test
                    void greets_and_reverses() {
                        assertEquals("hi", Lib.greet());
                        assertEquals(List.of(3, 2, 1), List.of(1, 2, 3).reversed());
                    }
                }
                """);
        assertThat(run("lock", "-C", dir.toString(), "--cache-dir", SharedTestCache.arg()))
                .as("the fixture's lock resolves JUnit")
                .isEqualTo(0);
    }

    private static Path classFile(Path dir, String name) throws IOException {
        try (Stream<Path> walk = Files.walk(dir.resolve("target"))) {
            return walk.filter(p -> p.getFileName().toString().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + name + " under target/"));
        }
    }

    /** The class-file major version: bytes 6–7 after the magic and the minor. */
    private static int major(Path classFile) throws IOException {
        try (InputStream in = Files.newInputStream(classFile);
                DataInputStream data = new DataInputStream(in)) {
            assertThat(data.readInt()).isEqualTo(0xCAFEBABE);
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }
}
