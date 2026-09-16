// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import cc.jumpkick.lock.GraalPin;
import cc.jumpkick.tool.GraalHomeLookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's answer for a request that shipped no Graal home is the CLI's answer minus the
 * install: an explicit spec or a lock pin names one Graal and no other satisfies it; without
 * either, the pointer and the policy pick among what is installed.
 */
class GraalHomeLookupTest {

    private static final Function<String, @Nullable String> NO_ENV = name -> null;

    @Test
    void an_explicit_spec_answers_its_own_install_or_nothing(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path graal25 = jdks.resolve("graalvm-25.0.4");
        makeGraalvmInstall(graal25, "25.0.4");
        Path project = Files.createDirectories(tmp.resolve("app"));

        assertThat(GraalHomeLookup.installed(project, jdks, NO_ENV, null, "graalvm-25"))
                .contains(graal25);
        assertThat(GraalHomeLookup.installed(project, jdks, NO_ENV, "graalvm-21", "graalvm-25"))
                .as("the first non-blank spec decides by itself; a 25 does not satisfy a named 21")
                .isEmpty();
        assertThat(GraalHomeLookup.installed(project, jdks, NO_ENV, "", null))
                .as("no spec: policy picks the one installed Graal")
                .contains(graal25);
    }

    @Test
    void a_plain_jdk_is_never_an_answer(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        makeJdkInstall(jdks.resolve("temurin-25"), "25.0.1");
        Path project = Files.createDirectories(tmp.resolve("app"));
        assertThat(GraalHomeLookup.installed(project, jdks, NO_ENV, "temurin-25"))
                .isEmpty();
        assertThat(GraalHomeLookup.installed(project, jdks, NO_ENV)).isEmpty();
    }

    @Test
    void the_lock_pin_is_a_floor(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path older = jdks.resolve("graalvm-24.0.2");
        makeGraalvmInstall(older, "24.0.2");
        JdkRegistry registry = new JdkRegistry(jdks);
        var pin = new GraalPin("oracle-graalvm", "25.0.3", "", "");

        assertThat(GraalHomeLookup.byLockPin(registry, pin, NO_ENV))
                .as("an unsatisfied pin answers nothing rather than an older Graal")
                .isEmpty();
        Path newer = jdks.resolve("graalvm-25.0.4");
        makeGraalvmInstall(newer, "25.0.4");
        assertThat(GraalHomeLookup.byLockPin(new JdkRegistry(jdks), pin, NO_ENV))
                .as("major-or-better among installed wins")
                .contains(newer);
    }

    @Test
    void the_environment_consulted_is_the_callers(@TempDir Path tmp) throws IOException {
        // A home whose launcher is only reachable through $GRAALVM_HOME resolves against the
        // request's environment, never the process's.
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path graal = jdks.resolve("graalvm-25.0.4");
        makeGraalvmInstall(graal, "25.0.4");
        Function<String, @Nullable String> env = Map.of("GRAALVM_HOME", graal.toString())::get;
        assertThat(GraalHomeLookup.bySpec(new JdkRegistry(jdks), "graalvm-25", env))
                .contains(graal);
        assertThat(Optional.ofNullable(env.apply("PATH"))).isEmpty();
    }

    private static void makeJdkInstall(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        JdkOwnership.mark(home);
    }

    private static void makeGraalvmInstall(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        String nativeImage = Os.isWindows() ? "native-image.cmd" : "native-image";
        Files.writeString(home.resolve("bin").resolve(nativeImage), "#!/fake");
        Files.writeString(
                home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Oracle Corporation\"\n"
                        + "IMPLEMENTOR_VERSION=\"Oracle GraalVM " + version + "\"\n");
        JdkOwnership.mark(home);
    }
}
