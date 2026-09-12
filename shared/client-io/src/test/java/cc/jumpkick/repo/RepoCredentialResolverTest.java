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
import cc.jumpkick.host.Log;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.task.RunNotices;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoCredentialResolverTest {

    /** Identity lookup that can't resolve a login (offline / error). */
    private static final ForgeIdentity NO_IDENTITY = (endpoint, field, token) -> Optional.empty();

    private static final URI NEXUS = URI.create("https://nexus.corp/repo");

    @BeforeEach
    @AfterEach
    void forgetRunNotices() {
        RunNotices.clear();
    }

    private static Function<String, @Nullable String> env(Map<String, String> m) {
        return m::get;
    }

    /** A ForgeAuth with no env/CLI and an injectable token store dir. */
    private static ForgeAuth forge(TokenStore store) {
        return new ForgeAuth(store, k -> null, argv -> Optional.empty());
    }

    /** No host bindings and no user-config repositories: every name-keyed source is unbound. */
    private static RepoCredentialResolver resolver(
            Function<String, @Nullable String> env,
            MavenSettings settings,
            RepoCredentialStore store,
            ForgeAuth forge) {
        return resolver(env, settings, store, forge, Map.of(), List.of());
    }

    private static RepoCredentialResolver resolver(
            Function<String, @Nullable String> env,
            MavenSettings settings,
            RepoCredentialStore store,
            ForgeAuth forge,
            Map<String, String> hostBindings,
            List<RepositorySpec> userRepositories) {
        return new RepoCredentialResolver(
                env, settings, store, forge, NO_IDENTITY, hostBindings::get, () -> userRepositories);
    }

    /** {@code JK_REPO_NEXUS_HOST=nexus.corp} — the binding a CI shell exports beside the token. */
    private static final Map<String, String> NEXUS_HOST = Map.of("JK_REPO_NEXUS_HOST", "nexus.corp");

    /** One run, the way the engine scopes one, with the log captured: what the user would see. */
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
    void env_token_becomes_bearer_when_the_shell_binds_the_name_to_the_host(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("JK_REPO_NEXUS_TOKEN", "env-tok")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)),
                NEXUS_HOST,
                List.of());
        assertThat(r.resolve("nexus", NEXUS, Optional.empty())).isEqualTo(new RepoCredential.Bearer("env-tok"));
    }

    @Test
    void env_username_password_becomes_basic_when_the_user_config_declares_the_repository(@TempDir Path dir) {
        var r = resolver(
                env(Map.of(
                        "JK_REPO_CORP_NEXUS_USERNAME", "deployer",
                        "JK_REPO_CORP_NEXUS_PASSWORD", "s3cr3t")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)),
                Map.of(),
                List.of(new RepositorySpec("corp-nexus", URI.create("https://nexus.corp/other-path/"))));
        // Note: the id "corp-nexus" sanitizes to CORP_NEXUS for the env var.
        assertThat(r.resolve("corp-nexus", NEXUS, Optional.empty()))
                .isEqualTo(new RepoCredential.Basic("deployer", "s3cr3t"));
    }

    @Test
    void store_used_when_no_inline_or_env_and_the_login_was_for_this_origin(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write("nexus", new RepoCredential.Bearer("stored-tok"), URI.create("https://nexus.corp/somewhere"));
        var r = resolver(env(Map.of()), MavenSettings.empty(), store, forge(new TokenStore(dir)));
        assertThat(r.resolve("nexus", NEXUS, Optional.empty())).isEqualTo(new RepoCredential.Bearer("stored-tok"));
    }

    // ---- a name is not a destination ----------------------------------------------------------

    /**
     * The attack this guards: a cloned project whose manifest declares the user's {@code ossrh} id
     * at its own host. The login was for Sonatype; the request is for the attacker; nothing is
     * sent, and the warning names both so the redirect is visible.
     */
    @Test
    void a_stored_login_for_another_host_is_not_sent_and_the_warning_names_both(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write(
                "ossrh",
                new RepoCredential.Basic("victim", "sonatype-pass"),
                URI.create("https://s01.oss.sonatype.org/"));
        var r = resolver(env(Map.of()), MavenSettings.empty(), store, forge(new TokenStore(dir)));
        URI attacker = URI.create("https://attacker.example/m2/");

        var resolved = new AtomicReference<RepoCredential>();
        String warnings = warningsFrom(() -> resolved.set(r.resolve("ossrh", attacker, Optional.empty())));

        assertThat(resolved.get()).isEqualTo(RepoCredential.ANONYMOUS);
        assertThat(warnings)
                .contains("repository `ossrh` at https://attacker.example is accessed anonymously")
                .contains(
                        "`jk repo login ossrh` stored it for https://s01.oss.sonatype.org, not https://attacker.example")
                .doesNotContain("sonatype-pass");
    }

    @Test
    void an_env_credential_for_a_project_declared_repository_is_refused_with_the_bindings_that_would_send_it(
            @TempDir Path dir) {
        var r = resolver(
                env(Map.of("JK_REPO_OSSRH_TOKEN", "victim-token")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)));
        URI attacker = URI.create("https://attacker.example/m2/");

        var resolved = new AtomicReference<RepoCredential>();
        String warnings = warningsFrom(() -> resolved.set(r.resolve("ossrh", attacker, Optional.empty())));

        assertThat(resolved.get()).isEqualTo(RepoCredential.ANONYMOUS);
        assertThat(warnings)
                .contains("a credential for that name exists in JK_REPO_OSSRH_TOKEN")
                .contains("declared by the project, not by ~/.jk/config.toml")
                .contains("JK_REPO_OSSRH_HOST=attacker.example")
                .contains("`jk repo login ossrh --url https://attacker.example/m2/`")
                .doesNotContain("victim-token");
    }

    @Test
    void a_user_config_declaration_at_another_origin_refuses_the_project_url(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("JK_REPO_INTERNAL_TOKEN", "tok")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)),
                Map.of(
                        "JK_REPO_INTERNAL_HOST",
                        "attacker.example"), // the project's .env cannot reach here, but even a shell binding loses to
                // the user's declaration
                List.of(new RepositorySpec("internal", URI.create("https://repo.acme.com/maven"))));
        URI attacker = URI.create("https://attacker.example/maven");

        var resolved = new AtomicReference<RepoCredential>();
        String warnings = warningsFrom(() -> resolved.set(r.resolve("internal", attacker, Optional.empty())));

        assertThat(resolved.get()).isEqualTo(RepoCredential.ANONYMOUS);
        assertThat(warnings)
                .contains(
                        "~/.jk/config.toml declares `internal` at https://repo.acme.com, not https://attacker.example");
    }

    @Test
    void a_host_binding_that_names_another_host_is_a_mismatch(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("JK_REPO_NEXUS_TOKEN", "tok")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)),
                Map.of("JK_REPO_NEXUS_HOST", "nexus.corp:8443"),
                List.of());

        var resolved = new AtomicReference<RepoCredential>();
        String warnings = warningsFrom(() -> resolved.set(r.resolve("nexus", NEXUS, Optional.empty())));

        assertThat(resolved.get()).isEqualTo(RepoCredential.ANONYMOUS);
        assertThat(warnings).contains("JK_REPO_NEXUS_HOST names nexus.corp:8443, not nexus.corp");
    }

    @Test
    void a_host_binding_may_carry_a_port_or_be_a_full_url() {
        assertThat(RepoCredentialResolver.hostMatches("nexus.corp", URI.create("https://Nexus.Corp/x")))
                .isTrue();
        assertThat(RepoCredentialResolver.hostMatches("nexus.corp:443", URI.create("https://nexus.corp/x")))
                .isTrue();
        assertThat(RepoCredentialResolver.hostMatches("nexus.corp:8443", URI.create("https://nexus.corp:8443/x")))
                .isTrue();
        assertThat(RepoCredentialResolver.hostMatches("https://nexus.corp", URI.create("https://nexus.corp/x")))
                .isTrue();
        assertThat(RepoCredentialResolver.hostMatches("http://nexus.corp", URI.create("https://nexus.corp/x")))
                .as("a full URL binds the scheme too")
                .isFalse();
        assertThat(RepoCredentialResolver.hostMatches("nexus.corp", URI.create("https://evil.example/x")))
                .isFalse();
    }

    /** A container registry is logged into by host; the id being the host is the binding. */
    @Test
    void an_id_that_is_the_host_binds_itself(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("JK_REPO_GHCR_IO_TOKEN", "ghcr-tok", "JK_REPO_127_0_0_1_5000_TOKEN", "local-tok")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)));
        assertThat(r.resolve("ghcr.io", URI.create("https://ghcr.io"), Optional.empty()))
                .isEqualTo(new RepoCredential.Bearer("ghcr-tok"));
        assertThat(r.resolve("127.0.0.1:5000", URI.create("https://127.0.0.1:5000"), Optional.empty()))
                .isEqualTo(new RepoCredential.Bearer("local-tok"));
    }

    /** A login stored without an origin is bound the way the environment is, by declaration or shell. */
    @Test
    void a_stored_login_without_an_origin_follows_the_name_bindings(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("nexus"), "bearer\nlegacy-tok\n");
        var unbound = resolver(
                env(Map.of()), MavenSettings.empty(), new RepoCredentialStore(dir), forge(new TokenStore(dir)));
        var bound = resolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)),
                NEXUS_HOST,
                List.of());

        assertThat(unbound.resolve("nexus", NEXUS, Optional.empty())).isEqualTo(RepoCredential.ANONYMOUS);
        assertThat(bound.resolve("nexus", NEXUS, Optional.empty())).isEqualTo(new RepoCredential.Bearer("legacy-tok"));
    }

    @Test
    void a_settings_xml_server_is_sent_only_when_the_name_is_bound(@TempDir Path dir) throws Exception {
        Path settings = dir.resolve("settings.xml");
        Files.writeString(settings, """
                <settings><servers><server><id>nexus</id><username>u</username><password>p</password></server></servers></settings>
                """);
        var unbound = resolver(
                env(Map.of()),
                MavenSettings.loadFrom(settings),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)));
        var bound = resolver(
                env(Map.of()),
                MavenSettings.loadFrom(settings),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)),
                NEXUS_HOST,
                List.of());

        var resolved = new AtomicReference<RepoCredential>();
        String warnings = warningsFrom(() -> resolved.set(unbound.resolve("nexus", NEXUS, Optional.empty())));
        assertThat(resolved.get()).isEqualTo(RepoCredential.ANONYMOUS);
        assertThat(warnings).contains("~/.m2/settings.xml <server> `nexus`");
        assertThat(bound.resolve("nexus", NEXUS, Optional.empty())).isEqualTo(new RepoCredential.Basic("u", "p"));
    }

    /** A refused source does not end the search: a login bound to this origin still answers. */
    @Test
    void a_refused_env_credential_falls_through_to_a_bound_login(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write("nexus", new RepoCredential.Bearer("stored-tok"), NEXUS);
        var r = resolver(
                env(Map.of("JK_REPO_NEXUS_TOKEN", "env-tok")),
                MavenSettings.empty(),
                store,
                forge(new TokenStore(dir)));

        var resolved = new AtomicReference<RepoCredential>();
        warningsFrom(() -> resolved.set(r.resolve("nexus", NEXUS, Optional.empty())));

        assertThat(resolved.get()).isEqualTo(new RepoCredential.Bearer("stored-tok"));
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
                octocat,
                k -> null,
                List::of);

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
                forge(new TokenStore(dir.resolve("tokens"))),
                NEXUS_HOST,
                List.of());

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
                forge(new TokenStore(dir.resolve("tokens"))),
                NEXUS_HOST,
                List.of());

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
