// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.repo.RepoCredentialStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class RepoCommandTest {

    private static int run(@Nullable String stdin, String... args) {
        InputStream prev = System.in;
        try {
            System.setIn(new ByteArrayInputStream((stdin == null ? "" : stdin).getBytes(StandardCharsets.UTF_8)));
            return Jk.execute(args);
        } finally {
            System.setIn(prev);
        }
    }

    private static final String URL = "https://nexus.corp:8443/repository/maven/";
    private static final URI ORIGIN = URI.create("https://nexus.corp:8443");

    @Test
    void login_stores_bearer_token_from_stdin_bound_to_the_url(@TempDir Path dir) {
        int code = run(
                "tok-from-stdin\n", "repo", "login", "corp-nexus", "--url", URL, "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(0);
        assertThat(new RepoCredentialStore(dir).read("corp-nexus"))
                .contains(new RepoCredentialStore.Entry(new RepoCredential.Bearer("tok-from-stdin"), ORIGIN));
    }

    @Test
    void login_with_username_stores_basic(@TempDir Path dir) {
        int code = run(
                "the-password\n",
                "repo",
                "login",
                "corp-nexus",
                "--username",
                "deployer",
                "--url",
                URL,
                "--credentials-dir",
                dir.toString());
        assertThat(code).isEqualTo(0);
        assertThat(new RepoCredentialStore(dir).read("corp-nexus"))
                .contains(new RepoCredentialStore.Entry(new RepoCredential.Basic("deployer", "the-password"), ORIGIN));
    }

    /** The URL comes from the project's own manifest when the id is declared there. */
    @Test
    void login_binds_to_the_url_the_working_directory_manifest_declares(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "1.0.0"
                java = 25

                [repositories.internal]
                url = "https://repo.acme.com/maven/"
                """);
        int code = run(
                "tok\n", "-C", project.toString(), "repo", "login", "internal", "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(0);
        assertThat(new RepoCredentialStore(dir).read("internal"))
                .contains(new RepoCredentialStore.Entry(
                        new RepoCredential.Bearer("tok"), URI.create("https://repo.acme.com")));
    }

    /** A registry is logged into by host, so the host is the URL. */
    @Test
    void a_host_shaped_id_binds_to_itself(@TempDir Path dir) {
        int code = run("tok\n", "repo", "login", "ghcr.io", "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(0);
        assertThat(new RepoCredentialStore(dir).read("ghcr.io"))
                .contains(
                        new RepoCredentialStore.Entry(new RepoCredential.Bearer("tok"), URI.create("https://ghcr.io")));
    }

    /** A nickname nothing declares has no destination; the login says so rather than guessing one. */
    @Test
    void an_undeclared_id_that_is_not_a_host_needs_a_url(@TempDir Path dir) throws Exception {
        Path empty = Files.createDirectories(dir.resolve("empty"));
        int code = run(
                "tok\n", "-C", empty.toString(), "repo", "login", "corp-nexus", "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(1);
        assertThat(new RepoCredentialStore(dir).read("corp-nexus")).isEmpty();
    }

    @Test
    void blank_stdin_is_an_error(@TempDir Path dir) {
        int code = run("   \n", "repo", "login", "x", "--url", URL, "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(1);
        assertThat(new RepoCredentialStore(dir).read("x")).isEmpty();
    }

    @Test
    void logout_removes_stored_credentials(@TempDir Path dir) {
        run("tok\n", "repo", "login", "corp-nexus", "--url", URL, "--credentials-dir", dir.toString());
        int code = run(null, "repo", "logout", "corp-nexus", "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(0);
        assertThat(new RepoCredentialStore(dir).read("corp-nexus")).isEmpty();
    }
}
