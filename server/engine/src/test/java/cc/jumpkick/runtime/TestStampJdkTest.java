// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JDK the suite runs on is a run-tests stamp input. A module with no JDK pin compiles on jk's
 * own runtime, so its test compile's key never names the JDK its test JVM gets (the module's
 * resolved JDK): a green marker must not replay once that JDK changes.
 */
@DisabledOnOs(OS.WINDOWS)
class TestStampJdkTest {

    @Test
    void a_patch_update_of_the_jdk_the_tests_run_on_moves_the_stamp(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        JkBuild project = project(module);
        // A stable <vendor>-<major> home updated in place: the path stays, the JDK does not.
        Path home = fakeJdk(tmp.resolve("temurin-25"), "25.0.1");
        List<String> before = extras(project, module, home);
        assertThat(extras(project, module, home))
                .as("an unchanged JDK costs nothing new")
                .isEqualTo(before);

        fakeJdk(home, "25.0.10");
        assertThat(extras(project, module, home))
                .as("the suite ran on 25.0.1; on 25.0.10 its green marker must not replay")
                .isNotEqualTo(before);
    }

    @Test
    void another_jdk_moves_the_stamp_and_the_same_jdk_under_another_path_does_not(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        JkBuild project = project(module);
        Path one = fakeJdk(tmp.resolve("one"), "25.0.2");
        Path two = fakeJdk(tmp.resolve("two"), "25.0.2");
        Path graal = fakeJdk(tmp.resolve("graal"), "25.0.2", "GraalVM Community");
        assertThat(extras(project, module, two)).isEqualTo(extras(project, module, one));
        assertThat(extras(project, module, graal)).isNotEqualTo(extras(project, module, one));
    }

    @Test
    void the_module_on_its_own_stamps_the_jdk_its_resolution_finds(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        JkBuild project = project(module);
        TestStampInputs.Facts facts = TestStampInputs.Facts.of(module, project);
        assertThat(facts.javaHome()).isEqualTo(JavaHomes.resolveJavaHome(module));
        assertThat(TestStampInputs.of(facts, ClasspathFingerprint.ON_DISK).extras())
                .contains("test-jdk:" + ActionKey.jdkToken(facts.javaHome()));
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
        Path bin = Files.createDirectories(home.resolve("bin"));
        for (String tool : List.of("java", "javac")) {
            Path exe = bin.resolve(tool);
            Files.writeString(exe, "#!/bin/sh\necho 'openjdk version \"" + version + "\"' >&2\n");
            Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Files.writeString(home.resolve("release"), """
                IMPLEMENTOR="%s"
                JAVA_VERSION="%s"
                OS_ARCH="x86_64"
                """.formatted(implementor, version));
        return home;
    }

    private static JkBuild project(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                java    = 25
                """);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }
}
