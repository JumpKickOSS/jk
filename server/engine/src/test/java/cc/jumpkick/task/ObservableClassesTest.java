// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a test JVM can observe of a workspace jar or classes tree: a comment that moves only the
 * classes' line tables keeps the token, a body, constant or resource edit moves it.
 */
class ObservableClassesTest {

    private static final String LIB = """
            package p;

            public final class Lib {
                private Lib() {}

                public static String text(String what) {
                    String trimmed = what.strip();
                    return "[" + trimmed + "]";
                }
            }
            """;

    private static final String COMMENTED = LIB.replace("""
                    String trimmed = what.strip();""", """
                    // Strip first: callers pass padded names.

                    String trimmed = what.strip();""");

    private static final String BODY = LIB.replace("\"[\"", "\"(\"");

    @Test
    void a_comment_that_moves_line_numbers_keeps_the_class_and_a_body_edit_moves_it(@TempDir Path dir)
            throws Exception {
        byte[] lib = compile(dir.resolve("a"), LIB);
        byte[] commented = compile(dir.resolve("b"), COMMENTED);
        byte[] body = compile(dir.resolve("c"), BODY);

        assertThat(commented).as("javac's bytes move with the line table").isNotEqualTo(lib);
        assertThat(ObservableClasses.classDigest(commented)).isEqualTo(ObservableClasses.classDigest(lib));
        assertThat(ObservableClasses.classDigest(body)).isNotEqualTo(ObservableClasses.classDigest(lib));
    }

    @Test
    void jars_and_trees_key_on_what_their_classes_do_and_on_every_resource(@TempDir Path dir) throws Exception {
        byte[] lib = compile(dir.resolve("a"), LIB);
        byte[] commented = compile(dir.resolve("b"), COMMENTED);
        byte[] body = compile(dir.resolve("c"), BODY);
        Path jarLib = jar(dir.resolve("lib.jar"), lib, "one");
        Path jarCommented = jar(dir.resolve("commented.jar"), commented, "one");
        Path jarBody = jar(dir.resolve("body.jar"), body, "one");
        Path jarResource = jar(dir.resolve("resource.jar"), lib, "two");
        Path treeLib = tree(dir.resolve("lib-classes"), lib);
        Path treeCommented = tree(dir.resolve("commented-classes"), commented);

        withCache(dir.resolve("cache"), () -> {
            String token = observable(jarLib);
            assertThat(token).startsWith(ObservableClasses.PREFIX);
            assertThat(ClasspathFingerprint.entry(jarCommented)).isNotEqualTo(ClasspathFingerprint.entry(jarLib));
            assertThat(observable(jarCommented)).isEqualTo(token);
            assertThat(observable(jarBody)).isNotEqualTo(token);
            assertThat(observable(jarResource)).as("a resource is read whole").isNotEqualTo(token);
            assertThat(observable(treeCommented)).isEqualTo(observable(treeLib));
            assertThat(observable(treeLib)).startsWith(ObservableClasses.PREFIX);
            return null;
        });
    }

    @Test
    void a_lone_file_a_missing_entry_and_a_jar_that_is_no_archive_key_by_content(@TempDir Path dir) throws Exception {
        Path notes = Files.writeString(dir.resolve("notes.txt"), "x");
        Path notAJar = Files.writeString(dir.resolve("dep.jar"), "DEP-V1");
        withCache(dir.resolve("cache"), () -> {
            assertThat(observable(notes)).isEqualTo(ClasspathFingerprint.entry(notes));
            assertThat(observable(dir.resolve("gone.jar"))).startsWith("missing:");
            String v1 = observable(notAJar);
            assertThat(v1).isEqualTo(ClasspathFingerprint.entry(notAJar));
            Files.writeString(notAJar, "DEP-V2");
            assertThat(observable(notAJar)).isNotEqualTo(v1);
            return null;
        });
    }

    private static String observable(Path entry) throws IOException {
        return ClasspathFingerprint.ON_DISK.observable(entry);
    }

    private static byte[] compile(Path dir, String source) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/p")).resolve("Lib.java");
        Files.writeString(src, source);
        Path out = Files.createDirectories(dir.resolve("out"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int exit = javac.run(null, null, null, "-d", out.toString(), src.toString());
        assertThat(exit).as("javac").isZero();
        return Files.readAllBytes(out.resolve("p/Lib.class"));
    }

    private static Path jar(Path file, byte[] cls, String resource) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("p/Lib.class"));
            out.write(cls);
            out.closeEntry();
            out.putNextEntry(new ZipEntry("p/lib.txt"));
            out.write(resource.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    private static Path tree(Path dir, byte[] cls) throws IOException {
        Files.write(Files.createDirectories(dir.resolve("p")).resolve("Lib.class"), cls);
        Files.writeString(dir.resolve("p/lib.txt"), "one");
        return dir;
    }

    private static void withCache(Path cache, Callable<Void> body) {
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
            AbiMemo.reset();
            FileHashMemo.reset();
            try {
                body.call();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }
}
