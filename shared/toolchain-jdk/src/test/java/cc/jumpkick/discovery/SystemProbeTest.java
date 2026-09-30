// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkUninstallPolicy;
import cc.jumpkick.testing.FakeJdk;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemProbeTest {

    @Test
    void linux_covers_the_roots_gradle_scans() {
        assertThat(SystemProbe.LINUX_ROOTS)
                .extracting(Path::toString)
                .containsExactly("/usr/lib/jvm", "/usr/java", "/usr/lib64/jvm", "/usr/local/java", "/opt/java");
    }

    @Test
    void every_root_is_listed_and_every_hit_is_system(@TempDir Path tmp) throws IOException {
        List<Path> roots = List.of(
                tmp.resolve("usr/lib/jvm"),
                tmp.resolve("usr/java"),
                tmp.resolve("usr/lib64/jvm"),
                tmp.resolve("usr/local/java"),
                tmp.resolve("opt/java"));
        for (int i = 0; i < roots.size(); i++) {
            FakeJdk.create(roots.get(i).resolve("jdk-" + i), "21.0." + i);
        }

        List<JdkHit> hits = new SystemProbe(roots, List::of).discoverAllJdks();

        assertThat(hits).hasSize(5);
        assertThat(hits).allSatisfy(h -> assertThat(h.source()).isEqualTo("system"));
        assertThat(JdkUninstallPolicy.removable("system")).isFalse();
    }

    @Test
    void java_home_listing_includes_homes_outside_the_system_root(@TempDir Path tmp) throws IOException {
        Path bundled =
                FakeJdk.create(tmp.resolve("Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"), "21.0.5");
        Path elsewhere = FakeJdk.create(tmp.resolve("Users/me/Library/Java/corretto-17/Contents/Home"), "17.0.9");
        String output = "Matching Java Virtual Machines (2):\n"
                + "    21.0.5 (arm64) \"Eclipse Adoptium\" - \"OpenJDK 21.0.5\" " + bundled + "\n"
                + "    17.0.9 (x86_64) \"Amazon.com Inc.\" - \"Amazon Corretto 17\" " + elsewhere + "\n"
                + bundled + "\n";

        List<JdkHit> hits = new SystemProbe(
                        List.of(tmp.resolve("Library/Java/JavaVirtualMachines")), () -> MacJavaHomes.parse(output))
                .discoverAllJdks();

        assertThat(hits).extracting(JdkHit::home).containsExactly(bundled.toRealPath(), elsewhere.toRealPath());
        assertThat(hits).allSatisfy(h -> assertThat(h.source()).isEqualTo("system"));
    }

    @Test
    void registry_java_home_and_adoptium_msi_path_are_hits_and_a_missing_key_adds_nothing() throws IOException {
        Map<String, Map<String, String>> registry = Map.of(
                "SOFTWARE\\JavaSoft\\JDK|JavaHome",
                Map.of("SOFTWARE\\JavaSoft\\JDK\\21", "C:\\Program Files\\Java\\jdk-21"),
                "SOFTWARE\\Eclipse Adoptium\\JDK|Path",
                Map.of(
                        "SOFTWARE\\Eclipse Adoptium\\JDK\\25.0.1.8\\hotspot\\MSI",
                        "C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.1.8-hotspot\\",
                        "SOFTWARE\\Eclipse Adoptium\\JDK\\25.0.1.8\\openj9\\MSI",
                        "C:\\ignored"));

        List<Path> homes = WindowsJavaRegistry.homes((key, name) -> registry.getOrDefault(key + "|" + name, Map.of()));

        assertThat(homes)
                .containsExactly(
                        Path.of("C:\\Program Files\\Java\\jdk-21"),
                        Path.of("C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.1.8-hotspot\\"));
        assertThat(WindowsJavaRegistry.homes((key, name) -> Map.of())).isEmpty();
    }

    @Test
    void reg_query_output_parses_to_subkey_and_value() {
        String output = "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Eclipse Adoptium\\JDK\\21.0.1.12\\hotspot\\MSI\r\n"
                + "    Path    REG_SZ    C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.1.12-hotspot\\\r\n"
                + "\r\nEnd of search: 1 match(es) found.\r\n";

        assertThat(WindowsJavaRegistry.parse(output, "Path"))
                .containsExactly(Map.entry(
                        "SOFTWARE\\Eclipse Adoptium\\JDK\\21.0.1.12\\hotspot\\MSI",
                        "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.1.12-hotspot\\"));
    }
}
