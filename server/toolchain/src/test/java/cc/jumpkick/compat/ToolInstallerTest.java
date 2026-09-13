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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
        http.serve("/gradle.zip.sha256", Hashing.sha256Hex(zip));

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
    void an_unpinned_archive_is_refused_when_no_checksum_is_published_beside_it(@TempDir Path tempDir)
            throws Exception {
        byte[] zip = buildZip("apache-maven-3.9.9", Map.of("bin/mvn", "#!/bin/sh\n", "bin/mvn.cmd", "@echo mvn\r\n"));
        http.served().put("/maven.zip", zip);

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist =
                new ToolDistribution(BuildTool.MAVEN, "3.9.9", http.base().resolve("/maven.zip"), "zip", null);

        assertThatThrownBy(() -> installer.install(dist))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot be verified")
                .hasMessageContaining(".sha512");
        assertThat(tempDir.resolve("tools/maven/3.9.9")).doesNotExist();
        assertThat(http.requestsFor("/maven.zip"))
                .as("the archive is not even downloaded when nothing vouches for it")
                .isZero();
    }

    @Test
    void an_unpinned_archive_is_verified_against_the_published_sidecar(@TempDir Path tempDir) throws Exception {
        byte[] zip = buildZip("apache-maven-3.9.9", Map.of("bin/mvn", "#!/bin/sh\n", "bin/mvn.cmd", "@echo mvn\r\n"));
        http.served().put("/maven.zip", zip);
        // Central's sidecar is the sha512sum form: digest, two spaces, file name.
        http.serve("/maven.zip.sha512", Hashing.hashHex("SHA-512", zip) + "  apache-maven-3.9.9-bin.zip\n");

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist =
                new ToolDistribution(BuildTool.MAVEN, "3.9.9", http.base().resolve("/maven.zip"), "zip", null);

        InstalledTool installed = installer.install(dist);
        assertThat(installed.home().resolve("bin/mvn")).exists();
        assertThat(http.requested()).containsSubsequence("/maven.zip.sha512", "/maven.zip");
    }

    @Test
    void a_published_sidecar_that_disagrees_with_the_archive_aborts_install(@TempDir Path tempDir) throws Exception {
        byte[] zip = buildZip("kotlinc", Map.of("bin/kotlinc", "#!/bin/sh\n", "bin/kotlinc.bat", "@echo\r\n"));
        http.served().put("/kotlin-compiler.zip", zip);
        http.serve("/kotlin-compiler.zip.sha256", "0".repeat(64));

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist = new ToolDistribution(
                BuildTool.KOTLIN, "2.4.10", http.base().resolve("/kotlin-compiler.zip"), "zip", null);

        assertThatThrownBy(() -> installer.install(dist))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sha256 mismatch");
        assertThat(tempDir.resolve("tools/kotlin/2.4.10")).doesNotExist();
    }

    @Test
    void a_sidecar_that_is_not_a_digest_counts_as_no_sidecar(@TempDir Path tempDir) throws Exception {
        byte[] zip = buildZip("gradle-9.5.1", Map.of("bin/gradle", "#!/bin/sh\n", "bin/gradle.bat", "@echo\r\n"));
        http.served().put("/gradle.zip", zip);
        http.serve("/gradle.zip.sha256", "<html><body>Not Found</body></html>");

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist =
                new ToolDistribution(BuildTool.GRADLE, "9.5.1", http.base().resolve("/gradle.zip"), "zip", null);

        assertThatThrownBy(() -> installer.install(dist))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("is not a sha256 digest");
        assertThat(tempDir.resolve("tools/gradle/9.5.1")).doesNotExist();
    }

    @Test
    void a_pinned_archive_needs_no_sidecar(@TempDir Path tempDir) throws Exception {
        byte[] zip = buildZip("apache-maven-3.9.9", Map.of("bin/mvn", "#!/bin/sh\n", "bin/mvn.cmd", "@echo mvn\r\n"));
        http.served().put("/maven.zip", zip);

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist = new ToolDistribution(
                BuildTool.MAVEN, "3.9.9", http.base().resolve("/maven.zip"), "zip", Hashing.sha256Hex(zip));

        installer.install(dist);
        assertThat(http.requestsFor("/maven.zip.sha512")).isZero();
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

    @Test
    void a_file_distribution_installs_from_disk_against_the_checksum_beside_it(@TempDir Path tempDir) throws Exception {
        byte[] zip = buildZip("apache-maven-3.9.9", Map.of("bin/mvn", "#!/bin/sh\n", "bin/mvn.cmd", "@echo mvn\r\n"));
        Path mirror = Files.createDirectories(tempDir.resolve("mirror"));
        Path archive = Files.write(mirror.resolve("apache-maven-3.9.9-bin.zip"), zip);
        Files.writeString(mirror.resolve("apache-maven-3.9.9-bin.zip.sha512"), Hashing.fileHex("SHA-512", archive));

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist = new ToolDistribution(BuildTool.MAVEN, "3.9.9", archive.toUri(), "zip", null);

        InstalledTool installed = installer.install(dist);
        assertThat(installed.home()).isEqualTo(tempDir.resolve("tools/maven/3.9.9"));
        assertThat(installed.home().resolve("bin/mvn")).hasContent("#!/bin/sh\n");
        assertThat(http.requestsFor("/"))
                .as("nothing is fetched over the network")
                .isZero();
    }

    @Test
    void a_file_distribution_without_a_pin_or_a_checksum_beside_it_is_refused(@TempDir Path tempDir) throws Exception {
        byte[] zip = buildZip("apache-maven-3.9.9", Map.of("bin/mvn", "#!/bin/sh\n"));
        Path mirror = Files.createDirectories(tempDir.resolve("mirror"));
        Path archive = Files.write(mirror.resolve("apache-maven-3.9.9-bin.zip"), zip);

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist = new ToolDistribution(BuildTool.MAVEN, "3.9.9", archive.toUri(), "zip", null);

        assertThatThrownBy(() -> installer.install(dist))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot be verified")
                .hasMessageContaining(archive.toUri() + ".sha512");
        assertThat(tempDir.resolve("tools/maven/3.9.9")).doesNotExist();
    }

    @Test
    void a_pinned_file_distribution_that_does_not_match_its_pin_is_refused(@TempDir Path tempDir) throws Exception {
        byte[] zip = buildZip("gradle-9.5.1", Map.of("bin/gradle", "#!/bin/sh\n"));
        Path archive = Files.write(tempDir.resolve("gradle-9.5.1-bin.zip"), zip);

        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist = new ToolDistribution(BuildTool.GRADLE, "9.5.1", archive.toUri(), "zip", "deadbeef");

        assertThatThrownBy(() -> installer.install(dist))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sha256 mismatch");
        assertThat(tempDir.resolve("tools/gradle/9.5.1")).doesNotExist();
    }

    @Test
    void a_file_distribution_that_is_not_on_disk_is_refused_naming_the_path(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("mirror/gradle-9.5.1-bin.zip");
        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(tempDir.resolve("tools")));
        ToolDistribution dist = new ToolDistribution(BuildTool.GRADLE, "9.5.1", missing.toUri(), "zip", "deadbeef");

        assertThatThrownBy(() -> installer.install(dist))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a file on this machine")
                .hasMessageContaining(missing.toString());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_zip_entry_routed_through_a_planted_link_is_refused_before_anything_is_written(@TempDir Path tempDir)
            throws Exception {
        Path dest = Files.createDirectories(tempDir.resolve("stage"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.createSymbolicLink(dest.resolve("apache-maven-3.9.9"), outside);
        Path zip = Files.write(
                tempDir.resolve("maven.zip"), buildZip("apache-maven-3.9.9", Map.of("bin/mvn", "#!/bin/sh\n")));

        assertThatThrownBy(() -> ToolInstaller.extract(zip, dest, "zip"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the destination");
        assertThat(outside).isEmptyDirectory();
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
