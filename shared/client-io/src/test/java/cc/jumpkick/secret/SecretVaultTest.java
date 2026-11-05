// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretVaultTest {

    @Test
    void auto_puts_the_secret_in_the_first_keychain_that_answers() {
        FakeKeychain down = new FakeKeychain();
        down.unavailable = "no bus";
        FakeKeychain up = new FakeKeychain();
        SecretVault vault = SecretVault.of(SecretVault.Choice.AUTO, List.of(down, up));

        SecretVault.Stored stored = vault.store("repo/nexus", "jk repo login nexus", "tok", Path.of("/r"));

        assertThat(stored.pointer()).isEqualTo(new SecretVault.Pointer("fake", "repo/nexus"));
        assertThat(stored.notice()).isNull();
        assertThat(up.items).containsEntry("repo/nexus", "tok");
        assertThat(down.items).isEmpty();
    }

    @Test
    void auto_with_no_keychain_here_keeps_the_secret_in_the_file_without_a_word() {
        FakeKeychain absent = new FakeKeychain();
        absent.unavailable = "no D-Bus session bus";
        SecretVault vault = SecretVault.of(SecretVault.Choice.AUTO, List.of(absent));

        SecretVault.Stored stored = vault.store("repo/nexus", "l", "tok", Path.of("/home/u/.jk/creds/repo/nexus"));

        assertThat(stored).isEqualTo(new SecretVault.Stored(null, null));
        assertThat(absent.calls).isEmpty();
    }

    @Test
    void auto_with_a_locked_keychain_keeps_the_secret_in_the_file_with_one_notice_naming_why() {
        FakeKeychain locked = new FakeKeychain();
        locked.locked = "the default keyring is locked, and jk never asks to unlock it";
        SecretVault vault = SecretVault.of(SecretVault.Choice.AUTO, List.of(locked));

        SecretVault.Stored stored = vault.store("repo/nexus", "l", "tok", Path.of("/home/u/.jk/creds/repo/nexus"));

        assertThat(stored.pointer()).isNull();
        assertThat(stored.notice())
                .contains("Fake Keychain: the default keyring is locked")
                .contains("/home/u/.jk/creds/repo/nexus")
                .contains("[credentials] store = \"file\"");
        assertThat(locked.calls).isEmpty();
    }

    @Test
    void auto_on_a_ci_server_or_in_a_container_asks_no_keychain_and_says_nothing() {
        for (HostSession headless : List.of(
                session(Map.of("CI", "true")),
                session(Map.of("GITHUB_ACTIONS", "true")),
                session(Map.of("container", "podman")),
                new HostSession(name -> null, Path.of("/.dockerenv")::equals))) {
            FakeKeychain up = new FakeKeychain();
            SecretVault.Stored stored = SecretVault.of(SecretVault.Choice.AUTO, List.of(up), headless)
                    .store("repo/nexus", "l", "tok", Path.of("/r"));

            assertThat(stored).isEqualTo(new SecretVault.Stored(null, null));
            assertThat(up.probes).isZero();
            assertThat(up.calls).isEmpty();
        }
    }

    @Test
    void over_ssh_with_no_display_a_locked_keychain_is_expected_and_earns_no_notice() {
        FakeKeychain locked = new FakeKeychain();
        locked.locked = "locked";
        HostSession ssh = session(Map.of("SSH_CONNECTION", "10.0.0.2 51000 10.0.0.1 22"));

        SecretVault.Stored stored = SecretVault.of(SecretVault.Choice.AUTO, List.of(locked), ssh)
                .store("repo/nexus", "l", "tok", Path.of("/r"));

        assertThat(stored).isEqualTo(new SecretVault.Stored(null, null));
    }

    @Test
    void keychain_on_a_headless_machine_fails_fast_naming_why_and_the_ci_recipe() {
        FakeKeychain absent = new FakeKeychain();
        absent.unavailable = "no D-Bus session bus";

        assertThatThrownBy(() -> SecretVault.of(
                                SecretVault.Choice.KEYCHAIN, List.of(absent), session(Map.of("GITLAB_CI", "true")))
                        .store("repo/nexus", "l", "tok", Path.of("/r")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fake Keychain: no D-Bus session bus")
                .hasMessageContaining("a CI server (GITLAB_CI is set)")
                .hasMessageContaining("JK_REPO_<ID>_TOKEN_FILE")
                .hasMessageContaining("${file:}")
                .hasMessageContaining("${cmd:}")
                .hasMessageContaining(SecretVault.ENV + "=file");
        assertThat(absent.calls).isEmpty();
    }

    @Test
    void keychain_on_a_ci_server_still_uses_a_keychain_that_answers() {
        FakeKeychain up = new FakeKeychain();
        SecretVault.Stored stored = SecretVault.of(SecretVault.Choice.KEYCHAIN, List.of(up), session(Map.of("CI", "1")))
                .store("repo/nexus", "l", "tok", Path.of("/r"));

        assertThat(stored.pointer()).isEqualTo(new SecretVault.Pointer("fake", "repo/nexus"));
    }

    @Test
    void destination_names_the_headless_reason_when_auto_asks_no_keychain() {
        List<String> reasons = new ArrayList<>();
        FakeKeychain up = new FakeKeychain();

        assertThat(SecretVault.of(SecretVault.Choice.AUTO, List.of(up), session(Map.of("CI", "true")))
                        .destination(reasons))
                .isEmpty();
        assertThat(reasons).containsExactly("this is a CI server (CI is set)");
        assertThat(up.probes).isZero();
    }

    private static HostSession session(Map<String, String> env) {
        return new HostSession(env::get, path -> false);
    }

    @Test
    void a_keychain_that_refuses_the_write_falls_back_under_auto_and_fails_under_keychain() {
        FakeKeychain locked = new FakeKeychain();
        locked.refusing = "the collection is locked";

        SecretVault.Stored auto =
                SecretVault.of(SecretVault.Choice.AUTO, List.of(locked)).store("repo/nexus", "l", "tok", Path.of("/r"));
        assertThat(auto.pointer()).isNull();
        assertThat(auto.notice()).contains("the collection is locked");

        assertThatThrownBy(() -> SecretVault.of(SecretVault.Choice.KEYCHAIN, List.of(locked))
                        .store("repo/nexus", "l", "tok", Path.of("/r")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the collection is locked")
                .hasMessageContaining(SecretVault.ENV + "=file");
    }

    @Test
    void file_keeps_the_secret_in_the_record_without_asking_any_keychain() {
        FakeKeychain up = new FakeKeychain();
        SecretVault.Stored stored =
                SecretVault.of(SecretVault.Choice.FILE, List.of(up)).store("repo/nexus", "l", "tok", Path.of("/r"));

        assertThat(stored).isEqualTo(new SecretVault.Stored(null, null));
        assertThat(up.calls).isEmpty();
    }

    @Test
    void the_choice_is_spelled_keychain_file_or_auto() {
        assertThat(SecretVault.Choice.parse(" Keychain ", "x")).isEqualTo(SecretVault.Choice.KEYCHAIN);
        assertThat(SecretVault.Choice.parse("file", "x")).isEqualTo(SecretVault.Choice.FILE);
        assertThat(SecretVault.Choice.parse("auto", "x")).isEqualTo(SecretVault.Choice.AUTO);
        assertThatThrownBy(() -> SecretVault.Choice.parse("vault", "[credentials] store"))
                .hasMessageContaining("[credentials] store is \"vault\"");
    }

    @Test
    void a_pointer_round_trips_through_its_text_and_rejects_anything_else() {
        SecretVault.Pointer pointer = new SecretVault.Pointer("secret-service", "repo/corp-nexus");
        assertThat(SecretVault.Pointer.parse(pointer.text())).contains(pointer);
        assertThat(SecretVault.Pointer.parse("p@ss w0rd")).isEmpty();
        assertThat(SecretVault.Pointer.parse("just-a-token")).isEmpty();
        assertThat(SecretVault.Pointer.parse("a b c")).isEmpty();
    }

    @Test
    void resolve_prefers_what_the_client_sent_and_otherwise_asks_the_keychain_once_per_record(@TempDir Path dir)
            throws Exception {
        FakeKeychain keychain = new FakeKeychain();
        keychain.items.put("repo/nexus", "from-keychain");
        SecretVault vault = SecretVault.of(SecretVault.Choice.AUTO, List.of(keychain));
        SecretVault.Pointer pointer = new SecretVault.Pointer("fake", "repo/nexus");
        Path record = Files.writeString(dir.resolve("nexus"), "bearer https://n keychain\nfake repo/nexus\n");

        String sent = RequestKeychain.where(
                Map.of(pointer.text(), "from-client"), Map.of(), () -> vault.resolve(pointer, record)
                        .orElseThrow());
        assertThat(sent).isEqualTo("from-client");
        assertThat(keychain.calls).isEmpty();

        assertThat(vault.resolve(pointer, record)).contains("from-keychain");
        assertThat(vault.resolve(pointer, record)).contains("from-keychain");
        assertThat(keychain.calls).containsExactly("read repo/nexus");
    }

    @Test
    void an_item_the_client_could_not_read_fails_with_its_reason_and_the_keychain_is_not_asked_again(@TempDir Path dir)
            throws Exception {
        FakeKeychain keychain = new FakeKeychain();
        keychain.items.put("repo/nexus", "from-keychain");
        SecretVault vault = SecretVault.of(SecretVault.Choice.AUTO, List.of(keychain));
        SecretVault.Pointer pointer = new SecretVault.Pointer("fake", "repo/nexus");
        SecretVault.Pointer other = new SecretVault.Pointer("fake", "repo/other");
        Path record = Files.writeString(dir.resolve("nexus"), "bearer https://n keychain\nfake repo/nexus\n");

        Throwable refused = RequestKeychain.where(
                Map.of(),
                Map.of(pointer.text(), "the default keyring is locked"),
                () -> catchThrowable(() -> vault.resolve(pointer, record)));

        assertThat(refused).isInstanceOf(SecretBackendException.class).hasMessage("the default keyring is locked");
        assertThat(keychain.calls).isEmpty();
        assertThat(RequestKeychain.where(
                        Map.of(), Map.of(pointer.text(), "locked"), () -> vault.resolve(other, record)))
                .as("a pointer the client did not speak for is the engine's to read")
                .isEmpty();
        assertThat(keychain.calls).containsExactly("read repo/other");
    }

    @Test
    void a_client_read_is_the_secret_or_why_there_is_none() {
        FakeKeychain keychain = new FakeKeychain();
        keychain.items.put("repo/nexus", "tok");
        SecretVault vault = SecretVault.of(SecretVault.Choice.AUTO, List.of(keychain));

        assertThat(vault.readForRequest(new SecretVault.Pointer("fake", "repo/nexus")))
                .isEqualTo(new SecretVault.ClientRead("tok", null));
        assertThat(vault.readForRequest(new SecretVault.Pointer("fake", "repo/gone")))
                .isEqualTo(new SecretVault.ClientRead(null, "it holds no item repo/gone"));
        assertThat(vault.readForRequest(new SecretVault.Pointer("elsewhere", "repo/nexus"))
                        .refused())
                .startsWith("this machine has no elsewhere keychain");
        keychain.refusing = "denied";
        assertThat(vault.readForRequest(new SecretVault.Pointer("fake", "repo/nexus"))
                        .refused())
                .contains("denied");
    }

    @Test
    void item_names_are_folded_to_a_set_no_command_line_quotes() {
        assertThat(SecretItems.of("repo", "Corp Nexus:1")).isEqualTo("repo/corp_nexus_1");
        assertThat(SecretItems.of("forge", "GitHub.com")).isEqualTo("forge/github.com");
        assertThatThrownBy(() -> SecretItems.requireValid("repo/a'b")).isInstanceOf(IllegalArgumentException.class);
    }
}
