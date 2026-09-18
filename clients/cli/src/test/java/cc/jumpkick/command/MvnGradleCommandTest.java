// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link MvnCommand} and {@link GradleCommand} end-to-end against a local HTTP server
 * serving a synthetic distribution. The "binary" inside the zip is a tiny shell script that records
 * its argv and env so we can verify passthrough + env scrubbing without depending on a real
 * Maven/Gradle. jk's own globals ({@code -C}) go before the command name; everything after it
 * belongs to the tool, except the command's own exactly-spelled options.
 */
@DisabledOnOs(OS.WINDOWS) // launcher scripts are .sh-only.
@Tag("integration")
class MvnGradleCommandTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @Test
    void mvn_passthrough_installs_and_forwards_args(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".mvn/wrapper"));
        Path argsLog = tempDir.resolve("argv.log");
        Path envLog = tempDir.resolve("env.log");

        serveMaven(recordingZip("apache-maven-3.9.9", "mvn", argsLog, envLog));
        Files.writeString(
                projectDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=" + maven.base().resolve("/apache-maven-3.9.9-bin.zip") + "\n");

        int exit = run(
                "-C",
                projectDir.toString(),
                "mvn",
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "--jdks-dir",
                tempDir.resolve("jdks").toString(),
                "clean",
                "install",
                "-DskipTests=true",
                "-X");
        assertThat(exit).isEqualTo(0);

        assertThat(tempDir.resolve("tools/maven/3.9.9/bin/mvn")).exists();
        assertThat(Files.readString(argsLog).trim()).isEqualTo("clean install -DskipTests=true -X");
        // Provisioning Maven is not a run of the project: a Maven that recorded no reactor leaves
        // no report behind, and no provisioning row overwrites one.
        assertThat(projectDir.resolve("target/jk-results.md")).doesNotExist();

        String env = Files.readString(envLog);
        assertThat(env).doesNotContain("MAVEN_OPTS=");
        assertThat(env).doesNotContain("JAVA_TOOL_OPTIONS=");
    }

    @Test
    void gradle_passthrough_installs_and_forwards_args(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve("gradle/wrapper"));
        Path argsLog = tempDir.resolve("argv.log");
        Path envLog = tempDir.resolve("env.log");

        byte[] zip = recordingZip("gradle-9.5.1", "gradle", argsLog, envLog);
        maven.served().put("/gradle-9.5.1-bin.zip", zip);
        // Gradle publishes a bare sha256 hex beside every distribution.
        maven.served()
                .put("/gradle-9.5.1-bin.zip.sha256", Hashing.sha256Hex(zip).getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                projectDir.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=" + maven.base().resolve("/gradle-9.5.1-bin.zip") + "\n");

        int exit = run(
                "-C",
                projectDir.toString(),
                "gradle",
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "--jdks-dir",
                tempDir.resolve("jdks").toString(),
                "build",
                "--no-daemon");
        assertThat(exit).isEqualTo(0);

        assertThat(tempDir.resolve("tools/gradle/9.5.1/bin/gradle")).exists();
        assertThat(Files.readString(argsLog).trim()).isEqualTo("build --no-daemon");
    }

    @Test
    void mvn_passthrough_does_not_install_twice(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".mvn/wrapper"));
        Path argsLog = tempDir.resolve("argv.log");
        Path envLog = tempDir.resolve("env.log");

        serveMaven(recordingZip("apache-maven-3.9.9", "mvn", argsLog, envLog));
        Files.writeString(
                projectDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=" + maven.base().resolve("/apache-maven-3.9.9-bin.zip") + "\n");

        run(
                "-C",
                projectDir.toString(),
                "mvn",
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "--jdks-dir",
                tempDir.resolve("jdks").toString(),
                "first");
        long firstMtime = Files.getLastModifiedTime(tempDir.resolve("tools/maven/3.9.9/bin/mvn"))
                .toMillis();

        // Drop the served archive; second invocation must not need it.
        maven.served().clear();
        int exit = run(
                "-C",
                projectDir.toString(),
                "mvn",
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "--jdks-dir",
                tempDir.resolve("jdks").toString(),
                "second");
        assertThat(exit).isEqualTo(0);

        long secondMtime = Files.getLastModifiedTime(tempDir.resolve("tools/maven/3.9.9/bin/mvn"))
                .toMillis();
        assertThat(secondMtime).isEqualTo(firstMtime);
        assertThat(Files.readString(argsLog).trim()).isEqualTo("second");
    }

    /**
     * A wrapper pinned to the 3.6 line, beside which Apache publishes only a {@code .sha1}: the
     * download is verified against it and the {@code downloaded} line says so.
     */
    @Test
    void a_wrapper_pinned_to_maven_3_6_is_verified_against_the_sha1_apache_publishes(@TempDir Path tempDir)
            throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".mvn/wrapper"));
        Path argsLog = tempDir.resolve("argv.log");
        byte[] zip = recordingZip("apache-maven-3.6.3", "mvn", argsLog, tempDir.resolve("env.log"));
        maven.served().put("/apache-maven-3.6.3-bin.zip", zip);
        maven.served()
                .put(
                        "/apache-maven-3.6.3-bin.zip.sha1",
                        Hashing.hashHex("SHA-1", zip).getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                projectDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=" + maven.base().resolve("/apache-maven-3.6.3-bin.zip") + "\n");

        int[] exit = {-1};
        String err = Capture.stderr(() -> exit[0] = run(
                "-C",
                projectDir.toString(),
                "mvn",
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "--jdks-dir",
                tempDir.resolve("jdks").toString(),
                "-v"));
        assertThat(exit[0]).isEqualTo(0);
        assertThat(err).contains("Maven 3.6.3 downloaded · verified against the published .sha1");
        assertThat(tempDir.resolve("tools/maven/3.6.3/bin/mvn")).exists();
        assertThat(Files.readString(argsLog).trim()).isEqualTo("-v");
    }

    /**
     * A distribution with no pin and no published checksum is refused with the flag that accepts
     * it; accepting once installs it, records its digest, and a later provision with no flag is
     * verified against that record.
     */
    @Test
    void an_unverifiable_distribution_is_refused_until_accepted_once(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".mvn/wrapper"));
        Path argsLog = tempDir.resolve("argv.log");
        byte[] zip = recordingZip("apache-maven-3.6.3", "mvn", argsLog, tempDir.resolve("env.log"));
        // The stub answers <path>.sha1 for anything it serves, as a Maven repository does; this
        // impersonates a mirror that publishes nothing beside the archive.
        maven.withoutChecksums().served().put("/apache-maven-3.6.3-bin.zip", zip);
        Files.writeString(
                projectDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=" + maven.base().resolve("/apache-maven-3.6.3-bin.zip") + "\n");
        String[] common = {
            "-C",
            projectDir.toString(),
            "mvn",
            "--tools-dir",
            tempDir.resolve("tools").toString(),
            "--no-discover",
            "--jdks-dir",
            tempDir.resolve("jdks").toString()
        };

        int[] exit = {-1};
        String refused = Capture.stderr(() -> exit[0] = run(with(common, "-v")));
        assertThat(exit[0]).isNotEqualTo(0);
        assertThat(refused)
                .contains("cannot be verified")
                .contains("no .sha512 or .sha1 checksum")
                .contains("--accept-unverified-tool");
        assertThat(tempDir.resolve("tools/maven/3.6.3")).doesNotExist();

        String accepted = Capture.stderr(() -> exit[0] = run(with(common, "--accept-unverified-tool", "-v")));
        assertThat(exit[0]).isEqualTo(0);
        assertThat(accepted)
                .contains("Maven 3.6.3 downloaded · accepted with --accept-unverified-tool, sha256 recorded");
        assertThat(Files.readString(argsLog).trim()).isEqualTo("-v");
        Path record = tempDir.resolve("tools/maven/3.6.3.accepted.sha256");
        assertThat(record).content().startsWith(Hashing.sha256Hex(zip));

        // The install is gone but the acceptance stays: the re-download needs no flag.
        PathUtil.deleteRecursively(tempDir.resolve("tools/maven/3.6.3"));
        String again = Capture.stderr(() -> exit[0] = run(with(common, "-v")));
        assertThat(exit[0]).isEqualTo(0);
        assertThat(again).contains("Maven 3.6.3 downloaded · verified against the digest accepted earlier");
    }

    private static String[] with(String[] common, String... tail) {
        String[] out = new String[common.length + tail.length];
        System.arraycopy(common, 0, out, 0, common.length);
        System.arraycopy(tail, 0, out, common.length, tail.length);
        return out;
    }

    /**
     * Serve a Maven distribution the way Central does: the archive, and beside it the sha512sum
     * form of its digest. An unverifiable archive is refused before it is downloaded.
     */
    private void serveMaven(byte[] zip) {
        maven.served().put("/apache-maven-3.9.9-bin.zip", zip);
        maven.served()
                .put(
                        "/apache-maven-3.9.9-bin.zip.sha512",
                        (Hashing.hashHex("SHA-512", zip) + "  apache-maven-3.9.9-bin.zip\n")
                                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A zip carrying a single shell launcher that writes its argv to one file and its env to another.
     * Used as a synthetic mvn/gradle distribution.
     */
    private static byte[] recordingZip(String topDir, String binaryName, Path argsLog, Path envLog) throws IOException {
        String script = "#!/usr/bin/env bash\n"
                + "echo \"$@\" > "
                + shellQuote(argsLog.toString())
                + "\n"
                + "env > "
                + shellQuote(envLog.toString())
                + "\n"
                + "exit 0\n";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(topDir + "/bin/" + binaryName));
            zos.write(script.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
