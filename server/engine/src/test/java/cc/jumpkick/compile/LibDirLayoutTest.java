// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.LibDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lib-dir layout ships the runtime closure under coordinate names, less what {@code
 * lib-exclude} names, and its jar's {@code Class-Path} names exactly the directory it wrote, so
 * {@code java -jar} runs it from wherever the pair is copied.
 */
class LibDirLayoutTest {

    private static Lockfile.Artifact row(String key, String version) {
        return new Lockfile.Artifact(key, version, "central", null, null, List.of(), List.of(), null, null, null);
    }

    @Test
    void locked_jars_take_coordinate_names_and_siblings_their_own() throws Exception {
        Path netty = Path.of("/store/aa.jar");
        Path natives = Path.of("/store/bb.jar");
        Path snap = Path.of("/store/cc.jar");
        Path sibling = Path.of("/ws/core/target/core-0.1.0.jar");
        Map<Path, Lockfile.Artifact> rows = Map.of(
                netty, row("io.netty:netty-common:jar:", "4.2.0"),
                natives, row("io.netty:netty-transport-native-epoll:jar:linux-x86_64", "4.2.0"),
                snap, row("com.acme:widget:jar:", "1.0-SNAPSHOT"));

        List<LibDirLayout.Lib> libs =
                LibDirLayout.libs(List.of(netty, natives, sibling, snap), rows, LibDir.defaults());

        assertThat(libs)
                .extracting(LibDirLayout.Lib::name)
                .containsExactly(
                        "netty-common-4.2.0.jar",
                        "netty-transport-native-epoll-4.2.0-linux-x86_64.jar",
                        "core-0.1.0.jar",
                        "widget-1.0-SNAPSHOT.jar");
        assertThat(libs)
                .extracting(LibDirLayout.Lib::kind)
                .containsExactly(
                        LibDirLayout.Kind.RELEASE,
                        LibDirLayout.Kind.RELEASE,
                        LibDirLayout.Kind.SIBLING,
                        LibDirLayout.Kind.SNAPSHOT);
    }

    @Test
    void stable_names_drop_the_version_and_exclusions_drop_the_jar() throws Exception {
        Path okio = Path.of("/store/okio.jar");
        Path json = Path.of("/store/json.jar");
        Map<Path, Lockfile.Artifact> rows = Map.of(
                okio, row("com.squareup.okio:okio:jar:", "3.9.0"),
                json, row("jakarta.json:jakarta.json-api:jar:", "2.1.3"));

        List<LibDirLayout.Lib> libs =
                LibDirLayout.libs(List.of(okio, json), rows, new LibDir("libs", false, List.of("okio")));

        assertThat(libs).extracting(LibDirLayout.Lib::name).containsExactly("jakarta.json-api.jar");
        assertThat(LibDirLayout.classPath("libs", libs)).containsExactly("libs/jakarta.json-api.jar");
    }

    @Test
    void one_artifact_name_under_two_groups_is_qualified_by_group() throws Exception {
        Path a = Path.of("/store/a.jar");
        Path b = Path.of("/store/b.jar");
        Map<Path, Lockfile.Artifact> rows =
                Map.of(a, row("org.one:util:jar:", "1.0"), b, row("org.two:util:jar:", "2.0"));

        assertThat(LibDirLayout.libs(List.of(a, b), rows, new LibDir("libs", false, List.of())))
                .extracting(LibDirLayout.Lib::name)
                .containsExactly("org.one-util.jar", "org.two-util.jar");
    }

    @Test
    void two_siblings_sharing_a_file_name_fail_rather_than_drop_a_jar() {
        Path a = Path.of("/ws/x/target/util-1.0.jar");
        Path b = Path.of("/ws/y/target/util-1.0.jar");

        assertThatThrownBy(() -> LibDirLayout.libs(List.of(a, b), Map.of(), LibDir.defaults()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("util-1.0.jar");
    }

    @Test
    void the_written_layout_runs_with_java_jar_and_a_rewrite_prunes_dropped_jars(@TempDir Path tmp) throws Exception {
        Path dep = compileJar(tmp.resolve("dep"), "dep.Greeting", """
                package dep;
                public class Greeting { public static String text() { return "hello from libs"; } }
                """, null);
        Path thin = compileJar(tmp.resolve("app"), "app.Main", """
                package app;
                public class Main { public static void main(String[] a) { System.out.println(dep.Greeting.text()); } }
                """, dep);
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path libDir = target.resolve("libs");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("stale-0.9.jar"), "left by an earlier layout");
        Files.writeString(libDir.resolve("README.txt"), "not a jar; not ours to prune");
        List<LibDirLayout.Lib> libs = List.of(new LibDirLayout.Lib(dep, "greeting-1.0.jar", LibDirLayout.Kind.RELEASE));

        List<Path> written = LibDirLayout.write(thin, target.resolve("app.jar"), libDir, "libs", libs, "app.Main");

        assertThat(written).containsExactly(target.resolve("app.jar"), libDir.resolve("greeting-1.0.jar"));
        assertThat(libDir.resolve("stale-0.9.jar")).doesNotExist();
        assertThat(libDir.resolve("README.txt")).exists();
        try (JarFile jar = new JarFile(target.resolve("app.jar").toFile())) {
            Attributes main = jar.getManifest().getMainAttributes();
            assertThat(main.getValue(Attributes.Name.CLASS_PATH)).isEqualTo("libs/greeting-1.0.jar");
            assertThat(main.getValue(Attributes.Name.MAIN_CLASS)).isEqualTo("app.Main");
        }
        // Copied elsewhere as a pair, the way a Dockerfile copies it, it still runs.
        Path moved = Files.createDirectories(tmp.resolve("moved/libs"));
        Files.copy(libDir.resolve("greeting-1.0.jar"), moved.resolve("greeting-1.0.jar"));
        Files.copy(target.resolve("app.jar"), tmp.resolve("moved/app.jar"));
        assertThat(runJar(tmp.resolve("moved"), "app.jar")).isEqualTo("hello from libs");

        LibDirLayout.write(thin, target.resolve("app.jar"), libDir, "libs", List.of(), "app.Main");
        assertThat(libDir.resolve("greeting-1.0.jar")).doesNotExist();
        try (JarFile jar = new JarFile(target.resolve("app.jar").toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH))
                    .isNull();
        }
        LibDirLayout.prune(tmp.resolve("absent"), Set.of());
    }

    /** One class compiled into a jar with no manifest attributes beyond the version. */
    private static Path compileJar(Path dir, String className, String source, @Nullable Path classpath)
            throws Exception {
        Path src = dir.resolve("src/" + className.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        int rc = classpath == null
                ? ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString())
                : ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-cp", classpath.toString(), "-d", classes.toString(), src.toString());
        assertThat(rc).isZero();
        Path jar = dir.resolve(dir.getFileName() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            String entry = className.replace('.', '/') + ".class";
            out.putNextEntry(new ZipEntry(entry));
            out.write(Files.readAllBytes(classes.resolve(entry)));
            out.closeEntry();
        }
        return jar;
    }

    private static String runJar(Path dir, String jar) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process p = new ProcessBuilder(java.toString(), "-jar", jar)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        return out;
    }
}
