// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.StubReleaseDirectory;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Invocation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release install running {@code jk mvn} for the first time: no spy jar anywhere, a stubbed
 * release directory that serves this version's, and a scripted Maven that leaves what the spy
 * would. The run ends with the jar under the product library, attached to Maven's extension path,
 * and {@code target/jk-results.md} written through the engine.
 */
@DisabledOnOs(OS.WINDOWS) // launcher scripts are .sh-only.
@Tag("integration")
class MvnSpyFetchCommandTest {

    private static final String JAR_NAME = "jk-maven-spy-" + JkVersion.VERSION + ".jar";
    private static final byte[] JAR = "fake spy jar bytes".getBytes(StandardCharsets.UTF_8);

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @TempDir
    Path tempDir;

    private StubReleaseDirectory release;

    @BeforeEach
    void start() throws IOException {
        release = new StubReleaseDirectory(JkVersion.VERSION);
        release.put(JAR_NAME, JAR);
    }

    @AfterEach
    void stop() {
        release.close();
    }

    @Test
    void the_first_jk_mvn_fetches_the_spy_attaches_it_and_writes_jk_results() throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".mvn/wrapper"));
        Files.writeString(
                projectDir.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>app</artifactId></project>\n");
        Path argsLog = tempDir.resolve("argv.log");
        byte[] zip = scriptedZip(argsLog);
        maven.served().put("/apache-maven-3.9.9-bin.zip", zip);
        maven.served()
                .put(
                        "/apache-maven-3.9.9-bin.zip.sha512",
                        (Hashing.hashHex("SHA-512", zip) + "  apache-maven-3.9.9-bin.zip\n")
                                .getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                projectDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=" + maven.base().resolve("/apache-maven-3.9.9-bin.zip") + "\n");

        Path lib = tempDir.resolve("home").resolve("lib");
        MavenSpyJar spy = new MavenSpyJar(
                null,
                JkVersion.VERSION,
                lib,
                List.of(),
                new MavenSpyJar.Release(release.base(), release.verifier(), true));
        Invocation in = Invocation.builder()
                .putValue("dir", projectDir.toString())
                .putValue("tools-dir", tempDir.resolve("tools").toString())
                .putValue("jdks-dir", tempDir.resolve("jdks").toString())
                .flag("no-discover", true)
                .addPositional("test")
                .build();

        int exit = new MvnCommand(spy).run(in);

        assertThat(exit).isEqualTo(0);
        Path fetched = lib.resolve(JAR_NAME);
        assertThat(fetched).hasBinaryContent(JAR);
        assertThat(release.requested()).containsExactly("SHA256SUMS", "SHA256SUMS.sig", JAR_NAME);
        String argv = Files.readString(argsLog).trim();
        assertThat(argv).startsWith("-Djk.mvn.events=");
        assertThat(argv).contains("-Dmaven.ext.class.path=" + fetched);
        assertThat(argv).endsWith(" test");

        Path results = projectDir.resolve("target").resolve("jk-results.md");
        assertThat(results).exists();
        String md = Files.readString(results);
        assertThat(md).startsWith("# jk results — OK\n\n**OK** · `com.example:app`");
        assertThat(md).contains("trigger: cli · tool: mvn");
    }

    /** A "Maven" that records its argv and writes one module's events as the spy would, then exits 0. */
    private static byte[] scriptedZip(Path argsLog) throws IOException {
        String t = "\t";
        String events = String.join(
                "\n",
                "SessionStarted" + t + "0" + t + t + t + t + t + t,
                "ProjectStarted" + t + "0" + t + "com.example%3Aapp" + t + "$PWD" + t + t + t + t,
                "MojoSucceeded" + t + "0" + t + "com.example%3Aapp" + t + "$PWD" + t + "maven-compiler-plugin%3Acompile"
                        + t + "default-compile" + t + t,
                "ProjectSucceeded" + t + "300" + t + "com.example%3Aapp" + t + "$PWD" + t + t + t + t,
                "SessionEnded" + t + "0" + t + t + t + t + t + t,
                "");
        String script = "#!/usr/bin/env bash\n"
                + "echo \"$@\" > '" + argsLog + "'\n"
                + "for a in \"$@\"; do case \"$a\" in -Djk.mvn.events=*) EVENTS=\"${a#-Djk.mvn.events=}\";; esac; done\n"
                + "cat > \"$EVENTS\" <<EOF\n" + events + "EOF\n"
                + "exit 0\n";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("apache-maven-3.9.9/bin/mvn"));
            zos.write(script.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
