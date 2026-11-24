// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.KeyContract;
import cc.jumpkick.test.CoverageAgent;
import cc.jumpkick.test.EnvProbeJar;
import cc.jumpkick.test.SuiteReads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run-tests stamp keys the agents and the recording a test JVM starts with by value: the flags as
 * the functions the launch spells them with write them over stand-ins, and the manifest the probe's
 * jar is cut under. So an edit to what one spells moves the key without the suite code's digest, and
 * a path moves nothing.
 */
class JvmAgentsTokenTest {

    @AfterEach
    void forget() {
        KeyContract.SUITE.overrideDigestForTests(null);
    }

    @Test
    void the_token_spells_what_the_coverage_agent_starts_with(@TempDir Path tmp) {
        Path jar = tmp.resolve("jacoco-agent.jar");
        Path exec = tmp.resolve("jacoco.exec");
        String launched = new CoverageAgent(jar, exec).agentArg();

        assertThat(TestStampInputs.agentsToken())
                .contains(launched.replace(jar.toString(), "@jacoco-agent").replace(exec.toString(), "@exec"));
    }

    @Test
    void the_token_spells_what_the_recording_starts_with(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("reads");
        String launched = SuiteReads.prepare(dir);

        assertThat(TestStampInputs.agentsToken())
                .contains(launched.replace(dir.toString(), "@recording").replace('\\', '/'));
    }

    @Test
    void the_token_spells_what_the_probe_starts_with_and_the_manifest_it_is_cut_under(@TempDir Path tmp)
            throws Exception {
        Path runner = runnerJar(tmp);
        Path records = tmp.resolve("records").toAbsolutePath();
        Path ws = tmp.resolve("ws").toAbsolutePath().normalize();
        String plain = EnvProbeJar.flag(runner, tmp.resolve("cache"), records);
        String marking = EnvProbeJar.flag(runner, tmp.resolve("cache"), records, ws);
        String jar = plain.substring("-javaagent:".length(), plain.indexOf('='));
        String token = TestStampInputs.agentsToken();

        assertThat(token)
                .contains(plain.replace(jar, "@probe").replace(records.toString(), "@record"))
                .contains(marking.replace(jar, "@probe")
                        .replace(records.toString(), "@record")
                        .replace(ws.toString().length() + ":" + ws, "10:@workspace"));
        try (JarFile cut = new JarFile(jar)) {
            cut.getManifest().getMainAttributes().forEach((name, value) -> assertThat(token)
                    .contains(name + ": " + (name.toString().equals("Boot-Class-Path") ? "@probe" : value)));
        }
    }

    @Test
    void paths_are_not_in_the_token() {
        assertThat(TestStampInputs.agentsToken())
                .doesNotContain(System.getProperty("user.home"))
                .doesNotContain(System.getProperty("java.io.tmpdir"))
                .doesNotContain("\\");
    }

    /** The suite code's digest moving leaves the token as it was: it names no code, only what the code spells. */
    @Test
    void the_token_names_no_code() {
        KeyContract.SUITE.overrideDigestForTests("suites000001");
        String one = TestStampInputs.agentsToken();
        KeyContract.SUITE.overrideDigestForTests("suites000002");

        assertThat(TestStampInputs.agentsToken()).isEqualTo(one);
    }

    @Test
    void the_stamp_carries_the_token(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("m");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """);
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));

        List<String> extras = TestStampInputs.of(TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .extras();

        assertThat(extras.stream().filter(token -> token.startsWith("jvm-agents:")))
                .containsExactly(TestStampInputs.agentsToken());
    }

    private static Path runnerJar(Path tmp) throws Exception {
        Path runner = tmp.resolve("runner.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(runner))) {
            out.putNextEntry(new JarEntry("cc/jumpkick/testrunner/env/EnvProbe.class"));
            out.write(new byte[] {(byte) 0xca, (byte) 0xfe});
            out.closeEntry();
        }
        return runner;
    }
}
