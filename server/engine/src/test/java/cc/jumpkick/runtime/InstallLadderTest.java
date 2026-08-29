// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The install ladder chooses from disk, and {@code target/} can hold leftovers from before a
 * declaration was removed — so every rung is gated on the manifest, or a stale {@code -min.jar}
 * silently outranks the artifact this build actually produced.
 */
class InstallLadderTest {

    @TempDir
    Path tmp;

    private static JkBuild app(boolean assembly, boolean minified) {
        return JkBuild.builder(Project.builder("ex", "demo", "1.0")
                        .jdkMajor(25)
                        .java(25)
                        .build())
                .application(new JkBuild.Application("ex.Main", assembly, minified, false, null))
                .build();
    }

    private static void write(Path... artifacts) throws Exception {
        for (Path p : artifacts) {
            Files.createDirectories(p.getParent());
            Files.writeString(p, "bytes");
        }
    }

    @Test
    void an_undeclared_min_jar_leftover_never_wins() throws Exception {
        JkBuild project = app(false, false);
        BuildLayout layout = BuildLayout.of(tmp, project);
        write(layout.minifiedJar(), layout.assemblyJar());

        assertThat(InstallPlans.declaredFatJar(project, layout))
                .as("target/ leftovers from a removed declaration must not be installed")
                .isEmpty();
    }

    @Test
    void a_declared_minified_jar_wins_the_ladder() throws Exception {
        JkBuild project = app(false, true);
        BuildLayout layout = BuildLayout.of(tmp, project);
        write(layout.minifiedJar(), layout.assemblyJar());

        assertThat(InstallPlans.declaredFatJar(project, layout)).contains(layout.minifiedJar());
    }

    @Test
    void declared_minified_without_its_jar_falls_back_to_the_implied_assembly() throws Exception {
        JkBuild project = app(false, true);
        BuildLayout layout = BuildLayout.of(tmp, project);
        write(layout.assemblyJar());

        assertThat(InstallPlans.declaredFatJar(project, layout)).contains(layout.assemblyJar());
    }

    @Test
    void a_supported_mode_native_binary_on_disk_does_not_get_installed() throws Exception {
        // SUPPORTED: `jk native` is an explicit verb and install deploys the jar; the binary in
        // target/ is that verb's output, not this install's.
        JkBuild project = app(true, false);
        BuildLayout layout = BuildLayout.of(tmp, project);
        write(layout.nativeBinary());

        assertThat(InstallPlans.installsNativeBinary(project, layout)).isFalse();
    }
}
