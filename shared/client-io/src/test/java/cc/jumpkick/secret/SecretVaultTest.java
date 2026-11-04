// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void auto_falls_back_to_the_file_with_one_notice_naming_why() {
        FakeKeychain down = new FakeKeychain();
        down.unavailable = "Cannot autolaunch D-Bus without X11 $DISPLAY";
        SecretVault vault = SecretVault.of(SecretVault.Choice.AUTO, List.of(down));

        SecretVault.Stored stored = vault.store("repo/nexus", "l", "tok", Path.of("/home/u/.jk/creds/repo/nexus"));

        assertThat(stored.pointer()).isNull();
        assertThat(stored.notice())
                .contains("Fake Keychain: Cannot autolaunch D-Bus")
                .contains("/home/u/.jk/creds/repo/nexus")
                .contains("[credentials] store = \"file\"");
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

        String sent = RequestKeychain.where(Map.of(pointer.text(), "from-client"), () -> vault.resolve(pointer, record)
                .orElseThrow());
        assertThat(sent).isEqualTo("from-client");
        assertThat(keychain.calls).isEmpty();

        assertThat(vault.resolve(pointer, record)).contains("from-keychain");
        assertThat(vault.resolve(pointer, record)).contains("from-keychain");
        assertThat(keychain.calls).containsExactly("read repo/nexus");
    }

    @Test
    void item_names_are_folded_to_a_set_no_command_line_quotes() {
        assertThat(SecretItems.of("repo", "Corp Nexus:1")).isEqualTo("repo/corp_nexus_1");
        assertThat(SecretItems.of("forge", "GitHub.com")).isEqualTo("forge/github.com");
        assertThatThrownBy(() -> SecretItems.requireValid("repo/a'b")).isInstanceOf(IllegalArgumentException.class);
    }
}
