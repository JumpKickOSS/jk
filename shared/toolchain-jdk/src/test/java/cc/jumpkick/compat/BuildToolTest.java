// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code binaryName()} picks a launcher by host, and it used to pick it with
 * {@code os.name.contains("win")} — which is true of {@code Darwin}. A JVM reporting the kernel
 * name instead of {@code Mac OS X} therefore got {@code mvn.cmd} handed to {@code jk mvn}, and the
 * passthrough failed with "no such file" on a machine that had Maven installed. Spoofing the
 * property is the only way to reach the branch: {@link BuildTool} reads it live, through
 * {@code cc.jumpkick.host.Os}, for exactly that reason.
 */
class BuildToolTest {

    @Test
    void a_darwin_host_gets_the_posix_binary_not_the_windows_one() {
        withOsName("Darwin", () -> {
            assertThat(BuildTool.MAVEN.binaryName()).isEqualTo("mvn");
            assertThat(BuildTool.GRADLE.binaryName()).isEqualTo("gradle");
            assertThat(BuildTool.KOTLIN.binaryName()).isEqualTo("kotlinc");
        });
    }

    @Test
    void a_mac_os_x_host_gets_the_posix_binary() {
        withOsName("Mac OS X", () -> assertThat(BuildTool.MAVEN.binaryName()).isEqualTo("mvn"));
    }

    @Test
    void a_windows_host_gets_the_windows_binary() {
        withOsName("Windows 11", () -> {
            assertThat(BuildTool.MAVEN.binaryName()).isEqualTo("mvn.cmd");
            assertThat(BuildTool.GRADLE.binaryName()).isEqualTo("gradle.bat");
            assertThat(BuildTool.KOTLIN.binaryName()).isEqualTo("kotlinc.bat");
        });
    }

    @Test
    void a_linux_host_gets_the_posix_binary() {
        withOsName("Linux", () -> assertThat(BuildTool.MAVEN.binaryName()).isEqualTo("mvn"));
    }

    private static void withOsName(String osName, Runnable body) {
        String saved = System.getProperty("os.name");
        System.setProperty("os.name", osName);
        try {
            body.run();
        } finally {
            if (saved == null) System.clearProperty("os.name");
            else System.setProperty("os.name", saved);
        }
    }
}
