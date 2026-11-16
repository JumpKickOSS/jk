// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.discovery.JkProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An unpinned module prefers this jk release's build of its major ({@link ReleaseJdks}) when it is
 * installed, whatever else the host has, so two hosts that both have it build on one JDK; a pin
 * still wins; absent, it is never installed for its own sake and the module builds on whatever
 * installed JDK satisfies it.
 */
class ReleaseJdkResolutionTest {

    private static final String RELEASE = ReleaseJdks.spec(25).orElseThrow();

    @Test
    void two_hosts_with_different_patches_resolve_to_the_release_build(@TempDir Path tmp) throws IOException {
        Path one = Files.createDirectories(tmp.resolve("one/jdks"));
        makeJdk(one, RELEASE);
        makeJdk(one, "temurin-25.0.9");
        Path two = Files.createDirectories(tmp.resolve("two/jdks"));
        makeJdk(two, "temurin-25.0.2");
        makeJdk(two, RELEASE);

        JdkResolution.Resolved a = JdkResolution.resolve(req(tmp, null), reg(one), inv(tmp.resolve("one")), 25);
        JdkResolution.Resolved b = JdkResolution.resolve(req(tmp, null), reg(two), inv(tmp.resolve("two")), 25);
        assertThat(a.tier()).isEqualTo(JdkResolution.Tier.RELEASE);
        assertThat(b.tier()).isEqualTo(JdkResolution.Tier.RELEASE);
        assertThat(a.jdkOpt().orElseThrow().home().getFileName().toString()).isEqualTo(RELEASE);
        assertThat(b.jdkOpt().orElseThrow().home().getFileName().toString()).isEqualTo(RELEASE);

        // Without the tier (before release builds), the first host took its newest patch.
        JdkResolution.Resolved old =
                JdkResolution.resolveWithoutRelease(req(tmp, null), reg(one), inv(tmp.resolve("one")), 25);
        assertThat(old.jdkOpt().orElseThrow().home().getFileName().toString()).isEqualTo("temurin-25.0.9");
    }

