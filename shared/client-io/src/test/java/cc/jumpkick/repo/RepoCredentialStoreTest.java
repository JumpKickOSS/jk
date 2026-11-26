// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.config.Request;
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
        store.write(
                "ghp",
                new RepoCredential.Bearer("tok-123"),
                List.of(URI.create("https://maven.pkg.github.com/acme/x")));
        store.write("corp-nexus", new RepoCredential.Basic("deployer", "p@ss w/ space"), List.of(NEXUS));

        assertThat(store.read("ghp"))
                .contains(new RepoCredentialStore.Entry(
                        new RepoCredential.Bearer("tok-123"), List.of(URI.create("https://maven.pkg.github.com"))));
        assertThat(store.read("corp-nexus"))
                .contains(new RepoCredentialStore.Entry(
                        new RepoCredential.Basic("deployer", "p@ss w/ space"),
                        List.of(URI.create("https://nexus.corp:8443"))));
        assertThat(store.read("absent")).isEmpty();
    }

    /** A login for a repository whose publish-url is elsewhere keeps both origins on its first line. */
    @Test
    void a_login_for_two_origins_round_trips_both(@TempDir Path dir) throws Exception {
        var store = new RepoCredentialStore(dir);
        store.write(
                "acme",
                new RepoCredential.Bearer("tok"),
                List.of(URI.create("https://maven.acme.dev/"), URI.create("https://nexus.acme.dev/hosted/")));

        assertThat(Files.readString(dir.resolve("acme")))
                .isEqualTo("bearer https://maven.acme.dev https://nexus.acme.dev\ntok\n");
        assertThat(store.read("acme"))
                .contains(new RepoCredentialStore.Entry(
                        new RepoCredential.Bearer("tok"),
                        List.of(URI.create("https://maven.acme.dev"), URI.create("https://nexus.acme.dev"))));
        assertThat(store.logins())
                .singleElement()
                .extracting(RepoCredentialStore.Login::origins)
                .isEqualTo(List.of(URI.create("https://maven.acme.dev"), URI.create("https://nexus.acme.dev")));
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

    /** A file whose first line carries no origin reads back with none rather than not at all. */
    @Test
    void a_file_without_an_origin_reads_back_with_none(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("legacy"), "bearer\nold-token\n");
        assertThat(new RepoCredentialStore(dir).read("legacy"))
                .contains(new RepoCredentialStore.Entry(new RepoCredential.Bearer("old-token"), List.of()));
    }

    @Test
    void clear_removes_only_that_repo(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write("a", new RepoCredential.Bearer("ta"), List.of(NEXUS));
        store.write("b", new RepoCredential.Bearer("tb"), List.of(NEXUS));
        store.clear("a");
        assertThat(store.read("a")).isEmpty();
        assertThat(store.read("b"))
                .map(RepoCredentialStore.Entry::credential)
                .contains(new RepoCredential.Bearer("tb"));
    }

    @Test
    void refuses_to_store_anonymous(@TempDir Path dir) {
        assertThatThrownBy(() -> new RepoCredentialStore(dir).write("x", RepoCredential.ANONYMOUS, List.of(NEXUS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void file_is_owner_only_on_posix(@TempDir Path dir) throws Exception {
        var store = new RepoCredentialStore(dir);
        store.write("ghp", new RepoCredential.Bearer("tok"), List.of(NEXUS));
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
                store.write("corp-nexus", new RepoCredential.Basic("deployer", "p@ss w/ space"), List.of(NEXUS));

        assertThat(stored.pointer()).isEqualTo(new SecretVault.Pointer("fake", "repo/corp-nexus"));
        assertThat(Files.readString(dir.resolve("corp-nexus")))
                .isEqualTo("basic https://nexus.corp:8443 keychain\ndeployer\nfake repo/corp-nexus\n")
                .doesNotContain("p@ss");
        assertThat(keychain.items).containsEntry("repo/corp-nexus", "p@ss w/ space");
        assertThat(store.read("corp-nexus"))
                .contains(new RepoCredentialStore.Entry(
                        new RepoCredential.Basic("deployer", "p@ss w/ space"),
                        List.of(URI.create("https://nexus.corp:8443"))));

        store.write(
                "ghp",
                new RepoCredential.Bearer("tok-123"),
                List.of(URI.create("https://maven.pkg.github.com/acme/x")));
        assertThat(Files.readString(dir.resolve("ghp")))
                .isEqualTo("bearer https://maven.pkg.github.com keychain\nfake repo/ghp\n");
        assertThat(store.read("ghp").map(RepoCredentialStore.Entry::credential))
                .contains(new RepoCredential.Bearer("tok-123"));
    }

    @Test
    void with_the_keychain_locked_the_secret_stays_in_the_file_with_one_notice(@TempDir Path dir) throws Exception {
        FakeKeychain keychain = new FakeKeychain();
        keychain.locked = "the default keyring is locked, and JumpKick never asks to unlock it";
        var store = new RepoCredentialStore(dir, keychainVault(keychain));

        SecretVault.Stored stored = store.write("ghp", new RepoCredential.Bearer("tok-123"), List.of(NEXUS));

        assertThat(stored.pointer()).isNull();
        assertThat(stored.notice())
                .contains("the default keyring is locked")
                .contains(dir.resolve("ghp").toString());
        assertThat(Files.readString(dir.resolve("ghp"))).isEqualTo("bearer https://nexus.corp:8443\ntok-123\n");
    }

    @Test
    void a_login_over_a_plaintext_record_moves_the_secret_into_the_keychain(@TempDir Path dir) throws Exception {
        new RepoCredentialStore(dir).write("ghp", new RepoCredential.Bearer("old"), List.of(NEXUS));
        FakeKeychain keychain = new FakeKeychain();

        new RepoCredentialStore(dir, keychainVault(keychain))
                .write("ghp", new RepoCredential.Bearer("new"), List.of(NEXUS));

        assertThat(Files.readString(dir.resolve("ghp"))).doesNotContain("old").doesNotContain("new");
        assertThat(keychain.items).containsEntry("repo/ghp", "new");
    }

    @Test
    void a_file_login_over_a_keychain_record_removes_the_keychain_item(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        new RepoCredentialStore(dir, keychainVault(keychain))
                .write("ghp", new RepoCredential.Bearer("k"), List.of(NEXUS));

        new RepoCredentialStore(dir, SecretVault.of(SecretVault.Choice.FILE, List.of(keychain)))
                .write("ghp", new RepoCredential.Bearer("f"), List.of(NEXUS));

        assertThat(keychain.items).isEmpty();
    }

    @Test
    void logout_removes_the_keychain_item_and_the_record(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        var store = new RepoCredentialStore(dir, keychainVault(keychain));
        store.write("ghp", new RepoCredential.Bearer("tok"), List.of(NEXUS));

        store.clear("ghp");

        assertThat(keychain.items).isEmpty();
        assertThat(dir.resolve("ghp")).doesNotExist();
    }

    @Test
    void a_keychain_that_refuses_the_logout_keeps_the_record_so_it_can_be_retried(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        var store = new RepoCredentialStore(dir, keychainVault(keychain));
        store.write("ghp", new RepoCredential.Bearer("tok"), List.of(NEXUS));
        keychain.refusing = "locked";

        assertThatThrownBy(() -> store.clear("ghp")).isInstanceOf(SecretBackendException.class);
        assertThat(dir.resolve("ghp")).exists();
    }

    @Test
    void a_pointer_the_keychain_cannot_answer_reads_as_no_login(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        var store = new RepoCredentialStore(dir, keychainVault(keychain));
        store.write("ghp", new RepoCredential.Bearer("tok"), List.of(NEXUS));
        keychain.items.clear();

        assertThat(new RepoCredentialStore(dir, keychainVault(keychain)).read("ghp"))
                .isEmpty();
        assertThat(new RepoCredentialStore(dir).read("ghp")).isEmpty();
    }

    @Test
    void logins_list_every_record_with_its_binding_and_pointer_and_no_secret(@TempDir Path dir) {
        FakeKeychain keychain = new FakeKeychain();
        new RepoCredentialStore(dir, keychainVault(keychain))
                .write("a", new RepoCredential.Bearer("ta"), List.of(NEXUS));
        new RepoCredentialStore(dir).write("b", new RepoCredential.Basic("u", "pb"), List.of(NEXUS));

        assertThat(new RepoCredentialStore(dir).logins())
                .containsExactly(
                        new RepoCredentialStore.Login(
                                "a",
                                "bearer",
                                List.of(URI.create("https://nexus.corp:8443")),
                                new SecretVault.Pointer("fake", "repo/a")),
                        new RepoCredentialStore.Login(
                                "b", "basic", List.of(URI.create("https://nexus.corp:8443")), null));
    }

    @Test
    void a_stored_and_a_cleared_login_are_journaled_without_the_secret(@TempDir Path dir) {
        Path journal = dir.resolve("changes");
        var store = new RepoCredentialStore(dir.resolve("creds"));
        ChangeActor shell = ChangeActor.shell(List.of("repo", "login", "corp-nexus", "--password", "p@ss-literal"));

        ChangeJournal.into(
                journal,
                () -> Request.actingAs(shell, () -> {
                    store.write("corp-nexus", new RepoCredential.Basic("deployer", "p@ss-literal"), List.of(NEXUS));
                    store.clear("corp-nexus");
                    return null;
                }));

        List<Change> entries = new ChangeJournal(journal).page(null, 10).entries();
        assertThat(entries).extracting(Change::action).containsExactly(Change.CLEAR, Change.STORE);
        Change stored = entries.getLast();
        assertThat(stored.what()).isEqualTo("credential for repository corp-nexus");
        assertThat(stored.diff()).contains("user deployer, password ***").contains("kept in");
        assertThat(stored.actor().label()).isEqualTo("jk repo login corp-nexus --password");
        assertThat(entries).allSatisfy(e -> assertThat(e.toMap().toString()).doesNotContain("p@ss-literal"));
    }
}
