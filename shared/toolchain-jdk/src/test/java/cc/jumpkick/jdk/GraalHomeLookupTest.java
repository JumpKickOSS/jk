// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import cc.jumpkick.lock.GraalPin;
import cc.jumpkick.tool.GraalHomeLookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's answer for a request that shipped no Graal home is the CLI's answer minus the
 * install: an explicit spec or a lock pin names one Graal and no other satisfies it; without
 * either, the pointer and the policy pick among what is installed.
 */
class GraalHomeLookupTest {

    @Test
    void an_explicit_spec_answers_its_own_install_or_nothing(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path graal25 = jdks.resolve("graalvm-25.0.4");
        makeGraalvmInstall(graal25, "25.0.4");
        Path project = Files.createDirectories(tmp.resolve("app"));

        assertThat(GraalHomeLookup.installed(project, jdks, null, "graalvm-25")).contains(graal25);
        assertThat(GraalHomeLookup.installed(project, jdks, "graalvm-21", "graalvm-25"))
                .as("the first non-blank spec decides by itself; a 25 does not satisfy a named 21")
                .isEmpty();
        assertThat(GraalHomeLookup.installed(project, jdks, "", null))
                .as("no spec: policy picks the one installed Graal")
                .contains(graal25);
    }

    @Test
    void a_plain_jdk_is_never_an_answer(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        makeJdkInstall(jdks.resolve("temurin-25"), "25.0.1");
        Path project = Files.createDirectories(tmp.resolve("app"));
        assertThat(GraalHomeLookup.installed(project, jdks, "temurin-25")).isEmpty();
        assertThat(GraalHomeLookup.installed(project, jdks)).isEmpty();
    }

    @Test
    void the_lock_pin_is_a_floor(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path older = jdks.resolve("graalvm-24.0.2");
        makeGraalvmInstall(older, "24.0.2");
        JdkRegistry registry = new JdkRegistry(jdks);
        var pin = new GraalPin("oracle-graalvm", "25.0.3", "", "");

        assertThat(GraalHomeLookup.byLockPin(registry, pin))
                .as("an unsatisfied pin answers nothing rather than an older Graal")
                .isEmpty();
        Path newer = jdks.resolve("graalvm-25.0.4");
        makeGraalvmInstall(newer, "25.0.4");
        assertThat(GraalHomeLookup.byLockPin(new JdkRegistry(jdks), pin))
                .as("major-or-better among installed wins")
                .contains(newer);
    }

    @Test
    void a_named_install_without_its_own_launcher_answers_nothing(@TempDir Path tmp) throws IOException {
        // The tier names ONE Graal, so the only question is whether that home can build. The check
        // used to run through NativeImageDriver.resolve, which falls back to $GRAALVM_HOME and then
        // every $PATH entry — so this home passed whenever any other GraalVM was on the path, and
        // the build linked with that one while believing it had honoured the spec.
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path graal = jdks.resolve("graalvm-25.0.4");
        makeGraalvmInstall(graal, "25.0.4");
        Files.delete(graal.resolve("bin").resolve(nativeImageName()));

        // A launcher does exist on this host — in another GraalVM, one directory over.
        Path elsewhere = Files.createDirectories(tmp.resolve("other-graalvm").resolve("bin"));
        Files.writeString(elsewhere.resolve(nativeImageName()), "#!/fake");

        assertThat(GraalHomeLookup.bySpec(new JdkRegistry(jdks), "graalvm-25"))
                .as("which is a different GraalVM, and not the one the spec named")
                .isEmpty();
    }

    @Test
    void a_flavour_with_a_release_floor_takes_the_lowest_graal_that_can_build_it(@TempDir Path tmp) throws IOException {
        // What a module linking a native image and pinning no `graal` resolves with: the bare
        // flavour, which matches any major. Its `java` says which majors can actually build it.
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path g21 = jdks.resolve("graalvm-21.0.5");
        makeGraalvmInstall(g21, "21.0.5");
        Path g25 = jdks.resolve("graalvm-25.0.4");
        makeGraalvmInstall(g25, "25.0.4");

        assertThat(GraalHomeLookup.bySpecAtLeast(new JdkRegistry(jdks), "graalvm", 25))
                .as("a 21 cannot build a module targeting 25, however well the flavour matches")
                .contains(g25);
        assertThat(GraalHomeLookup.bySpecAtLeast(new JdkRegistry(jdks), "graalvm", 21))
                .as("and a 21 is not replaced by a download just because a 25 exists")
                .contains(g21);
        assertThat(GraalHomeLookup.bySpecAtLeast(new JdkRegistry(jdks), "graalvm", 26))
                .as("nothing installed clears the floor, so the caller installs")
                .isEmpty();
        assertThat(GraalHomeLookup.bySpecAtLeast(new JdkRegistry(jdks), "graalvm", 0))
                .as("no declared release is no floor")
                .isPresent();
    }

    @Test
    void a_release_floor_does_not_reach_across_flavours(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        makeGraalvmInstall(jdks.resolve("graalvm-25.0.4"), "25.0.4");
        Path plain = jdks.resolve("temurin-25.0.4");
        makeJdkInstall(plain, "25.0.4");

        assertThat(GraalHomeLookup.bySpecAtLeast(new JdkRegistry(jdks), "temurin", 21))
                .as("a Temurin clears the floor and is still not a GraalVM")
                .isEmpty();
    }

    @Test
    void a_named_install_that_carries_its_own_launcher_answers(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path graal = jdks.resolve("graalvm-25.0.4");
        makeGraalvmInstall(graal, "25.0.4");

        assertThat(GraalHomeLookup.bySpec(new JdkRegistry(jdks), "graalvm-25")).contains(graal);
    }

    /** The launcher spelling {@link #makeGraalvmInstall} writes for this host. */
    private static String nativeImageName() {
        return Os.isWindows() ? "native-image.cmd" : "native-image";
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
