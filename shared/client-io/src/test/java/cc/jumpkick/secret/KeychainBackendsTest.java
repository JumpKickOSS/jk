// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The three OS backends over their command lines: what each runs, sends, and makes of the answer. */
class KeychainBackendsTest {

    /** Records every command and answers from a queue. */
    static final class Scripted implements SecretCommand {
        final List<List<String>> argv = new ArrayList<>();
        final List<@Nullable String> stdin = new ArrayList<>();
        final Deque<Object> answers = new ArrayDeque<>();

        Scripted answer(int exit, String out, String err) {
            answers.add(new Result(exit, out, err));
            return this;
        }

        Scripted failing(IOException e) {
            answers.add(e);
            return this;
        }

        @Override
        public Result run(List<String> command, byte @Nullable [] in, Duration cap) throws IOException {
            argv.add(command);
            stdin.add(in == null ? null : new String(in, StandardCharsets.UTF_8));
            Object next = answers.removeFirst();
            if (next instanceof IOException e) throw e;
            return (Result) next;
        }
    }

    /** A desktop session: a session bus, and secret-tool, busctl and gdbus in /usr/bin. */
    static HostSession desktop(String... tools) {
        Set<Path> present = new HashSet<>();
        for (String tool : tools.length == 0 ? new String[] {"secret-tool", "busctl", "gdbus"} : tools) {
            present.add(Path.of("/usr/bin", tool));
        }
        Map<String, String> env =
                Map.of("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus", "PATH", "/usr/bin");
        return new HostSession(env::get, present::contains);
    }

    static SecretServiceBackend secretService(SecretCommand cmd) {
        return new SecretServiceBackend(cmd, desktop());
    }

    // ---- Secret Service (secret-tool) ----

    @Test
    void secret_tool_stores_the_secret_on_stdin_under_service_and_account_once_the_keyring_is_unlocked() {
        Scripted cmd = new Scripted().answer(0, "b false\n", "").answer(0, "", "");
        secretService(cmd).write("repo/nexus", "jk repo login nexus", "s3cret");

        assertThat(cmd.argv.get(0)).isEqualTo(SecretServiceBackend.BUSCTL);
        assertThat(cmd.argv.get(1))
                .containsExactly(
                        "secret-tool",
                        "store",
                        "--label=jk repo login nexus",
                        "service",
                        "jk",
                        "account",
                        "repo/nexus");
        assertThat(cmd.stdin.get(1)).isEqualTo("s3cret");
    }

