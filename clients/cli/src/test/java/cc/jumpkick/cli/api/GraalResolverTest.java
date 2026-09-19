// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.host.GraalLauncher;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jdk.JdkOwnership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
    void a_flavour_does_not_answer_over_the_locks_graal_pin(@TempDir Path tmp) throws IOException {
        // A module that links a native image and pins no `graal` resolves with the bare flavour
        // "graalvm". That is a preference, not an answer: the lock's [graal] pin names one, and
        // for exactly these modules the flavour was taken as the answer and the pin never read.
        // Taking the flavour as the answer hands back whichever install the registry lists first,
        // which is the 21 here — so pinning the 25 is what makes the difference visible.
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        graalvm(jdks, "graalvm-21.0.5", "21.0.5");
        Path pinned = graalvm(jdks, "graalvm-25.0.4", "25.0.4");
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(
                project.resolve("jk-lock.toml"),
                "[graal]\nsuggested-vendor = \"graalvm\"\nsuggested-version = \"25.0.4\"\n");

        Optional<Path> home =
                new GraalResolver(jdks, false, BuildPlanConsole.Mode.QUIET).resolve(project, "graalvm", 0);

        assertThat(home).contains(pinned);
    }

    @Test
    void a_flavour_takes_the_installed_graal_that_clears_the_modules_release(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        graalvm(jdks, "graalvm-21.0.5", "21.0.5");
        Path g25 = graalvm(jdks, "graalvm-25.0.4", "25.0.4");
        Path project = Files.createDirectories(tmp.resolve("app"));

        assertThat(new GraalResolver(jdks, false, BuildPlanConsole.Mode.QUIET).resolve(project, "graalvm", 25))
                .as("a 21 cannot build a module targeting 25")
                .contains(g25);
    }

    /** A GraalVM install: a JDK the probe accepts, plus the launcher a native build needs. */
    private static Path graalvm(Path jdks, String name, String version) throws IOException {
        Path home = jdks.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        Files.writeString(home.resolve("bin").resolve(GraalLauncher.NAME), "#!/fake");
        Files.writeString(
                home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Oracle Corporation\"\n"
                        + "IMPLEMENTOR_VERSION=\"Oracle GraalVM " + version + "\"\n");
        JdkOwnership.mark(home);
        return home;
    }

    @Test
    void a_home_that_holds_no_launcher_is_refused_rather_than_handed_on(@TempDir Path dir) throws IOException {
        // What an install used to hand back for a directory that was not an install: a home with
        // nothing in it. Unchecked, it reached the engine, which fell through to the project's JDK
        // and failed naming that instead.
        Path home = Files.createDirectories(dir.resolve("graalvm-25"));

        assertThat(GraalResolver.carriesNativeImage(home)).isFalse();

        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve(GraalLauncher.NAME), "#!/fake");

        assertThat(GraalResolver.carriesNativeImage(home)).isTrue();
    }

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
