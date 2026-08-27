// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.discovery.JkProbe;
import cc.jumpkick.lock.Lockfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolchainLockStampTest {

    @Test
    void stamps_jdk_from_the_selected_home(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile stamped = ToolchainLockStamp.apply(Lockfile.empty("0.1"), home, registry, 25, false);
        assertThat(stamped.jdk()).isEqualTo(new Lockfile.JdkPin("temurin", "25.0.4"));
        assertThat(stamped.graal()).isNull();
    }

    @Test
    void stamps_graal_when_the_project_declared_it_and_one_is_installed(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path java = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        fakeJdk(jdks.resolve("graalce-25.0.4"), "25.0.4", "GraalVM Community", "GRAALVM_VERSION=\"25.0.4\"\n");
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile stamped = ToolchainLockStamp.apply(Lockfile.empty("0.1"), java, registry, 25, true);
        assertThat(stamped.jdk()).isEqualTo(new Lockfile.JdkPin("temurin", "25.0.4"));
        assertThat(stamped.graal()).isEqualTo(new Lockfile.GraalPin("graalvm-ce", "25.0.4"));
    }

    @Test
    void stamps_both_tables_from_a_graal_java_home(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path graal = fakeJdk(
                jdks.resolve("graalvm-25.0.4"),
                "25.0.4",
                "Oracle Corporation",
                "IMPLEMENTOR_VERSION=\"Oracle GraalVM 25\"\nGRAALVM_VERSION=\"25.0.4\"\n");
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile stamped = ToolchainLockStamp.apply(Lockfile.empty("0.1"), graal, registry, 25, false);
        assertThat(stamped.jdk()).isEqualTo(new Lockfile.JdkPin("graalvm", "25.0.4"));
        assertThat(stamped.graal()).isEqualTo(new Lockfile.GraalPin("graalvm", "25.0.4"));
    }

    @Test
    void no_jdk_table_when_the_selected_home_is_not_the_declared_major(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        // The host has only 25; the project asked for 17. Stamping 25 here is what would let the
        // pin read as satisfied and leave 17 unprovisioned, so no table is written at all.
        Lockfile stamped = ToolchainLockStamp.apply(Lockfile.empty("0.1"), home, registry, 17, false);
        assertThat(stamped.jdk()).isNull();
    }

    @Test
    void no_jdk_table_when_the_project_declares_no_jdk(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        // Nothing declared: the ambient JVM is not a pin the project chose.
        Lockfile stamped = ToolchainLockStamp.apply(Lockfile.empty("0.1"), home, registry, 0, false);
        assertThat(stamped.jdk()).isNull();
    }

    @Test
    void no_graal_table_when_nothing_declared_it(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path java = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        fakeJdk(jdks.resolve("graalce-25.0.4"), "25.0.4", "GraalVM Community", "GRAALVM_VERSION=\"25.0.4\"\n");
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        // The machine has a GraalVM; no manifest asked for one. This is the case that produced
        // JK-1020 — an ambient SDKMAN graalvm-ce stamped into jk's own shared lock, which would
        // then demand that distribution from everyone else reading it.
        Lockfile stamped = ToolchainLockStamp.apply(Lockfile.empty("0.1"), java, registry, 25, false);
        assertThat(stamped.graal()).isNull();
        assertThat(stamped.jdk()).isEqualTo(new Lockfile.JdkPin("temurin", "25.0.4"));
    }

    private static Path fakeJdk(Path home, String version, String implementor, String extra) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake\n");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake\n");
        String release = "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n"
                + (extra == null ? "" : extra);
        Files.writeString(home.resolve("release"), release);
        JdkOwnership.mark(home);
        return home.toRealPath();
    }
}
