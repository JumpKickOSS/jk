// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.Hashing;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Maven 4 {@code gpg-agent:} master source against a real {@code gpg-agent}: a private one, in a
 * scratch {@code GNUPGHOME} this test creates and stops, with the master passphrase preset under
 * Maven's cache id. It starts a process of this machine's GnuPG, so it runs only in the {@code
 * live} tier, whose profile sets {@code JK_TEST_GPG_AGENT=1}: {@code jk test -p live} with GnuPG
 * 2.1+ installed. The user's own {@code ~/.gnupg} and agent are never touched.
 *
 * <p>Linux and macOS: the agent listens on a Unix socket. On Windows Gpg4win's agent socket is
 * libassuan's emulation file, which is not one jk reads; the test is skipped there.
 */
@Tag("live")
class MvnencGpgAgentLiveTest {

    private static final Duration STEP = Duration.ofSeconds(20);

    @Test
    void a_preset_passphrase_in_a_real_agent_decrypts_and_an_empty_cache_is_reported_without_a_prompt(@TempDir Path dir)
            throws Exception {
        assumeTrue("1".equals(System.getenv("JK_TEST_GPG_AGENT")), "opt-in: jk test -p live");
        assumeTrue(
                !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                "Gpg4win's agent socket is an emulation file, not a Unix socket");
        assumeTrue(run(dir, List.of("gpgconf", "--version")).exit() == 0, "no GnuPG on PATH");

        Path gnupg = Files.createDirectory(
                dir.resolve("gnupg"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(gnupg.resolve("gpg-agent.conf"), "allow-preset-passphrase\n");
        try {
            Result launched = run(gnupg, List.of("gpgconf", "--homedir", gnupg.toString(), "--launch", "gpg-agent"));
            assertThat(launched.exit()).as(launched.output()).isZero();
            Path socket =
                    Path.of(run(gnupg, List.of("gpgconf", "--homedir", gnupg.toString(), "--list-dirs", "agent-socket"))
                            .output()
                            .strip());
            String hex = Hashing.hex("jk-fixture-gpg-master".getBytes(StandardCharsets.UTF_8))
                    .toUpperCase(Locale.ROOT);
            Result preset = agent(gnupg, "PRESET_PASSPHRASE " + MvnencPasswords.GPG_CACHE_ID + " -1 " + hex);
            assertThat(preset.output()).as("the agent took the preset").contains("OK");

            Path home = home(dir, "gpg-agent:" + socket);
            MavenSettings settings = read(dir, home);
            assertThat(settings.warnings()).isEmpty();
            assertThat(settings.server("nexus").map(MavenSettings.Server::password))
                    .contains("jk-fixture-gpg-secret");

            agent(gnupg, "CLEAR_PASSPHRASE " + MvnencPasswords.GPG_CACHE_ID);
            long started = System.nanoTime();
            MavenSettings cleared = read(dir, home);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("--no-ask: the agent answered at once instead of starting a pinentry")
                    .isLessThan(Duration.ofSeconds(5));
            assertThat(cleared.server("nexus")).isEmpty();
            assertThat(cleared.warnings()).singleElement().asString().contains("has no cached master password");
        } finally {
            run(gnupg, List.of("gpgconf", "--homedir", gnupg.toString(), "--kill", "gpg-agent"));
        }
    }

    private static Result agent(Path gnupg, String command) throws IOException, InterruptedException {
        return run(gnupg, List.of("gpg-connect-agent", "--homedir", gnupg.toString(), command, "/bye"));
    }

    /** {@code home/.m2/settings-security4.xml} with the master dispatcher reading {@code source}. */
    private static Path home(Path dir, String source) throws IOException {
        Path m2 = Files.createDirectories(dir.resolve("home/.m2"));
        Files.writeString(m2.resolve(MvnencPasswords.SECURITY_FILE), MvnencPasswordsTest.security4(source));
        return dir.resolve("home");
    }

    private static MavenSettings read(Path dir, Path home) throws IOException {
        Path xml = dir.resolve("settings.xml");
        Files.writeString(xml, """
                <settings>
                  <servers>
                    <server><id>nexus</id><username>deployer</username><password>%s</password></server>
                  </servers>
                </settings>
                """.formatted(MvnencPasswordsTest.GPG_SECRET));
        SettingsValues values = new SettingsValues(
                Map.<String, String>of()::get,
                name -> name.equals("user.home") ? home.toString() : System.getProperty(name));
        return SettingsXml.read(SettingsXml.parse(xml), values);
    }

    private record Result(int exit, String output) {}

    /**
     * {@code argv} with {@code GNUPGHOME} pointing at the scratch home, bounded by {@link #STEP}.
     * Output goes to a file, not a pipe: {@code gpgconf --launch} leaves a daemon behind, and a
     * daemon holding the pipe would keep a read open after the command itself has ended.
     */
    private static Result run(Path gnupg, List<String> argv) throws IOException, InterruptedException {
        Path log = Files.createTempFile(gnupg.getParent(), "gpg-", ".log");
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(argv))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        pb.environment().put("GNUPGHOME", gnupg.toString());
        Process p;
        try {
            p = pb.start();
        } catch (IOException notInstalled) {
            return new Result(127, String.valueOf(notInstalled.getMessage()));
        }
        if (!p.waitFor(STEP.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            return new Result(124, Files.readString(log));
        }
        return new Result(p.exitValue(), Files.readString(log));
    }
}
