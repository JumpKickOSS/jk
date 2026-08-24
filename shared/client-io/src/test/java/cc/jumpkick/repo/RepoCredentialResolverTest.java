// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.ForgeIdentity;
import cc.jumpkick.forge.TokenStore;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoCredentialResolverTest {

    /** Identity lookup that can't resolve a login (offline / error). */
    private static final ForgeIdentity NO_IDENTITY = (endpoint, field, token) -> Optional.empty();

    private static Function<String, String> env(Map<String, String> m) {
        return m::get;
    }

    /** A ForgeAuth with no env/CLI and an injectable token store dir. */
    private static ForgeAuth forge(TokenStore store) {
        return new ForgeAuth(store, k -> null, argv -> Optional.empty());
    }

    private static RepoCredentialResolver resolver(
            Function<String, String> env, MavenSettings settings, RepoCredentialStore store, ForgeAuth forge) {
        return new RepoCredentialResolver(env, settings, store, forge, NO_IDENTITY);
    }

    @Test
    void inline_credential_wins(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("JK_REPO_NEXUS_TOKEN", "env-tok")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)));
        RepoCredential cred = r.resolve(
                "nexus", URI.create("https://nexus.corp/repo"), Optional.of(new RepoCredential.Bearer("inline-tok")));
        assertThat(cred).isEqualTo(new RepoCredential.Bearer("inline-tok"));
    }

    @Test
    void env_token_becomes_bearer(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("JK_REPO_NEXUS_TOKEN", "env-tok")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)));
        assertThat(r.resolve("nexus", URI.create("https://nexus.corp/repo"), Optional.empty()))
                .isEqualTo(new RepoCredential.Bearer("env-tok"));
    }

    @Test
    void env_username_password_becomes_basic(@TempDir Path dir) {
        var r = resolver(
                env(Map.of(
                        "JK_REPO_CORP_NEXUS_USERNAME", "deployer",
                        "JK_REPO_CORP_NEXUS_PASSWORD", "s3cr3t")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)));
        // Note: the id "corp-nexus" sanitizes to CORP_NEXUS for the env var.
        assertThat(r.resolve("corp-nexus", URI.create("https://nexus.corp/repo"), Optional.empty()))
                .isEqualTo(new RepoCredential.Basic("deployer", "s3cr3t"));
    }

    @Test
    void store_used_when_no_inline_or_env(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write("nexus", new RepoCredential.Bearer("stored-tok"));
        var r = resolver(env(Map.of()), MavenSettings.empty(), store, forge(new TokenStore(dir)));
        assertThat(r.resolve("nexus", URI.create("https://nexus.corp/repo"), Optional.empty()))
                .isEqualTo(new RepoCredential.Bearer("stored-tok"));
    }

    @Test
    void github_packages_bridge_uses_basic_with_resolved_login(@TempDir Path dir) {
        // A prior `jk auth login github` left a token; GitHub Packages reuses it
        // as HTTP Basic with the account login as username.
        var forgeStore = new TokenStore(dir);
        forgeStore.write("github.com", "gho_pkgtoken");
        ForgeIdentity octocat = (endpoint, field, token) -> Optional.of("octocat");
        var r = new RepoCredentialResolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir.resolve("repocreds")),
                forge(forgeStore),
                octocat);

        RepoCredential cred =
                r.resolve("ghp", URI.create("https://maven.pkg.github.com/JumpKickOSS/jk"), Optional.empty());
        assertThat(cred).isEqualTo(new RepoCredential.Basic("octocat", "gho_pkgtoken"));
    }

    @Test
    void github_packages_bridge_falls_back_to_bearer_when_login_unavailable(@TempDir Path dir) {
        // Offline / API error → no login → fall back to Bearer (no worse than before).
        var forgeStore = new TokenStore(dir);
        forgeStore.write("github.com", "gho_pkgtoken");
        var r = resolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir.resolve("repocreds")),
                forge(forgeStore)); // NO_IDENTITY

        RepoCredential cred =
                r.resolve("ghp", URI.create("https://maven.pkg.github.com/JumpKickOSS/jk"), Optional.empty());
        assertThat(cred).isEqualTo(new RepoCredential.Bearer("gho_pkgtoken"));
    }

    @Test
    void gitlab_packages_bridge_uses_bearer(@TempDir Path dir) {
        // GitLab's package API takes the device-flow OAuth token as a Bearer.
        var forgeStore = new TokenStore(dir);
        forgeStore.write("gitlab.com", "glpat-or-oauth");
        var r = resolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir.resolve("repocreds")),
                forge(forgeStore));

        RepoCredential cred =
                r.resolve("gl", URI.create("https://gitlab.com/api/v4/projects/1/packages/maven"), Optional.empty());
        assertThat(cred).isEqualTo(new RepoCredential.Bearer("glpat-or-oauth"));
    }

    @Test
    void anonymous_when_nothing_matches(@TempDir Path dir) {
        var r = resolver(
                env(Map.of()), MavenSettings.empty(), new RepoCredentialStore(dir), forge(new TokenStore(dir)));
        assertThat(r.resolve("central", URI.create("https://repo.maven.apache.org/maven2/"), Optional.empty()))
                .isEqualTo(RepoCredential.ANONYMOUS);
    }

    // ---- ${VAR} expansion, moved here from the parse------------------

    @Test
    void inline_credentials_expand_env_references(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("REPO_USER", "alice", "REPO_PASS", "s3cret")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir.resolve("tokens"))));

        var resolved = r.resolve(
                "internal",
                URI.create("https://repo.example/maven"),
                Optional.of(new RepoCredential.Basic("${REPO_USER}", "${REPO_PASS}")));

        assertThat(resolved).isInstanceOf(RepoCredential.Basic.class);
        var basic = (RepoCredential.Basic) resolved;
        assertThat(basic.username()).isEqualTo("alice");
        assertThat(basic.password()).isEqualTo("s3cret");
    }

    @Test
    void a_bearer_token_expands_too(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("TOK", "abc123")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir.resolve("tokens"))));

        var resolved = r.resolve(
                "internal", URI.create("https://repo.example/maven"), Optional.of(new RepoCredential.Bearer("${TOK}")));

        assertThat(((RepoCredential.Bearer) resolved).token()).isEqualTo("abc123");
    }

    @Test
    void expansion_composes_with_surrounding_text(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("USER", "bob")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir.resolve("tokens"))));

        var resolved = r.resolve(
                "internal",
                URI.create("https://repo.example/maven"),
                Optional.of(new RepoCredential.Basic("svc-${USER}-ci", "p")));

        assertThat(((RepoCredential.Basic) resolved).username()).isEqualTo("svc-bob-ci");
    }

    @Test
    void an_unset_variable_is_an_error_rather_than_anonymous_access(@TempDir Path dir) {
        // Silent emptiness would authenticate anonymously against a private repository, which looks
        // like a permissions problem much later. Strict — just at point of use now, not at parse.
        var r = resolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir.resolve("tokens"))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> r.resolve(
                        "internal",
                        URI.create("https://repo.example/maven"),
                        Optional.of(new RepoCredential.Basic("${MISSING}", "p"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING")
                .hasMessageContaining("internal");
    }

    @Test
    void a_credential_without_references_is_untouched(@TempDir Path dir) {
        var r = resolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir.resolve("tokens"))));

        var resolved = r.resolve(
                "internal",
                URI.create("https://repo.example/maven"),
                Optional.of(new RepoCredential.Basic("plain", "pass")));

        assertThat(((RepoCredential.Basic) resolved).username()).isEqualTo("plain");
    }

    /**
     * The resolver is the one place that holds a repository credential before anything can print
     * it, so it is where the redactor is told. No {@code .env} names {@code JK_REPO_NEXUS_TOKEN} —
     * in CI nothing does — and masking it is still not a guess about the name.
     */
    @Test
    void a_resolved_token_is_filed_for_the_workspace_that_resolved_it(@TempDir Path dir) throws Exception {
        ResolvedSecrets.clear();
        var r = resolver(
                env(Map.of("JK_REPO_NEXUS_TOKEN", "ci-only-nexus-token")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir.resolve("tokens"))));

        SessionContext.where(
                Session.defaults().withWorkingDir(dir),
                () -> r.resolve("nexus", URI.create("https://nexus.corp/repo"), Optional.empty()));

        assertThat(ResolvedSecrets.plus(dir, SecretRedactor.none()).redact("401 for Bearer ci-only-nexus-token"))
                .isEqualTo("401 for Bearer " + SecretRedactor.MASK);
        ResolvedSecrets.clear();
    }

    /** Basic's password is the secret half; its username is a name, and masking names blanks output. */
    @Test
    void a_basic_password_is_filed_and_its_username_is_not(@TempDir Path dir) throws Exception {
        ResolvedSecrets.clear();
        var r = resolver(
                env(Map.of("JK_REPO_NEXUS_USERNAME", "alice-the-user", "JK_REPO_NEXUS_PASSWORD", "p4ssw0rd-secret")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir.resolve("tokens"))));

        SessionContext.where(
                Session.defaults().withWorkingDir(dir),
                () -> r.resolve("nexus", URI.create("https://nexus.corp/repo"), Optional.empty()));

        assertThat(ResolvedSecrets.plus(dir, SecretRedactor.none()).redact("alice-the-user:p4ssw0rd-secret"))
                .isEqualTo("alice-the-user:" + SecretRedactor.MASK);
        ResolvedSecrets.clear();
    }

    /** Anonymous access has nothing to mask, and an empty redactor is what proves it. */
    @Test
    void an_anonymous_resolution_files_nothing(@TempDir Path dir) throws Exception {
        ResolvedSecrets.clear();
        var r = resolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir.resolve("tokens"))));

        SessionContext.where(
                Session.defaults().withWorkingDir(dir),
                () -> r.resolve("central", URI.create("https://repo.maven.apache.org/maven2/"), Optional.empty()));

        assertThat(ResolvedSecrets.plus(dir, SecretRedactor.none()).isEmpty()).isTrue();
        ResolvedSecrets.clear();
    }

    /** The env prefix a message quotes is the one the lookup uses — one spelling, one owner. */
    @Test
    void the_env_prefix_is_published_for_diagnostics() {
        assertThat(RepoCredentialResolver.envVarPrefix("my-nexus")).isEqualTo("JK_REPO_MY_NEXUS_");
    }
}
