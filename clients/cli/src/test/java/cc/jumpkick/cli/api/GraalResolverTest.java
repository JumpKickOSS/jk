// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.GraalLauncher;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher -> home inverse. {@code jk native} hands {@code NativePlans.nativeStep} a GraalVM
 * HOME, and the search that precedes it produces a LAUNCHER, so something has to invert the layout.
 * Doing it as a parent-of-parent at the call site was correct for {@code <home>/bin/native-image}
 * and silently wrong for the {@code lib/svm/bin} layout the same search can return.
 */
class GraalResolverTest {

    @Test
    void svm_launcher_yields_the_home_not_the_lib_dir(@TempDir Path dir) {
        Path home = dir.resolve("graalvm-25");
        Path svmBin = home.resolve("lib").resolve("svm").resolve("bin");
        Path launcher = svmBin.resolve(GraalLauncher.EXE);
        Path fallback = dir.resolve("temurin-25");

        assertThat(GraalResolver.graalHomeOf(launcher, fallback)).isEqualTo(home);
        assertThat(GraalResolver.graalHomeOf(launcher, fallback)).isNotEqualTo(svmBin.getParent());
    }

    @Test
    void bin_launcher_yields_the_home(@TempDir Path dir) {
        Path home = dir.resolve("graalvm-25");
        Path fallback = dir.resolve("temurin-25");

        assertThat(GraalResolver.graalHomeOf(home.resolve("bin").resolve(GraalLauncher.NAME), fallback))
                .isEqualTo(home);
        assertThat(GraalResolver.graalHomeOf(home.resolve("bin").resolve(GraalLauncher.CMD), fallback))
                .isEqualTo(home);
    }

    @Test
    void an_unrecognised_layout_falls_back_rather_than_naming_a_wrong_home(@TempDir Path dir) {
        Path launcher = dir.resolve("opt").resolve("tools").resolve(GraalLauncher.NAME);
        Path fallback = dir.resolve("temurin-25");

        assertThat(GraalResolver.graalHomeOf(launcher, fallback)).isEqualTo(fallback);
    }
}
