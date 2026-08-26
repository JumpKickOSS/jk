// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.discovery.JkProbe;
import cc.jumpkick.lock.Lockfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkResolutionTest {

    private static final int LATEST_LTS = 25;

    @Test
    void switch_wins_over_everything(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-21.0.5");
        makeJdk(jdks, "temurin-25.0.3");
        var req = req(tmp).switchSpec("21").projectJdkSpec("25").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.SWITCH);
        assertThat(r.jdk().get().home().getFileName().toString()).isEqualTo("temurin-21.0.5");
    }

    @Test
    void jdk_version_file_beats_lock_and_toml(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-21.0.5");
        makeJdk(jdks, "temurin-25.0.3");
        Files.writeString(tmp.resolve(".jdk-version"), "temurin-25");
        var req = req(tmp).lockJdk("temurin", "21.0.5").projectJdkSpec("21").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.JDK_VERSION_FILE);
        assertThat(r.jdk().get().home().getFileName().toString()).isEqualTo("temurin-25.0.3");
    }

    @Test
    void lock_beats_toml(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-21.0.5");
        makeJdk(jdks, "temurin-25.0.3");
        var req = req(tmp).lockJdk("temurin", "21.0.5").projectJdkSpec("25").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.LOCKFILE);
        assertThat(r.jdk().get().home().getFileName().toString()).isEqualTo("temurin-21.0.5");
    }

    @Test
    void de_facto_default_when_nothing_pinned_or_set(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-17.0.13");
        makeJdk(jdks, "temurin-21.0.5");
        Path j26 = makeJdk(jdks, "temurin-26.0.1");

        // No pins, no persisted default → policy picks 26 (25 not installed).
        var r = JdkResolution.resolve(req(tmp).build(), reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.DEFAULT);
        assertThat(r.jdk().get().home()).isEqualTo(j26);
    }

    @Test
    void build_signals_install_for_an_uninstalled_pin(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-21.0.5");
        var req = req(tmp).switchSpec("26").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.jdk()).isEmpty();
        assertThat(r.wouldInstall()).isTrue();
        assertThat(r.installSpec()).isEqualTo("26");
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.SWITCH);
    }

    @Test
    void hook_falls_through_an_uninstalled_pin(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        Path j25 = makeJdk(jdks, "temurin-25.0.3");
        JdkInventory gdj = gdj(tmp);
        gdj.setDefault(new InstalledJdk("temurin-25.0.3", j25));
        // Switch names an uninstalled 99 — the hook must not block; it falls
        // through to the default rather than reporting wouldInstall.
        var req = req(tmp).switchSpec("99").build();

        var r = JdkResolution.resolveForHook(req, reg(jdks), gdj);
        assertThat(r.wouldInstall()).isFalse();
        assertThat(r.jdk().get().home()).isEqualTo(j25);
    }

    @Test
    void java_release_floor_kicks_in_above_latest_lts(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        Path j26 = makeJdk(jdks, "temurin-26.0.1");
        // No jdk pin, project.java = 26 > latest LTS 25 → implied >=26.
        var req = req(tmp).projectJavaRelease(26).build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.JAVA_RELEASE_FLOOR);
        assertThat(r.jdk().get().home()).isEqualTo(j26);
    }

    @Test
    void jre_only_home_is_not_accepted_as_current_or_java_home(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        Path real = makeJdk(jdks, "temurin-25.0.3");
        // System package layout: java + release, no javac (Fedora/RHEL headless JRE).
        Path jre = tmp.resolve("java-25-openjdk");
        Files.createDirectories(jre.resolve("bin"));
        Files.writeString(JdkFingerprint.java(jre), "#!/fake");
        Files.writeString(jre.resolve("release"), "JAVA_VERSION=\"25.0.4\"\nIMPLEMENTOR=\"Red Hat, Inc.\"\n");

        JdkInventory gdj = gdj(tmp);
        gdj.setDefault(new InstalledJdk("java-25-openjdk", jre));
        // default points at a JRE → skipped; de-facto default should pick the real JDK.
        var r = JdkResolution.resolve(req(tmp).build(), reg(jdks), gdj, LATEST_LTS);
        assertThat(r.jdk().get().home()).isEqualTo(real);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.DEFAULT);
    }

    @Test
    void lock_accepts_newer_major_when_locked_install_is_gone(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        Path j26 = makeJdk(jdks, "temurin-26.0.1");
        var req = req(tmp).lockJdk("temurin", "25.0.4").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.LOCKFILE);
        assertThat(r.jdk().get().home()).isEqualTo(j26);
        assertThat(r.wouldInstall()).isFalse();
    }

    @Test
    void build_would_install_when_lock_major_is_unmet(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-21.0.5");
        var req = req(tmp).lockJdk("temurin", "25.0.4").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.jdk()).isEmpty();
        assertThat(r.wouldInstall()).isTrue();
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.LOCKFILE);
        assertThat(r.installSpec()).isEqualTo("temurin-25");
    }

    @Test
    void hook_does_not_export_a_too_old_default_for_an_unmet_lock(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        Path j21 = makeJdk(jdks, "temurin-21.0.5");
        JdkInventory gdj = gdj(tmp);
        gdj.setDefault(new InstalledJdk("temurin-21.0.5", j21));
        var req = req(tmp).lockJdk("temurin", "25.0.4").build();

        var r = JdkResolution.resolveForHook(req, reg(jdks), gdj);
        assertThat(r.wouldInstall()).isFalse();
        assertThat(r.jdk()).isEmpty();
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.NONE);
    }

    // -- helpers -------------------------------------------------------------

    private static Path jdks(Path tmp) throws IOException {
        return Files.createDirectories(tmp.resolve("jdks"));
    }

    private static JdkRegistry reg(Path jdksRoot) {
        return new JdkRegistry(jdksRoot, List.of(new JkProbe(jdksRoot)));
    }

    private static JdkInventory gdj(Path tmp) {
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

    private static ReqBuilder req(Path projectDir) {
        return new ReqBuilder(projectDir);
    }

    /** Tiny builder so each test only sets the tiers it cares about. */
    private static final class ReqBuilder {
        private final Path projectDir;
        private String switchSpec, envSpec, projectJdkSpec;
        private Lockfile.JdkPin lockJdk;
        private int projectJavaRelease;
        private final Map<String, String> env = new HashMap<>();

        ReqBuilder(Path projectDir) {
            this.projectDir = projectDir;
        }

        ReqBuilder switchSpec(String s) {
            this.switchSpec = s;
            return this;
        }

        ReqBuilder envSpec(String s) {
            this.envSpec = s;
            return this;
        }

        ReqBuilder lockJdk(String vendor, String version) {
            this.lockJdk = new Lockfile.JdkPin(vendor, version);
            return this;
        }

        ReqBuilder projectJdkSpec(String s) {
            this.projectJdkSpec = s;
            return this;
        }

        ReqBuilder projectJavaRelease(int r) {
            this.projectJavaRelease = r;
            return this;
        }

        JdkResolution.Request build() {
            return new JdkResolution.Request(
                    projectDir, switchSpec, envSpec, lockJdk, projectJdkSpec, projectJavaRelease, env::get);
        }
    }
}
