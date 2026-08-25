// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.http.Http;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class ToolInstallerTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @Test
    void installs_zip_and_flattens_top_level_dir(@TempDir Path tempDir) throws Exception {
        // Real Maven zips ship both POSIX and Windows launchers.
        byte[] zip = buildZip(
                "apache-maven-3.9.9",
                Map.of(
                        "bin/mvn", "#!/bin/sh\necho mvn\n",
                        "bin/mvn.cmd", "@echo mvn\r\n",
                        "conf/settings.xml", "<settings/>\n"));
        http.served().put("/maven.zip", zip);

        ToolRegistry registry = new ToolRegistry(tempDir.resolve("tools"));
        ToolInstaller installer = new ToolInstaller(new Http(), registry);

        ToolDistribution dist = new ToolDistribution(
                BuildTool.MAVEN, "3.9.9", http.base().resolve("/maven.zip"), "zip", Hashing.sha256Hex(zip));

        InstalledTool installed = installer.install(dist);
        assertThat(installed.home()).isEqualTo(tempDir.resolve("tools/maven/3.9.9"));
        assertThat(installed.home().resolve("bin/mvn")).exists();
        assertThat(installed.home().resolve("conf/settings.xml")).exists();
        Path expectedBin = installed.home().resolve("bin").resolve(Os.isWindows() ? "mvn.cmd" : "mvn");
        assertThat(installed.binary()).isEqualTo(expectedBin);
    }

    @Test
    void bin_launcher_is_marked_executable(@TempDir Path tempDir) throws Exception {
        byte[] zip =
                buildZip("gradle-9.5.1", Map.of("bin/gradle", "#!/bin/sh\n", "bin/gradle.bat", "@echo gradle\r\n"));
        http.served().put("/gradle.zip", zip);

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist =
                new ToolDistribution(BuildTool.GRADLE, "9.5.1", http.base().resolve("/gradle.zip"), "zip", null);

        InstalledTool installed = installer.install(dist);
        Path bin = installed.binary();
        assertThat(bin).exists();
        // POSIX only — on Windows the .bat doesn't need +x and the check is skipped.
        if (Files.getFileStore(bin).supportsFileAttributeView("posix")) {
            assertThat(Files.isExecutable(bin)).isTrue();
        }
    }

    @Test
    void sha256_mismatch_aborts_install(@TempDir Path tempDir) throws Exception {
        byte[] zip = buildZip("apache-maven-3.9.9", Map.of("bin/mvn", "#!/bin/sh\n", "bin/mvn.cmd", "@echo mvn\r\n"));
        http.served().put("/maven.zip", zip);

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist =
                new ToolDistribution(BuildTool.MAVEN, "3.9.9", http.base().resolve("/maven.zip"), "zip", "deadbeef");

        assertThatThrownBy(() -> installer.install(dist))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sha256 mismatch");
        assertThat(tempDir.resolve("tools/maven/3.9.9")).doesNotExist();
    }

    @Test
    void second_install_is_idempotent(@TempDir Path tempDir) throws Exception {
        byte[] zip = buildZip("apache-maven-3.9.9", Map.of("bin/mvn", "#!/bin/sh\n", "bin/mvn.cmd", "@echo mvn\r\n"));
        http.served().put("/maven.zip", zip);

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist = new ToolDistribution(
                BuildTool.MAVEN, "3.9.9", http.base().resolve("/maven.zip"), "zip", Hashing.sha256Hex(zip));

        InstalledTool first = installer.install(dist);
        InstalledTool second = installer.install(dist);
        assertThat(second.home()).isEqualTo(first.home());
    }

    private static byte[] buildZip(String topLevelDir, Map<String, String> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(topLevelDir + "/" + e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
