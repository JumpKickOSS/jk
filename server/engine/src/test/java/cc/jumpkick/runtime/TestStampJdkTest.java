// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run-tests stamp names the suite's Java level, not the JDK it runs on (JK-74S9K): the module's
 * {@code java}, else the major of its test JDK, so the level in the key is always concrete. The JDK
 * that ran is the green marker's TOOLCHAIN, matched by the reader's policy (same major by default),
 * so a patch, a vendor or a platform keeps the stamp and the policy decides; the level moves it.
 */
@DisabledOnOs(OS.WINDOWS)
class TestStampJdkTest {

    @Test
    void a_patch_a_vendor_and_a_platform_keep_the_stamp(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        JkBuild project = project(module, "java = 25\n");
        List<String> base = extras(project, module, fakeJdk(tmp.resolve("t25"), "25.0.1"));
        assertThat(base).contains("java:25").noneMatch(t -> t.startsWith("test-jdk:"));
        assertThat(extras(project, module, fakeJdk(tmp.resolve("t25b"), "25.0.10")))
                .isEqualTo(base);
        assertThat(extras(project, module, fakeJdk(tmp.resolve("g25"), "25.0.1", "GraalVM Community")))
                .isEqualTo(base);
        assertThat(extras(
                        project,
                        module,
                        fakeJdk(tmp.resolve("mac"), "25.0.1", "Eclipse Adoptium", "25.0.1+8-LTS", "Darwin", "aarch64")))
                .isEqualTo(base);
    }

    @Test
    void the_level_moves_the_stamp_and_no_level_keys_the_test_jdks_major(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        Path jdk25 = fakeJdk(tmp.resolve("t25"), "25.0.1");
        Path jdk27 = fakeJdk(tmp.resolve("t27"), "27.0.1");
        List<String> at21 = extras(project(module, "java = 21\n"), module, jdk25);
        List<String> at25 = extras(project(module, "java = 25\n"), module, jdk25);
        assertThat(at21).contains("java:21").isNotEqualTo(at25);

        JkBuild levelless = project(module, "");
        assertThat(extras(levelless, module, jdk25)).contains("java:25");
        assertThat(extras(levelless, module, jdk27)).contains("java:27");
    }

    @Test
    void the_module_on_its_own_stamps_its_level(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        JkBuild project = project(module, "java = 25\n");
        TestStampInputs.Facts facts = TestStampInputs.Facts.of(module, project);
        assertThat(facts.javaHome()).isEqualTo(JavaHomes.resolveJavaHome(module));
        assertThat(TestStampInputs.of(facts, ClasspathFingerprint.ON_DISK).extras())
                .contains("java:25");
    }

    /** The module's stamp extras with its tests run on {@code javaHome}, as the live run and the forecast hand it. */
    private static List<String> extras(JkBuild project, Path module, Path javaHome) throws Exception {
        TestStampInputs.Facts own = TestStampInputs.Facts.of(module, project);
        TestStampInputs.Facts facts = new TestStampInputs.Facts(
                own.dir(),
                own.project(),
                own.layout(),
                javaHome,
                own.profileName(),
                own.sessionSelection(),
                own.pluginDecls(),
                own.workerJars(),
                own.resolvedRuntime(),
                own.providedRuntime(),
                own.compileTestKeys());
        return TestStampInputs.of(facts, ClasspathFingerprint.ON_DISK).extras();
    }

    /** A JDK home as resolution and the stamp read it: a release file and a launcher pair. */
    private static Path fakeJdk(Path home, String version) throws Exception {
        return fakeJdk(home, version, "Eclipse Adoptium");
    }

    private static Path fakeJdk(Path home, String version, String implementor) throws Exception {
        return fakeJdk(home, version, implementor, null, "Linux", "x86_64");
    }

    private static Path fakeJdk(
            Path home, String version, String implementor, @Nullable String runtimeVersion, String os, String arch)
            throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        for (String tool : List.of("java", "javac")) {
            Path exe = bin.resolve(tool);
            Files.writeString(exe, "#!/bin/sh\necho 'openjdk version \"" + version + "\"' >&2\n");
            Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Files.writeString(home.resolve("release"), """
                IMPLEMENTOR="%s"
                JAVA_VERSION="%s"
                %s
                OS_NAME="%s"
                OS_ARCH="%s"
                BUILD_INFO="OS: %s"
                """.formatted(
                        implementor,
                        version,
                        runtimeVersion == null ? "" : "JAVA_RUNTIME_VERSION=\"" + runtimeVersion + "\"",
                        os,
                        arch,
                        os));
        return home;
    }

    private static JkBuild project(Path dir, String level) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """ + level);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }
}
