// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A distribution's product-info.json: the build, this host's launcher flags, and where each plugin lies. */
class ProductInfoTest {

    @TempDir
    Path tmp;

    @Test
    void the_launcher_flags_name_the_distribution_and_the_splash_screen_is_dropped() throws IOException {
        Path ide = new FakeIde(tmp.resolve("ide"))
                .plugin("com.intellij.java", "java", "<idea-plugin/>")
                .write();
        ProductInfo info = ProductInfo.read(ide, ProductInfo.hostOs(), ProductInfo.hostArch());
        String home = ide.toAbsolutePath().normalize().toString();

        assertThat(info.buildNumber()).isEqualTo("262.10968.63");
        assertThat(info.jvmArgs())
                .containsExactly(
                        "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader",
                        "-Djna.boot.library.path=" + home + "/lib/jna",
                        "-Dpty4j.preferred.native.folder=" + home + "/lib/pty4j",
                        "-Xbootclasspath/a:" + home + "/lib/nio-fs.jar",
                        "--add-opens=java.base/java.lang=ALL-UNNAMED");
        assertThat(info.pluginDir("com.intellij.java")).isEqualTo("java");
        assertThat(info.pluginIn("java")).isEqualTo("com.intellij.java");
        assertThat(info.pluginIn("kotlin")).isNull();
    }

    @Test
    void a_host_the_distribution_does_not_launch_on_is_named() throws IOException {
        Path ide = new FakeIde(tmp.resolve("ide")).write();
        assertThatThrownBy(() -> ProductInfo.read(ide, "Plan9", "mips"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no launch entry for Plan9/mips");
        assertThatThrownBy(() -> ProductInfo.read(tmp, "Linux", "amd64"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not an IntelliJ distribution");
    }

    @Test
    void the_branch_is_the_first_segment_of_a_build_number_only() {
        assertThat(ProductInfo.branch("262.10968.63")).isEqualTo("262");
        assertThat(ProductInfo.branch("241.19416")).isEqualTo("241");
        assertThat(ProductInfo.branch("2026.2.3")).as("a marketing version").isNull();
        assertThat(ProductInfo.branch("262")).isNull();
        assertThat(ProductInfo.directoryOf("plugins/java/lib/java-impl.jar")).isEqualTo("java");
        assertThat(ProductInfo.directoryOf("lib/app.jar")).isNull();
    }
}
