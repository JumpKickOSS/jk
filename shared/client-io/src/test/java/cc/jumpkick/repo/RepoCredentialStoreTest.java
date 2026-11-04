// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.secret.FakeKeychain;
import cc.jumpkick.secret.SecretBackendException;
import cc.jumpkick.secret.SecretVault;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoCredentialStoreTest {

    private static final URI NEXUS = URI.create("https://nexus.corp:8443/repository/maven/");

    @Test
    void round_trips_bearer_and_basic_with_the_origin_they_were_stored_for(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write("ghp", new RepoCredential.Bearer("tok-123"), URI.create("https://maven.pkg.github.com/acme/x"));
        store.write("corp-nexus", new RepoCredential.Basic("deployer", "p@ss w/ space"), NEXUS);

        assertThat(store.read("ghp"))
                .contains(new RepoCredentialStore.Entry(
                        new RepoCredential.Bearer("tok-123"), URI.create("https://maven.pkg.github.com")));
        assertThat(store.read("corp-nexus"))
                .contains(new RepoCredentialStore.Entry(
                        new RepoCredential.Basic("deployer", "p@ss w/ space"), URI.create("https://nexus.corp:8443")));
        assertThat(store.read("absent")).isEmpty();
    }

    /** Only the origin is kept: the path, query and any userinfo of the URL are not part of the binding. */
    @Test
    void the_origin_is_scheme_host_and_port_only() {
        assertThat(RepoCredentialStore.originOf(URI.create("HTTPS://Nexus.Corp/repo/x?y=1")))
                .isEqualTo(URI.create("https://nexus.corp"));
        assertThat(RepoCredentialStore.originOf(URI.create("http://127.0.0.1:5000/v2/")))
                .isEqualTo(URI.create("http://127.0.0.1:5000"));
        assertThatThrownBy(() -> RepoCredentialStore.originOf(URI.create("nexus.corp/repo")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A file whose first line carries no origin reads back with a null one rather than not at all. */
    @Test
    void a_file_without_an_origin_reads_back_with_none(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("legacy"), "bearer\nold-token\n");
        assertThat(new RepoCredentialStore(dir).read("legacy"))
                .contains(new RepoCredentialStore.Entry(new RepoCredential.Bearer("old-token"), null));
    }

    @Test
    void clear_removes_only_that_repo(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write("a", new RepoCredential.Bearer("ta"), NEXUS);
        store.write("b", new RepoCredential.Bearer("tb"), NEXUS);
        store.clear("a");
        assertThat(store.read("a")).isEmpty();
        assertThat(store.read("b"))
                .map(RepoCredentialStore.Entry::credential)
                .contains(new RepoCredential.Bearer("tb"));
    }

    @Test
    void refuses_to_store_anonymous(@TempDir Path dir) {
        assertThatThrownBy(() -> new RepoCredentialStore(dir).write("x", RepoCredential.ANONYMOUS, NEXUS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void file_is_owner_only_on_posix(@TempDir Path dir) throws Exception {
        var store = new RepoCredentialStore(dir);
        store.write("ghp", new RepoCredential.Bearer("tok"), NEXUS);
        Path file = dir.resolve("ghp");
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        assumeTrue(posix != null, "POSIX-only assertion");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    private static SecretVault keychainVault(FakeKeychain keychain) {
        return SecretVault.of(SecretVault.Choice.AUTO, List.of(keychain));
    }

    @Test
    void with_a_keychain_the_record_keeps_the_binding_and_a_pointer_and_the_secret_leaves_the_file(@TempDir Path dir)
            throws Exception {
        FakeKeychain keychain = new FakeKeychain();
        var store = new RepoCredentialStore(dir, keychainVault(keychain));

        SecretVault.Stored stored =
                store.write("corp-nexus", new RepoCredential.Basic("deployer", "p@ss w/ space"), NEXUS);

        assertThat(stored.pointer()).isEqualTo(new SecretVault.Pointer("fake", "repo/corp-nexus"));
        assertThat(Files.readString(dir.resolve("corp-nexus")))
                .isEqualTo("basic https://nexus.corp:8443 keychain\ndeployer\nfake repo/corp-nexus\n")
                .doesNotContain("p@ss");
        assertThat(keychain.items).containsEntry("repo/corp-nexus", "p@ss w/ space");
        assertThat(store.read("corp-nexus"))
                .contains(new RepoCredentialStore.Entry(
                        new RepoCredential.Basic("deployer", "p@ss w/ space"), URI.create("https://nexus.corp:8443")));

        store.write("ghp", new RepoCredential.Bearer("tok-123"), URI.create("https://maven.pkg.github.com/acme/x"));
        assertThat(Files.readString(dir.resolve("ghp")))
                .isEqualTo("bearer https://maven.pkg.github.com keychain\nfake repo/ghp\n");
        assertThat(store.read("ghp").map(RepoCredentialStore.Entry::credential))
                .contains(new RepoCredential.Bearer("tok-123"));
    }

    @Test
    void with_the_keychain_locked_the_secret_stays_in_the_file_with_one_notice(@TempDir Path dir) throws Exception {
        FakeKeychain keychain = new FakeKeychain();
        keychain.locked = "the default keyring is locked, and jk never asks to unlock it";
        var store = new RepoCredentialStore(dir, keychainVault(keychain));

        SecretVault.Stored stored = store.write("ghp", new RepoCredential.Bearer("tok-123"), NEXUS);

        assertThat(stored.pointer()).isNull();
        assertThat(stored.notice())
                .contains("the default keyring is locked")
                .contains(dir.resolve("ghp").toString());
        assertThat(Files.readString(dir.resolve("ghp"))).isEqualTo("bearer https://nexus.corp:8443\ntok-123\n");
    }

    @Test
    void a_login_over_a_plaintext_record_moves_the_secret_into_the_keychain(@TempDir Path dir) throws Exception {
        new RepoCredentialStore(dir).write("ghp", new RepoCredential.Bearer("old"), NEXUS);
        FakeKeychain keychain = new FakeKeychain();

        new RepoCredentialStore(dir, keychainVault(keychain)).write("ghp", new RepoCredential.Bearer("new"), NEXUS);

        assertThat(Files.readString(dir.resolve("ghp"))).doesNotContain("old").doesNotContain("new");
        assertThat(keychain.items).containsEntry("repo/ghp", "new");
    }

    @Test
    void a_file_login_over_a_keychain_record_removes_the_keychain_item(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        new RepoCredentialStore(dir, keychainVault(keychain)).write("ghp", new RepoCredential.Bearer("k"), NEXUS);

        new RepoCredentialStore(dir, SecretVault.of(SecretVault.Choice.FILE, List.of(keychain)))
                .write("ghp", new RepoCredential.Bearer("f"), NEXUS);

        assertThat(keychain.items).isEmpty();
    }

    @Test
    void logout_removes_the_keychain_item_and_the_record(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        var store = new RepoCredentialStore(dir, keychainVault(keychain));
        store.write("ghp", new RepoCredential.Bearer("tok"), NEXUS);

        store.clear("ghp");

        assertThat(keychain.items).isEmpty();
        assertThat(dir.resolve("ghp")).doesNotExist();
    }

    @Test
    void a_keychain_that_refuses_the_logout_keeps_the_record_so_it_can_be_retried(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        var store = new RepoCredentialStore(dir, keychainVault(keychain));
        store.write("ghp", new RepoCredential.Bearer("tok"), NEXUS);
        keychain.refusing = "locked";

        assertThatThrownBy(() -> store.clear("ghp")).isInstanceOf(SecretBackendException.class);
        assertThat(dir.resolve("ghp")).exists();
    }

    @Test
    void a_pointer_the_keychain_cannot_answer_reads_as_no_login(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        var store = new RepoCredentialStore(dir, keychainVault(keychain));
        store.write("ghp", new RepoCredential.Bearer("tok"), NEXUS);
        keychain.items.clear();

        assertThat(new RepoCredentialStore(dir, keychainVault(keychain)).read("ghp"))
                .isEmpty();
        assertThat(new RepoCredentialStore(dir).read("ghp")).isEmpty();
    }

    @Test
    void logins_list_every_record_with_its_binding_and_pointer_and_no_secret(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        new RepoCredentialStore(dir, keychainVault(keychain)).write("a", new RepoCredential.Bearer("ta"), NEXUS);
        new RepoCredentialStore(dir).write("b", new RepoCredential.Basic("u", "pb"), NEXUS);

        assertThat(new RepoCredentialStore(dir).logins())
                .containsExactly(
                        new RepoCredentialStore.Login(
                                "a",
                                "bearer",
                                URI.create("https://nexus.corp:8443"),
                                new SecretVault.Pointer("fake", "repo/a")),
                        new RepoCredentialStore.Login("b", "basic", URI.create("https://nexus.corp:8443"), null));
    }
}