    @Test
    void a_pin_still_wins(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        makeJdk(jdks, RELEASE);
        makeJdk(jdks, "temurin-25.0.2");
        JdkResolution.Resolved r = JdkResolution.resolve(req(tmp, "temurin-25.0.2"), reg(jdks), inv(tmp), 25);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.PROJECT_TOML);
        assertThat(r.jdkOpt().orElseThrow().home().getFileName().toString()).isEqualTo("temurin-25.0.2");
    }

    /**
     * Absent, the release build is not a requirement: a build and the hook alike take the JDK that
     * is installed and satisfies the module, and nothing is installed (JK-ADBJV).
     */
    @Test
    void absent_it_is_passed_over_and_nothing_is_installed(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        makeJdk(jdks, "temurin-25.0.2");
        for (JdkResolution.Resolved r : List.of(
                JdkResolution.resolve(req(tmp, null), reg(jdks), inv(tmp), 25),
                JdkResolution.resolveForHook(req(tmp, null), reg(jdks), inv(tmp)))) {
            assertThat(r.tier()).isEqualTo(JdkResolution.Tier.DEFAULT);
            assertThat(r.wouldInstall()).isFalse();
            assertThat(r.jdkOpt().orElseThrow().home().getFileName().toString()).isEqualTo("temurin-25.0.2");
        }
    }

    /**
     * The release build offered as the request's JAVA_HOME serves the tier though no root jk scans
     * holds it; another build offered so builds as the JAVA_HOME it is, with nothing installed.
     */
    @Test
    void the_release_build_offered_as_java_home_serves_and_another_build_serves_as_itself(@TempDir Path tmp)
            throws IOException {
        Path elsewhere = makeJdk(Files.createDirectories(tmp.resolve("opt")), RELEASE);
        Path other = makeJdk(Files.createDirectories(tmp.resolve("opt2")), "temurin-25.0.2");
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));

        JdkResolution.Resolved offered = JdkResolution.resolve(withJavaHome(tmp, elsewhere), reg(jdks), inv(tmp), 25);
        assertThat(offered.tier()).isEqualTo(JdkResolution.Tier.RELEASE);
        assertThat(offered.jdkOpt().orElseThrow().home()).isEqualTo(elsewhere);

        JdkResolution.Resolved notIt = JdkResolution.resolve(withJavaHome(tmp, other), reg(jdks), inv(tmp), 25);
        assertThat(notIt.wouldInstall())
                .as("another build that satisfies the module is used, not replaced")
                .isFalse();
        assertThat(notIt.tier()).isEqualTo(JdkResolution.Tier.JAVA_HOME);
        assertThat(notIt.jdkOpt().orElseThrow().home()).isEqualTo(other);
    }

    /** A release build in a scanned root is found whatever JDK the request names as its JAVA_HOME. */
    @Test
    void a_scanned_release_build_wins_over_any_java_home(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path release = makeJdk(jdks, RELEASE);
        Path other = makeJdk(Files.createDirectories(tmp.resolve("opt")), "temurin-25.0.9");
        for (Path javaHome : new Path[] {other, release}) {
            JdkResolution.Resolved r = JdkResolution.resolve(withJavaHome(tmp, javaHome), reg(jdks), inv(tmp), 25);
            assertThat(r.tier()).isEqualTo(JdkResolution.Tier.RELEASE);
            assertThat(r.jdkOpt().orElseThrow().home()).isEqualTo(release);
        }
        JdkResolution.Resolved none = JdkResolution.resolve(req(tmp, null), reg(jdks), inv(tmp), 25);
        assertThat(none.jdkOpt().orElseThrow().home()).isEqualTo(release);
    }

    /** A JDK named by its absolute home is that JDK: nothing to look up or install, ahead of every tier. */
    @Test
    void a_switch_naming_a_home_is_that_jdk(@TempDir Path tmp) throws IOException {
        Path home = makeJdk(Files.createDirectories(tmp.resolve("opt")), "temurin-25.0.2");
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        JdkResolution.Request req = new JdkResolution.Request(
                tmp, home.toString(), null, null, null, 25, null, Map.<String, String>of()::get);
        JdkResolution.Resolved r = JdkResolution.resolve(req, reg(jdks), inv(tmp), 25);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.SWITCH);
        assertThat(r.jdkOpt().orElseThrow().home()).isEqualTo(home);
        assertThat(r.wouldInstall()).isFalse();
    }

    private static JdkResolution.Request withJavaHome(Path dir, Path javaHome) {
        return new JdkResolution.Request(dir, null, null, null, null, 25, javaHome, Map.<String, String>of()::get);
    }

    @Test
    void a_newer_java_level_takes_that_majors_release_build(@TempDir Path tmp) throws IOException {
        String release26 = ReleaseJdks.spec(26).orElseThrow();
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        makeJdk(jdks, release26);
        makeJdk(jdks, RELEASE);
        JdkResolution.Request req =
                new JdkResolution.Request(tmp, null, null, null, null, 26, null, Map.<String, String>of()::get);
        JdkResolution.Resolved r = JdkResolution.resolve(req, reg(jdks), inv(tmp), 25);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.RELEASE);
        assertThat(r.jdkOpt().orElseThrow().home().getFileName().toString()).isEqualTo(release26);
    }

    @Test
    void the_table_covers_every_supported_lts_from_the_floor_and_names_full_versions() {
        for (int major = SupportedJdk.MIN_MAJOR; major <= JdkLts.OFFLINE_LATEST_LTS; major++) {
            if (JdkLts.isLtsMajor(major))
                assertThat(ReleaseJdks.spec(major)).as("LTS " + major).isPresent();
        }
        ReleaseJdks.builds().forEach((major, spec) -> {
            assertThat(ReleaseJdks.version(spec))
                    .as(spec)
                    .startsWith(major + ".")
                    .matches("\\d+(\\.\\d+){2,}");
            assertThat(ReleaseJdks.vendor(spec)).as(spec).isEqualTo("temurin");
        });
    }

    private static JdkResolution.Request req(Path dir, @Nullable String projectJdk) {
        return new JdkResolution.Request(dir, null, null, null, projectJdk, 25, null, Map.<String, String>of()::get);
    }

    private static JdkRegistry reg(Path jdksRoot) {
        return new JdkRegistry(jdksRoot, List.of(new JkProbe(jdksRoot)));
    }

    private static JdkInventory inv(Path tmp) {
        return new JdkInventory(tmp.resolve("jdks"), tmp.resolve("state/jk-jdks.toml"));
    }

    private static Path makeJdk(Path jdksRoot, String dirName) throws IOException {
        Path home = jdksRoot.resolve(dirName);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        String version = dirName.substring(dirName.indexOf('-') + 1);
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        JdkOwnership.mark(home);
        return home.toRealPath();
    }
}
