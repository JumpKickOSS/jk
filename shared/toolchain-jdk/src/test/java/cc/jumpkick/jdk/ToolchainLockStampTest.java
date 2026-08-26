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

        Lockfile stamped = ToolchainLockStamp.apply(Lockfile.empty("0.1"), home, registry);
        assertThat(stamped.jdk()).isEqualTo(new Lockfile.JdkPin("temurin", "25.0.4"));
        assertThat(stamped.graal()).isNull();
    }

    @Test
    void stamps_graal_when_a_graalvm_is_installed(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path java = fakeJdk(jdks.resolve("temurin-25.0.4"), "25.0.4", "Eclipse Adoptium", null);
        fakeJdk(jdks.resolve("graalce-25.0.4"), "25.0.4", "GraalVM Community", "GRAALVM_VERSION=\"25.0.4\"\n");
        JdkRegistry registry = new JdkRegistry(jdks, List.of(new JkProbe(jdks)));

        Lockfile stamped = ToolchainLockStamp.apply(Lockfile.empty("0.1"), java, registry);
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

        Lockfile stamped = ToolchainLockStamp.apply(Lockfile.empty("0.1"), graal, registry);
        assertThat(stamped.jdk()).isEqualTo(new Lockfile.JdkPin("graalvm", "25.0.4"));
        assertThat(stamped.graal()).isEqualTo(new Lockfile.GraalPin("graalvm", "25.0.4"));
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
