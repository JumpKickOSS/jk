// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
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

        Scripted missing() {
            answers.add(new IOException("Cannot run program: No such file or directory"));
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

    // ---- Secret Service (secret-tool) ----

    @Test
    void secret_tool_stores_the_secret_on_stdin_under_service_and_account() {
        Scripted cmd = new Scripted().answer(0, "", "");
        new SecretServiceBackend(cmd).write("repo/nexus", "jk repo login nexus", "s3cret");

        assertThat(cmd.argv.get(0))
                .containsExactly(
                        "secret-tool",
                        "store",
                        "--label=jk repo login nexus",
                        "service",
                        "jk",
                        "account",
                        "repo/nexus");
        assertThat(cmd.stdin.get(0)).isEqualTo("s3cret");
    }

    @Test
    void secret_tool_lookup_reads_the_secret_and_tells_absent_from_refused() {
        Scripted cmd = new Scripted()
                .answer(0, "s3cret", "")
                .answer(1, "", "")
                .answer(1, "", "secret-tool: Cannot autolaunch D-Bus without X11 $DISPLAY\n");
        SecretServiceBackend backend = new SecretServiceBackend(cmd);

        assertThat(backend.read("repo/nexus")).contains("s3cret");
        assertThat(cmd.argv.get(0)).containsExactly("secret-tool", "lookup", "service", "jk", "account", "repo/nexus");
        assertThat(backend.read("repo/nexus")).isEmpty();
        assertThatThrownBy(() -> backend.read("repo/nexus"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("Cannot autolaunch D-Bus");
    }

    @Test
    void secret_tool_probe_answers_from_a_search_and_names_why_not() {
        assertThat(new SecretServiceBackend(new Scripted().answer(0, "", ""))
                        .probe()
                        .available())
                .isTrue();
        SecretBackend.Probe noBus = new SecretServiceBackend(
                        new Scripted().answer(1, "", "secret-tool: Cannot autolaunch D-Bus without X11 $DISPLAY"))
                .probe();
        assertThat(noBus.available()).isFalse();
        assertThat(noBus.reason()).contains("Cannot autolaunch D-Bus");
        SecretBackend.Probe absent = new SecretServiceBackend(new Scripted().missing()).probe();
        assertThat(absent.reason()).contains("secret-tool is not available");
    }

    @Test
    void secret_tool_clear_treats_an_absent_item_as_removed() {
        Scripted cmd = new Scripted().answer(1, "", "");
        new SecretServiceBackend(cmd).delete("repo/nexus");
        assertThat(cmd.argv.get(0)).containsExactly("secret-tool", "clear", "service", "jk", "account", "repo/nexus");
    }

    // ---- macOS Keychain (security) ----

    @Test
    void security_adds_through_its_interactive_mode_so_the_secret_is_never_an_argument() {
        Scripted cmd = new Scripted().answer(0, "", "");
        new MacKeychainBackend(cmd).write("repo/nexus", "ignored label", "p@ss w0rd\"");

        assertThat(cmd.argv.get(0)).containsExactly("/usr/bin/security", "-i");
        String hex = HexFormat.of().formatHex("p@ss w0rd\"".getBytes(StandardCharsets.UTF_8));
        assertThat(cmd.stdin.get(0))
                .isEqualTo("add-generic-password -U -s jk -a repo/nexus -l jk:repo/nexus -X " + hex + "\n");
        assertThat(String.join(" ", cmd.argv.get(0))).doesNotContain(hex);
    }

    @Test
    void security_interactive_mode_reports_a_failure_on_stderr_with_exit_zero() {
        Scripted cmd =
                new Scripted().answer(0, "", "SecKeychainItemCreateFromContent: User interaction is not allowed.");
        assertThatThrownBy(() -> new MacKeychainBackend(cmd).write("repo/nexus", "l", "s"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("User interaction is not allowed");
    }

    @Test
    void security_find_reads_the_password_and_44_means_absent() {
        Scripted cmd = new Scripted()
                .answer(0, "s3cret\n", "")
                .answer(44, "", "security: SecKeychainSearchCopyNext: The specified item could not be found.")
                .answer(51, "", "security: SecKeychainUnlock: user canceled");
        MacKeychainBackend backend = new MacKeychainBackend(cmd);

        assertThat(backend.read("repo/nexus")).contains("s3cret");
        assertThat(cmd.argv.get(0))
                .containsExactly("/usr/bin/security", "find-generic-password", "-s", "jk", "-a", "repo/nexus", "-w");
        assertThat(backend.read("repo/nexus")).isEmpty();
        assertThatThrownBy(() -> backend.read("repo/nexus")).hasMessageContaining("user canceled");
    }

    @Test
    void security_delete_treats_44_as_removed() {
        Scripted cmd = new Scripted().answer(44, "", "could not be found");
        new MacKeychainBackend(cmd).delete("forge/github.com");
        assertThat(cmd.argv.get(0))
                .containsExactly("/usr/bin/security", "delete-generic-password", "-s", "jk", "-a", "forge/github.com");
    }

    // ---- Windows Credential Manager (powershell) ----

    private static String script(List<String> argv) {
        assertThat(argv.subList(0, 5))
                .containsExactly("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand");
        return new String(Base64.getDecoder().decode(argv.get(5)), StandardCharsets.UTF_16LE);
    }

    @Test
    void powershell_writes_the_target_into_an_encoded_script_and_the_secret_onto_stdin() {
        Scripted cmd = new Scripted().answer(0, "", "");
        new WindowsCredentialBackend(cmd).write("repo/nexus", "l", "s3cret");

        String script = script(cmd.argv.get(0));
        assertThat(script)
                .contains("$target = 'jk:repo/nexus'")
                .contains("[JkCred]::Write($target, [Console]::In.ReadToEnd())")
                .contains("CredWriteW")
                .doesNotContain("s3cret");
        assertThat(cmd.stdin.get(0)).isEqualTo("s3cret");
    }

    @Test
    void powershell_read_returns_stdout_and_exit_3_means_absent() {
        Scripted cmd = new Scripted().answer(0, "s3cret", "").answer(3, "", "").answer(1, "", "Win32 error 1312\r\n");
        WindowsCredentialBackend backend = new WindowsCredentialBackend(cmd);

        assertThat(backend.read("repo/nexus")).contains("s3cret");
        assertThat(script(cmd.argv.get(0))).contains("[JkCred]::Read($target, [ref]$s)");
        assertThat(backend.read("repo/nexus")).isEmpty();
        assertThatThrownBy(() -> backend.read("repo/nexus")).hasMessageContaining("Win32 error 1312");
    }

    @Test
    void powershell_delete_treats_not_found_as_removed() {
        Scripted cmd = new Scripted().answer(3, "", "");
        new WindowsCredentialBackend(cmd).delete("forge/github.com");
        assertThat(script(cmd.argv.get(0)))
                .contains("$target = 'jk:forge/github.com'")
                .contains("[JkCred]::Delete");
    }

    @Test
    void every_backend_names_itself_the_way_records_and_doctor_spell_it() {
        Scripted none = new Scripted();
        for (SecretBackend b : List.of(
                new SecretServiceBackend(none), new MacKeychainBackend(none), new WindowsCredentialBackend(none))) {
            assertThat(SecretVault.displayName(b.id())).isEqualTo(b.displayName());
        }
        assertThat(new SecretServiceBackend(none).daemonReachable()).isFalse();
        assertThat(new MacKeychainBackend(none).daemonReachable()).isTrue();
        assertThat(new WindowsCredentialBackend(none).daemonReachable()).isTrue();
    }
}
