// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Boot-launcher facts read off the packager inputs: the resolved Boot version comes from the
 * closure, the libs keep lock order and their snapshot/group facts, and the two inputs a launcher
 * jar cannot do without fail here, where the cause is still legible.
 */
class BootJarInputsTest {

    @Test
    void libs_keep_lock_order_and_the_boot_version_is_the_closure_s(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, "com.example.App");
        Path guava = io.entry("guava-33.0.jar", "com.google.guava", "guava", "33.0");
        Path boot = io.entry("spring-boot-4.1.2.jar", "org.springframework.boot", "spring-boot", "4.1.2");
        Path snap = io.jar("shared-2.0-SNAPSHOT.jar", "com/example/Shared.class");
        io.entry(new PackageIo.RuntimeEntry(
                "shared-2.0-SNAPSHOT.jar", snap, true, null, "com.example", "shared", "2.0-SNAPSHOT"));

        BootJarInputs inputs = BootJarInputs.read(io);

        assertThat(inputs.bootVersion()).isEqualTo("4.1.2");
        assertThat(inputs.startClass()).isEqualTo("com.example.App");
        assertThat(inputs.loaderJar()).isEqualTo(io.extra("spring-boot-loader").orElseThrow());
        assertThat(inputs.libs())
                .containsExactly(
                        new BootJarPackager.Lib("guava-33.0.jar", guava, false, "com.google.guava"),
                        new BootJarPackager.Lib("spring-boot-4.1.2.jar", boot, false, "org.springframework.boot"),
                        new BootJarPackager.Lib("shared-2.0-SNAPSHOT.jar", snap, true, "com.example"));
    }

    /** No main class, no Start-Class: refused before any jar is written. */
    @Test
    void a_project_without_a_main_class_is_refused(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, null);
        io.entry("spring-boot-4.1.2.jar", "org.springframework.boot", "spring-boot", "4.1.2");

        assertThatThrownBy(() -> BootJarInputs.read(io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no application main class");
    }

    /** The loader jar is an engine-fetched packager dependency; its absence names it. */
    @Test
    void a_missing_loader_artifact_is_refused_by_name(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "spring-boot").project("com.example", "app", "1.0.0", "com.example.App");
        io.entry("spring-boot-4.1.2.jar", "org.springframework.boot", "spring-boot", "4.1.2");

        assertThatThrownBy(() -> BootJarInputs.read(io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("spring-boot-loader artifact missing");
    }

    /** A closure with Boot starters but no `spring-boot` itself has no version to record and nothing to launch. */
    @Test
    void a_closure_without_spring_boot_names_the_coordinate_to_declare(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, "com.example.App");
        io.entry("spring-core-7.0.0.jar", "org.springframework", "spring-core", "7.0.0");

        assertThatThrownBy(() -> BootJarInputs.read(io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("org.springframework.boot:spring-boot")
                .hasMessageContaining("[dependencies]");
    }

    /** Only the exact coordinate counts: a same-group artifact is a lib, not the Boot version. */
    @Test
    void a_same_group_artifact_does_not_stand_in_for_spring_boot(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = fake(tmp, "com.example.App");
        io.entry(
                "spring-boot-autoconfigure-4.1.2.jar",
                "org.springframework.boot",
                "spring-boot-autoconfigure",
                "4.1.2");

        assertThatThrownBy(() -> BootJarInputs.read(io)).isInstanceOf(IOException.class);
    }

    private static FakeBuildIo fake(Path tmp, String mainClass) throws IOException {
        FakeBuildIo io = new FakeBuildIo(tmp, "spring-boot").project("com.example", "app", "1.0.0", mainClass);
        return io.extra(
                "spring-boot-loader",
                io.jar("spring-boot-loader.jar", "org/springframework/boot/loader/launch/JarLauncher.class"));
    }
}
