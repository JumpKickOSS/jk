// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.host.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk mvn} against a Maven-only reactor whose "Maven" is a script that leaves behind what the
 * event spy and surefire would: the events file named by {@code -Djk.mvn.events} and one module's
 * {@code TEST-*.xml}. Proves the client → engine → journal path without a network; the real Maven
 * run is {@link MvnRealResultsTest}.
 */
@DisabledOnOs(OS.WINDOWS) // launcher scripts are .sh-only.
@Tag("integration")
class MvnResultsCommandTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @TempDir
    Path tempDir;

    private @Nullable String previousSpy;

    @BeforeEach
    void pinSpyJar() throws IOException {
        previousSpy = System.getProperty("jk.maven-spy.jar");
        // Any file will do: the scripted Maven never loads it, and its presence is what attaches the spy.
        Path spy = Files.createFile(tempDir.resolve("jk-maven-spy.jar"));
        System.setProperty("jk.maven-spy.jar", spy.toString());
    }

    @AfterEach
    void restoreSpyJar() {
        if (previousSpy == null) System.clearProperty("jk.maven-spy.jar");
        else System.setProperty("jk.maven-spy.jar", previousSpy);
    }

    @Test
    void a_failed_reactor_run_writes_jk_results_that_jk_results_prints() throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".mvn/wrapper"));
        Files.writeString(
                projectDir.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>reactor</artifactId><version>1.0</version></project>\n");
        Path argsLog = tempDir.resolve("argv.log");
        serveMaven(scriptedZip("apache-maven-3.9.9", argsLog));
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
                "test");
        assertThat(exit).isEqualTo(1);

        String argv = Files.readString(argsLog).trim();
        assertThat(argv).startsWith("-Djk.mvn.events=");
        assertThat(argv).contains("-Dmaven.ext.class.path=" + tempDir.resolve("jk-maven-spy.jar"));
        assertThat(argv).endsWith(" test");

        Path results = projectDir.resolve("target").resolve("jk-results.md");
        assertThat(results).exists();
        String md = Files.readString(results);
        assertThat(md).startsWith("# jk results — FAIL\n\n**FAIL** · `com.example:reactor` · #1");
        assertThat(md).contains("trigger: cli · tool: mvn");
        assertThat(md).contains("## Tests");
        assertThat(md).contains("#### com.example.AppTest\n");
        assertThat(md).contains("##### `adds`");
        assertThat(md).contains("expected: <4> but was: <5>");
        assertThat(md).contains("at com.example.AppTest.adds(AppTest.java:12)");
        assertThat(md).contains("## Modules");
        assertThat(md).contains("| com.example:app | FAIL |");
        assertThat(md).contains("| com.example:lib | OK |");

        String printed = Capture.stdout(() -> run("-C", projectDir.toString(), "results"));
        assertThat(printed).isEqualTo(md);

        // The Maven run counts as a build: numbered, and listed with its outcome and tool.
        String history = Capture.stdout(() -> run("--no-ansi", "-C", projectDir.toString(), "history", "list"));
        assertThat(history).contains("com.example:reactor").contains("mvn").contains("1 failed");
    }

    private void serveMaven(byte[] zip) {
        maven.served().put("/apache-maven-3.9.9-bin.zip", zip);
        maven.served()
                .put(
                        "/apache-maven-3.9.9-bin.zip.sha512",
                        (Hashing.hashHex("SHA-512", zip) + "  apache-maven-3.9.9-bin.zip\n")
                                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A "Maven" that records its argv, then writes the reactor's events (lib ok, app failed in
     * surefire) and app's surefire XML, and exits 1 the way a failed build does.
     */
    private static byte[] scriptedZip(String topDir, Path argsLog) throws IOException {
        String script = "#!/usr/bin/env bash\n"
                + "echo \"$@\" > " + shellQuote(argsLog.toString()) + "\n"
                + "for a in \"$@\"; do case \"$a\" in -Djk.mvn.events=*) EVENTS=\"${a#-Djk.mvn.events=}\";; esac; done\n"
                + "L=\"$PWD/lib\"; A=\"$PWD/app\"\n"
                + "mkdir -p \"$L\" \"$A/target/surefire-reports\"\n"
                + "cat > \"$EVENTS\" <<EOF\n"
                + events()
                + "EOF\n"
                + "cat > \"$A/target/surefire-reports/TEST-com.example.AppTest.xml\" <<'EOF'\n"
                + surefireXml()
                + "EOF\n"
                + "exit 1\n";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(topDir + "/bin/mvn"));
            zos.write(script.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    /**
     * The event lines as the spy writes them — eight percent-encoded fields per line, tabs
     * between — with the module dirs left to the script's {@code $L} / {@code $A}.
     */
    private static String events() {
        String t = "\t";
        return String.join(
                "\n",
                "SessionStarted" + t + "0" + t + t + t + t + t + t,
                "ProjectStarted" + t + "0" + t + "com.example%3Alib" + t + "$L" + t + t + t + t,
                "MojoSucceeded" + t + "0" + t + "com.example%3Alib" + t + "$L" + t + "maven-compiler-plugin%3Acompile"
                        + t + "default-compile" + t + t,
                "ProjectSucceeded" + t + "400" + t + "com.example%3Alib" + t + "$L" + t + t + t + t,
                "ProjectStarted" + t + "0" + t + "com.example%3Aapp" + t + "$A" + t + t + t + t,
                "MojoFailed" + t + "0" + t + "com.example%3Aapp" + t + "$A" + t + "maven-surefire-plugin%3Atest" + t
                        + "default-test" + t + "org.apache.maven.plugin.MojoFailureException" + t
                        + "There+are+test+failures.",
                "ProjectFailed" + t + "1200" + t + "com.example%3Aapp" + t + "$A" + t + t + t + t,
                "SessionEnded" + t + "0" + t + t + t + t + t + t,
                "");
    }

    private static String surefireXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"com.example.AppTest\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\" time=\"0.05\">\n"
                + "  <testcase name=\"greets\" classname=\"com.example.AppTest\" time=\"0.01\"/>\n"
                + "  <testcase name=\"adds\" classname=\"com.example.AppTest\" time=\"0.04\">\n"
                + "    <failure message=\"expected: &lt;4&gt; but was: &lt;5&gt;\" type=\"org.opentest4j.AssertionFailedError\">"
                + "org.opentest4j.AssertionFailedError: expected: &lt;4&gt; but was: &lt;5&gt;\n"
                + "\tat com.example.AppTest.adds(AppTest.java:12)\n"
                + "</failure>\n"
                + "  </testcase>\n"
                + "</testsuite>\n";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