    @Test
    void secret_tool_lookup_reads_the_secret_and_tells_absent_from_refused() {
        Scripted cmd = new Scripted()
                .answer(0, "b false", "")
                .answer(0, "s3cret", "")
                .answer(0, "b false", "")
                .answer(1, "", "")
                .answer(0, "b false", "")
                .answer(1, "", "secret-tool: Cannot autolaunch D-Bus without X11 $DISPLAY\n");
        SecretServiceBackend backend = secretService(cmd);

        assertThat(backend.read("repo/nexus")).contains("s3cret");
        assertThat(cmd.argv.get(1)).containsExactly("secret-tool", "lookup", "service", "jk", "account", "repo/nexus");
        assertThat(backend.read("repo/nexus")).isEmpty();
        assertThatThrownBy(() -> backend.read("repo/nexus"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("Cannot autolaunch D-Bus");
    }

    @Test
    void a_locked_keyring_refuses_every_call_without_running_secret_tool() {
        Scripted cmd = new Scripted()
                .answer(0, "b true\n", "")
                .answer(0, "b true\n", "")
                .answer(0, "b true\n", "");
        SecretServiceBackend backend = secretService(cmd);

        assertThatThrownBy(() -> backend.write("repo/nexus", "l", "s"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("locked");
        assertThatThrownBy(() -> backend.read("repo/nexus")).hasMessageContaining("locked");
        assertThatThrownBy(() -> backend.delete("repo/nexus")).hasMessageContaining("locked");
        assertThat(cmd.argv).allSatisfy(argv -> assertThat(argv).isEqualTo(SecretServiceBackend.BUSCTL));
    }

    @Test
    void the_probe_reads_the_default_collections_locked_property_and_tells_absent_from_refused() {
        assertThat(secretService(new Scripted().answer(0, "b false\n", "")).probe())
                .isEqualTo(SecretBackend.Probe.AVAILABLE);

        SecretBackend.Probe locked =
                secretService(new Scripted().answer(0, "b true\n", "")).probe();
        assertThat(locked.refused()).isTrue();
        assertThat(locked.reason()).contains("locked");

        SecretBackend.Probe noCollection = secretService(new Scripted()
                        .answer(
                                1,
                                "",
                                "Failed to get property Locked on interface org.freedesktop.Secret.Collection: Object"
                                        + " does not exist at path \"/org/freedesktop/secrets/aliases/default\""))
                .probe();
        assertThat(noCollection.refused()).isTrue();
        assertThat(noCollection.reason()).contains("no default collection");

        SecretBackend.Probe noService = secretService(new Scripted()
                        .answer(
                                1,
                                "",
                                "Failed to get property Locked on interface org.freedesktop.Secret.Collection: The"
                                        + " name org.freedesktop.secrets was not provided by any .service files"))
                .probe();
        assertThat(noService.available()).isFalse();
        assertThat(noService.refused()).isFalse();

        SecretBackend.Probe busGone = secretService(
                        new Scripted().answer(1, "", "Failed to connect to bus: No such file or directory"))
                .probe();
        assertThat(busGone.refused()).isFalse();
    }

    @Test
    void gdbus_answers_the_probe_when_busctl_is_not_installed() {
        Scripted cmd = new Scripted().answer(0, "(<true>,)\n", "");
        SecretBackend.Probe probe = new SecretServiceBackend(cmd, desktop("secret-tool", "gdbus")).probe();

        assertThat(cmd.argv.get(0)).isEqualTo(SecretServiceBackend.GDBUS);
        assertThat(probe.refused()).isTrue();
        assertThat(SecretServiceBackend.lockAnswer(new SecretCommand.Result(0, "(<false>,)\n", "")))
                .isEqualTo(SecretBackend.Probe.AVAILABLE);
        assertThat(SecretServiceBackend.lockAnswer(new SecretCommand.Result(
                                1, "", "Error connecting: Could not connect: No such file or directory"))
                        .refused())
                .isFalse();
    }

    @Test
    void with_no_way_to_read_the_lock_the_probe_refuses_rather_than_risk_a_prompt() {
        Scripted cmd = new Scripted();
        SecretBackend.Probe probe = new SecretServiceBackend(cmd, desktop("secret-tool")).probe();

        assertThat(probe.refused()).isTrue();
        assertThat(probe.reason()).contains("busctl").contains("gdbus");
        assertThat(cmd.argv).isEmpty();
    }

    @Test
    void with_no_session_bus_or_no_secret_tool_the_probe_starts_nothing_and_is_not_a_refusal() {
        Scripted cmd = new Scripted();
        SecretBackend.Probe noBus =
                new SecretServiceBackend(cmd, new HostSession(Map.of("PATH", "/usr/bin")::get, path -> true)).probe();
        assertThat(noBus.available()).isFalse();
        assertThat(noBus.refused()).isFalse();
        assertThat(noBus.reason()).isEqualTo("no D-Bus session bus");

        SecretBackend.Probe noTool = new SecretServiceBackend(cmd, desktop("busctl")).probe();
        assertThat(noTool.refused()).isFalse();
        assertThat(noTool.reason()).isEqualTo("secret-tool is not installed");
        assertThat(cmd.argv).isEmpty();

        assertThatThrownBy(() -> new SecretServiceBackend(cmd, HostSession.desktop()).read("repo/nexus"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("no D-Bus session bus");
        assertThat(cmd.argv).isEmpty();
    }

    @Test
    void a_probe_that_outlives_its_cap_is_a_refusal() {
        SecretBackend.Probe slow = secretService(
                        new Scripted().failing(new IOException("busctl did not answer within 1s")))
                .probe();
        assertThat(slow.refused()).isTrue();
        assertThat(slow.reason()).contains("did not answer");
    }

    @Test
    void secret_tool_clear_treats_an_absent_item_as_removed() {
        Scripted cmd = new Scripted().answer(0, "b false", "").answer(1, "", "");
        secretService(cmd).delete("repo/nexus");
        assertThat(cmd.argv.get(1)).containsExactly("secret-tool", "clear", "service", "jk", "account", "repo/nexus");
    }

    // ---- macOS Keychain (security) ----

    /** The backend where the lock status cannot be read in process, so {@code security} is the probe. */
    private static MacKeychainBackend viaCli(SecretCommand cmd) {
        return new MacKeychainBackend(cmd, MacKeychainLock.UNKNOWN);
    }

    private static MacKeychainLock lockSays(MacKeychainLock.State state) {
        return () -> new MacKeychainLock.Status(
                state,
                state == MacKeychainLock.State.UNLOCKED
                        ? null
                        : "the default keychain is " + state.name().toLowerCase(Locale.ROOT));
    }

    @Test
    void an_unlocked_status_lets_security_run_with_no_probe_of_its_own() {
        Scripted cmd = new Scripted().answer(0, "s3cret\n", "").answer(0, "", "");
        MacKeychainBackend backend = new MacKeychainBackend(cmd, lockSays(MacKeychainLock.State.UNLOCKED));

        assertThat(backend.probe().available()).isTrue();
        assertThat(backend.read("repo/nexus")).contains("s3cret");
        backend.write("repo/nexus", "l", "s3cret");
        assertThat(cmd.argv).hasSize(2);
        assertThat(cmd.argv.get(0)).contains("find-generic-password");
        assertThat(cmd.argv.get(1)).containsExactly("/usr/bin/security", "-i");
    }

    @Test
    void a_locked_status_is_a_refusal_before_security_runs_so_a_locked_read_is_never_absent() {
        Scripted cmd = new Scripted();
        MacKeychainBackend backend = new MacKeychainBackend(cmd, lockSays(MacKeychainLock.State.LOCKED));

        SecretBackend.Probe probe = backend.probe();
        assertThat(probe.available()).isFalse();
        assertThat(probe.refused()).isTrue();
        assertThat(probe.reason()).contains("locked");
        assertThatThrownBy(() -> backend.read("repo/nexus"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("refused to read repo/nexus")
                .hasMessageContaining("locked");
        assertThatThrownBy(() -> backend.write("repo/nexus", "l", "s")).hasMessageContaining("refused to store");
        assertThatThrownBy(() -> backend.delete("repo/nexus")).hasMessageContaining("refused to remove");
        assertThat(cmd.argv).isEmpty();
    }

    @Test
    void no_default_keychain_is_no_keychain_here_rather_than_a_refusal() {
        SecretBackend.Probe probe =
                new MacKeychainBackend(new Scripted(), lockSays(MacKeychainLock.State.NO_KEYCHAIN)).probe();
        assertThat(probe.available()).isFalse();
        assertThat(probe.refused()).isFalse();
    }

    @Test
    void off_macos_the_lock_status_is_unknown_and_names_why() {
        assumeFalse(Os.isDarwin(), "off macOS only");
        MacKeychainLock.Status status = MacKeychainLock.HOST.status();
        assertThat(status.state()).isEqualTo(MacKeychainLock.State.UNKNOWN);
        assertThat(status.reason()).contains("only on macOS");
    }

    private static final String UNLOCKED =
            "Keychain \"/Users/u/Library/Keychains/login.keychain-db\" lock-on-sleep timeout=21600s\n";

    @Test
    void security_adds_through_its_interactive_mode_so_the_secret_is_never_an_argument() {
        Scripted cmd = new Scripted().answer(0, UNLOCKED, "").answer(0, "", "");
        viaCli(cmd).write("repo/nexus", "ignored label", "p@ss w0rd\"");

        assertThat(cmd.argv.get(0)).containsExactly("/usr/bin/security", "show-keychain-info");
        assertThat(cmd.argv.get(1)).containsExactly("/usr/bin/security", "-i");
        String hex = HexFormat.of().formatHex("p@ss w0rd\"".getBytes(StandardCharsets.UTF_8));
        assertThat(cmd.stdin.get(1))
                .isEqualTo("add-generic-password -U -s jk -a repo/nexus -l jk:repo/nexus -X " + hex + "\n");
        assertThat(String.join(" ", cmd.argv.get(1))).doesNotContain(hex);
    }

    @Test
    void security_interactive_mode_reports_a_failure_on_stderr_with_exit_zero() {
        Scripted cmd = new Scripted()
                .answer(0, UNLOCKED, "")
                .answer(0, "", "SecKeychainItemCreateFromContent: User interaction is not allowed.");
        assertThatThrownBy(() -> viaCli(cmd).write("repo/nexus", "l", "s"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("locked and this session cannot unlock it")
                .hasMessageContaining("User interaction is not allowed");
    }

    @Test
    void security_find_reads_the_password_44_means_absent_and_51_is_a_denial() {
        Scripted cmd = new Scripted()
                .answer(0, UNLOCKED, "")
                .answer(0, "s3cret\n", "")
                .answer(0, UNLOCKED, "")
                .answer(44, "", "security: SecKeychainSearchCopyNext: The specified item could not be found.")
                .answer(0, UNLOCKED, "")
                .answer(
                        51,
                        "",
                        "security: SecKeychainSearchCopyNext: The user name or passphrase you entered is not correct.");
        MacKeychainBackend backend = viaCli(cmd);

        assertThat(backend.read("repo/nexus")).contains("s3cret");
        assertThat(cmd.argv.get(1))
                .containsExactly("/usr/bin/security", "find-generic-password", "-s", "jk", "-a", "repo/nexus", "-w");
        assertThat(backend.read("repo/nexus")).isEmpty();
        assertThatThrownBy(() -> backend.read("repo/nexus"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("denied access")
                .hasMessageContaining("passphrase you entered is not correct");
    }

    @Test
    void over_ssh_a_locked_login_keychain_is_no_keychain_and_no_item_is_asked_for() {
        String said = "security: SecKeychainCopySettings /Users/u/Library/Keychains/login.keychain-db: User"
                + " interaction is not allowed.";
        Scripted cmd = new Scripted().answer(36, "", said).answer(36, "", said).answer(36, "", said);
        MacKeychainBackend backend = viaCli(cmd);

        SecretBackend.Probe probe = backend.probe();
        assertThat(probe.available()).isFalse();
        assertThat(probe.refused()).isFalse();
        assertThat(probe.reason()).contains("no window server");
        assertThatThrownBy(() -> backend.read("repo/nexus"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("no window server");
        assertThatThrownBy(() -> backend.write("repo/nexus", "l", "s")).hasMessageContaining("no window server");
        assertThat(cmd.argv)
                .allSatisfy(argv -> assertThat(argv).containsExactly("/usr/bin/security", "show-keychain-info"));
    }

    @Test
    void the_interaction_refusal_is_recognised_by_its_words_as_well_as_its_status() {
        assertThat(MacKeychainBackend.noWindowServer(
                        new SecretCommand.Result(1, "", "SecKeychainCopySettings: User interaction is not allowed.")))
                .isTrue();
        assertThat(MacKeychainBackend.noWindowServer(new SecretCommand.Result(36, "", "")))
                .isTrue();
        assertThat(MacKeychainBackend.noWindowServer(new SecretCommand.Result(51, "", "denied")))
                .isFalse();

        SecretBackend.Probe denied =
                viaCli(new Scripted().answer(51, "", "security: denied")).probe();
        assertThat(denied.refused()).isTrue();
        assertThat(denied.reason()).contains("denied access");
    }

    @Test
    void security_delete_treats_44_as_removed() {
        Scripted cmd = new Scripted().answer(0, UNLOCKED, "").answer(44, "", "could not be found");
        viaCli(cmd).delete("forge/github.com");
        assertThat(cmd.argv.get(1))
                .containsExactly("/usr/bin/security", "delete-generic-password", "-s", "jk", "-a", "forge/github.com");
    }

    @Test
    void every_backend_names_itself_the_way_records_and_doctor_spell_it() {
        Scripted none = new Scripted();
        for (SecretBackend b :
                List.of(new SecretServiceBackend(none), viaCli(none), WindowsCredentialBackendTest.fake())) {
            assertThat(SecretVault.displayName(b.id())).isEqualTo(b.displayName());
        }
    }
}
