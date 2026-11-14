// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a JDK contributes to a key, from two machines: the compile token names the compiler (full
 * version and {@code jdk.compiler} content) and nothing of the host; the platform token names the
 * whole {@code release} file. Each fake home is a {@code release} file beside a link to the running
 * JDK's {@code lib/}, so both read the same real compiler.
 */
class JdkTokenPortabilityTest {

    private static final String LINUX = """
            IMPLEMENTOR="Eclipse Adoptium"
            JAVA_VERSION="%s"
            OS_NAME="Linux"
            OS_ARCH="x86_64"
            LIBC="gnu"
            SOURCE=".:git:7ec19fb1eda7"
            BUILD_INFO="OS: Linux Version: 6.8.0-1064-azure"
            """;

    private static final String MAC = """
            IMPLEMENTOR="Amazon.com Inc."
            JAVA_VERSION="%s"
            OS_NAME="Darwin"
            OS_ARCH="aarch64"
            LIBC="default"
            SOURCE=".:git:20cebe6fd824+"
            BUILD_INFO="OS: Mac OS X Version: 14.7"
            """;

    @Test
    void two_hosts_on_one_java_version_share_the_compile_token_and_not_the_platform_token(@TempDir Path tmp)
            throws IOException {
        Path linux = home(tmp.resolve("linux"), LINUX, "25.0.5");
        Path mac = home(tmp.resolve("mac"), MAC, "25.0.5");

        assertThat(ActionKey.compileJdkToken(linux))
                .startsWith("javac:25.0.5:")
                .as("the compiler module was read, not an empty walk")
                .doesNotEndWith(Hashing.sha256Hex(new byte[0]))
                .isEqualTo(ActionKey.compileJdkToken(mac));
        assertThat(ActionKey.platformJdkToken(linux)).isNotEqualTo(ActionKey.platformJdkToken(mac));
    }

    @Test
    void a_patch_release_moves_the_compile_token(@TempDir Path tmp) throws IOException {
        Path older = home(tmp.resolve("older"), LINUX, "25.0.4");
        Path newer = home(tmp.resolve("newer"), LINUX, "25.0.5");

        assertThat(ActionKey.compileJdkToken(older)).isNotEqualTo(ActionKey.compileJdkToken(newer));
    }

    /**
     * One locked module under two checkout roots, two user homes (its classpath jar and kotlinc's
     * worker in each home's store) and two JDK homes that differ in OS, architecture, libc, vendor
     * and build host: javac, kotlinc and package-jar keys are equal. A patch apart, the compile keys
     * differ and the package key, which names the classes, does not.
     */
    @Test
    void a_module_keys_alike_on_two_machines_and_apart_across_a_patch(@TempDir Path tmp) throws IOException {
        Machine one = machine(tmp.resolve("one"), "work/app", LINUX, "25.0.5");
        Machine two = machine(tmp.resolve("two"), "src/nested/app", MAC, "25.0.5");
        Machine patched = machine(tmp.resolve("three"), "app", LINUX, "25.0.4");

        assertThat(javacKey(one)).as("javac").isEqualTo(javacKey(two));
        assertThat(kotlincKey(one)).as("kotlinc").isEqualTo(kotlincKey(two));
        assertThat(jarKey(one)).as("package-jar").isEqualTo(jarKey(two));

        assertThat(javacKey(patched)).as("javac across a patch").isNotEqualTo(javacKey(one));
        assertThat(kotlincKey(patched)).as("kotlinc across a patch").isNotEqualTo(kotlincKey(one));
        assertThat(jarKey(patched)).as("package-jar across a patch").isEqualTo(jarKey(one));
    }

    @Test
    void a_home_without_a_module_image_keys_its_release_file(@TempDir Path tmp) throws IOException {
        Path bare = Files.createDirectories(tmp.resolve("bare"));
        Files.writeString(bare.resolve("release"), LINUX.formatted("25.0.5"));
        assertThat(ActionKey.compileJdkToken(bare)).isEqualTo(ActionKey.platformJdkToken(bare));
    }

    private record Machine(Path module, Path javaHome, Path libJar, Path worker) {}

    private static Machine machine(Path root, String checkout, String release, String version) throws IOException {
        Path module = root.resolve(checkout);
        Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(module.resolve("jk.toml"), "name = \"app\"\n");
        Files.writeString(
                module.resolve("jk-lock.toml"), "version = 1\nproject-id = \"0123456789abcdef0123456789abcdef\"\n");
        Files.writeString(module.resolve("src/main/java/Hello.java"), "class Hello {}");
        Files.writeString(module.resolve("src/main/java/App.kt"), "object App");
        Path store = Files.createDirectories(root.resolve("home/user/.jk/store/repos/central/org/acme/lib/1.0"));
        Path libJar = jar(store.resolve("lib-1.0.jar"), "org/acme/lib.txt");
        Path worker = jar(store.resolve("kotlin-worker.jar"), "worker.txt");
        return new Machine(module, home(root.resolve("jdks/jdk"), release, version), libJar, worker);
    }

    private static String javacKey(Machine m) throws IOException {
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(m.module().resolve("src/main/java/Hello.java")))
                .classpath(List.of(m.libJar()))
                .outputDir(m.module().resolve("target/classes"))
                .release(21)
                .javaHome(m.javaHome())
                .pinnedJdk(true)
                .build();
        return ActionKey.forJavac(
                ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, m.module().resolve("target/classes")),
                request,
                "0.1.0");
    }

    private static String kotlincKey(Machine m) throws IOException {
        KotlincRequest request = KotlincRequest.builder()
                .sources(List.of(m.module().resolve("src/main/java/App.kt")))
                .outputDir(m.module().resolve("target/classes"))
                .jvmTarget(21)
                .workerClasspath(List.of(m.worker()))
                .javaHome(m.javaHome())
                .build();
        return ActionKey.forKotlinc(
                ActionKey.qualifiedTaskId(TaskNames.COMPILE_KOTLIN, m.module().resolve("target/classes")),
                request,
                "0.1.0",
                KotlinClasspathAbi.MEMOIZED_ONLY);
    }

    private static String jarKey(Machine m) {
        return ActionKey.forArtifact(
                ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, m.module().resolve("target/app-1.0.jar")),
                "0.1.0",
                List.of("classes:dir:abc", "main:App", "manifest:"));
    }

    /** A JDK home: {@code release} with these lines, {@code lib/} the running JDK's. */
    private static Path home(Path home, String release, String version) throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve("release"), release.formatted(version));
        Path lib = JavaHomes.runningJavaHome().toAbsolutePath().resolve("lib");
        Symlinks.create(home.resolve("lib"), lib);
        return home;
    }

    private static Path jar(Path path, String entry) throws IOException {
        try (OutputStream out = Files.newOutputStream(path);
                JarOutputStream jar = new JarOutputStream(out)) {
            JarEntry e = new JarEntry(entry);
            e.setTime(0);
            jar.putNextEntry(e);
            jar.write(entry.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return path;
    }
}
