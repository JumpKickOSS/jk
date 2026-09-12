// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.credential.RepoCredential;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
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
}
