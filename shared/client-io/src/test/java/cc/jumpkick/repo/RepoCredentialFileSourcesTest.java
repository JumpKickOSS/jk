// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.ForgeIdentity;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.host.Log;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.task.RunNotices;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File-sourced repository credentials: a {@code ${file:...}} reference in the user's own config, the
 * same reference refused from a project manifest, and the {@code JK_REPO_<ID>_*_FILE} convention.
 */
class RepoCredentialFileSourcesTest {

    private static final URI CORP = URI.create("https://corp.example/maven/");

    @BeforeEach
    @AfterEach
    void forget() {
        RunNotices.clear();
        ResolvedSecrets.clear();
    }

    private static Path secret(Path dir, String name, String body) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private static RepoCredentialResolver resolver(
            Path dir, Map<String, String> env, Map<String, String> shell, List<RepositorySpec> user) {
        return new RepoCredentialResolver(
                env::get,
                MavenSettings.empty(),
                new RepoCredentialStore(dir.resolve("store")),
                new ForgeAuth(new TokenStore(dir.resolve("tokens")), k -> null, argv -> Optional.empty()),
                (ForgeIdentity) (endpoint, field, token) -> Optional.empty(),
                shell::get,
                () -> user);
    }

    private static String warningsFrom(Runnable body) {
        var err = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(err, true, StandardCharsets.UTF_8), System.Logger.Level.INFO, UnaryOperator.identity());
        try {
            SessionContext.runWhere(Session.defaults(), body);
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void a_user_config_file_reference_authenticates_and_follows_rotation(@TempDir Path dir) throws IOException {
        Path token = secret(dir, "corp-token", "first-token\n");
        var declared = new RepoCredential.Bearer("${file:" + token + "}");
        var r = resolver(dir, Map.of(), Map.of(), List.of(new RepositorySpec("corp", CORP, declared)));

        assertThat(r.resolve("corp", CORP, Optional.of(declared))).isEqualTo(new RepoCredential.Bearer("first-token"));

        Files.writeString(token, "rotated-token-2\n");
        Files.setLastModifiedTime(token, FileTime.from(Instant.now().plusSeconds(5)));
        assertThat(r.resolve("corp", CORP, Optional.of(declared)))
                .isEqualTo(new RepoCredential.Bearer("rotated-token-2"));
    }

    @Test
    void a_resolved_file_secret_is_filed_for_redaction(@TempDir Path dir) throws Exception {
        Path props = secret(dir, "secrets.properties", "corp.password=file-sourced-pass\n");
        var declared = new RepoCredential.Basic("svc", "${file:" + props + "#corp.password}");
        var r = resolver(dir, Map.of(), Map.of(), List.of(new RepositorySpec("corp", CORP, declared)));

        SessionContext.where(
                Session.defaults().withWorkingDir(dir), () -> r.resolve("corp", CORP, Optional.of(declared)));

        assertThat(ResolvedSecrets.plus(dir, SecretRedactor.none()).redact("svc:file-sourced-pass"))
                .isEqualTo("svc:" + SecretRedactor.MASK);
    }

    @Test
    void a_project_file_reference_is_refused_with_a_warning_naming_the_scheme(@TempDir Path dir) throws IOException {
        Path token = secret(dir, "corp-token", "must-not-leak");
        var declared = new RepoCredential.Bearer("${file:" + token + "}");
        var r = resolver(dir, Map.of(), Map.of("JK_REPO_CORP_HOST", "corp.example"), List.of());

        var resolved = new AtomicReference<RepoCredential>();
        String warnings = warningsFrom(() -> resolved.set(r.resolve("corp", CORP, Optional.of(declared))));

        assertThat(resolved.get()).isEqualTo(RepoCredential.ANONYMOUS);
        assertThat(warnings)
                .contains("jk.toml cannot use ${file:" + token + "}")
                .contains("move the reference to ~/.jk/config.toml")
                .doesNotContain("must-not-leak");
    }

    @Test
    void a_missing_file_is_an_error_naming_the_repository_key(@TempDir Path dir) {
        var declared = new RepoCredential.Bearer("${file:" + dir.resolve("absent") + "}");
        var r = resolver(dir, Map.of(), Map.of(), List.of(new RepositorySpec("corp", CORP, declared)));

        assertThatThrownBy(() -> r.resolve("corp", CORP, Optional.of(declared)))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("repositories.corp.token");
    }

    @Test
    void a_token_file_variable_authenticates_a_bound_repository(@TempDir Path dir) throws IOException {
        Path token = secret(dir, "ci-token", "ci-secret\n");
        var r = resolver(
                dir,
                Map.of(),
                Map.of("JK_REPO_CORP_TOKEN_FILE", token.toString(), "JK_REPO_CORP_HOST", "corp.example"),
                List.of());

        assertThat(r.resolve("corp", CORP, Optional.empty())).isEqualTo(new RepoCredential.Bearer("ci-secret"));
    }

    @Test
    void username_and_password_file_variables_make_a_basic_credential(@TempDir Path dir) throws IOException {
        Path user = secret(dir, "user", "deployer\n");
        Path pass = secret(dir, "pass", "pa55\n");
        var r = resolver(
                dir,
                Map.of(),
                Map.of(
                        "JK_REPO_CORP_USERNAME_FILE", user.toString(),
                        "JK_REPO_CORP_PASSWORD_FILE", pass.toString(),
                        "JK_REPO_CORP_HOST", "corp.example"),
                List.of());

        assertThat(r.resolve("corp", CORP, Optional.empty())).isEqualTo(new RepoCredential.Basic("deployer", "pa55"));
    }

    @Test
    void a_token_and_a_token_file_both_set_is_an_error(@TempDir Path dir) throws IOException {
        Path token = secret(dir, "ci-token", "ci-secret");
        var r = resolver(
                dir,
                Map.of("JK_REPO_CORP_TOKEN", "direct"),
                Map.of("JK_REPO_CORP_TOKEN_FILE", token.toString(), "JK_REPO_CORP_HOST", "corp.example"),
                List.of());

        assertThatThrownBy(() -> r.resolve("corp", CORP, Optional.empty()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("JK_REPO_CORP_TOKEN and JK_REPO_CORP_TOKEN_FILE are both set");
    }

    @Test
    void a_token_file_others_can_read_is_refused(@TempDir Path dir) throws IOException {
        Path token = secret(dir, "ci-token", "ci-secret");
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-r-----"));
        var r = resolver(
                dir,
                Map.of(),
                Map.of("JK_REPO_CORP_TOKEN_FILE", token.toString(), "JK_REPO_CORP_HOST", "corp.example"),
                List.of());

        assertThatThrownBy(() -> r.resolve("corp", CORP, Optional.empty()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("JK_REPO_CORP_TOKEN_FILE")
                .hasMessageContaining("chmod 600 " + token);
    }
}
