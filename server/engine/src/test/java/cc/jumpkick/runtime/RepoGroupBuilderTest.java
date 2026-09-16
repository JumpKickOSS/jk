// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.MavenSettings;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.task.RunNotices;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoGroupBuilderTest {

    @BeforeEach
    @AfterEach
    void forgetRunNotices() {
        RunNotices.clear();
    }

    /**
     * One run, the way the engine scopes one: a fresh {@code Session} carries a fresh
     * {@code IoLedger}, which is what {@code RunNotices} counts "once" against.
     */
    private static String inOneRun(Runnable body) {
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

    private static int occurrencesOf(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) n++;
        return n;
    }

    @Test
    void empty_declaration_defaults_to_jumpkick_central_google() {
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(Map.of());
        assertThat(effective)
                .containsExactly(RepositorySpec.JUMPKICK, RepositorySpec.MAVEN_CENTRAL, RepositorySpec.GOOGLE_MAVEN);
        assertThat(RepositorySpec.JUMPKICK.groups()).contains("cc.jumpkick", "build.jumpkick.*");
    }

    @Test
    void partial_list_prepends_jumpkick_and_appends_missing_public_baseline() {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        byName.put("corp", new RepositorySpec("corp", URI.create("https://corp.example/maven/")));
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(byName);
        assertThat(effective).extracting(RepositorySpec::name).containsExactly("jumpkick", "corp", "central", "google");
    }

    @Test
    void declared_central_and_google_urls_are_not_replaced() {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        RepositorySpec customGoogle =
                new RepositorySpec("google", URI.create("https://dl.google.com/dl/android/maven2/"));
        byName.put("google", customGoogle);
        byName.put("central", RepositorySpec.MAVEN_CENTRAL);
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(byName);
        assertThat(effective).extracting(RepositorySpec::name).containsExactly("jumpkick", "google", "central");
        assertThat(effective.stream().filter(r -> r.name().equals("google")).findFirst())
                .get()
                .extracting(RepositorySpec::url)
                .hasToString("https://dl.google.com/dl/android/maven2/");
    }

    @Test
    void project_order_keeps_jumpkick_first_among_builtins() {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        byName.put("central", RepositorySpec.MAVEN_CENTRAL);
        byName.put("internal", new RepositorySpec("internal", URI.create("https://internal.example/")));
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(byName);
        assertThat(effective)
                .extracting(RepositorySpec::name)
                .containsExactly("jumpkick", "central", "internal", "google");
    }

    @Test
    void default_remote_repos_includes_jumpkick_first() {
        assertThat(RepoGroupBuilder.defaultRemoteRepos())
                .containsExactly(RepositorySpec.JUMPKICK, RepositorySpec.MAVEN_CENTRAL, RepositorySpec.GOOGLE_MAVEN);
        assertThat(RepositorySpec.GOOGLE_MAVEN.url().toString()).contains("google");
    }

    @Test
    void official_repo_url_override_governs_the_default_remotes() {
        String prior = System.getProperty(RepositorySpec.OFFICIAL_REPO_URL_PROPERTY);
        System.setProperty(RepositorySpec.OFFICIAL_REPO_URL_PROPERTY, "http://official.invalid/mirror");
        try {
            RepositorySpec jumpkick = RepoGroupBuilder.defaultRemoteRepos().getFirst();
            assertThat(jumpkick.name()).isEqualTo("jumpkick");
            assertThat(jumpkick.url().toString()).isEqualTo("http://official.invalid/mirror/");
            assertThat(jumpkick.groups()).isEqualTo(RepositorySpec.JUMPKICK.groups());
        } finally {
            if (prior == null) {
                System.clearProperty(RepositorySpec.OFFICIAL_REPO_URL_PROPERTY);
            } else {
                System.setProperty(RepositorySpec.OFFICIAL_REPO_URL_PROPERTY, prior);
            }
        }
    }

    @Test
    void only_google_declared_still_appends_central_and_jumpkick() {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        byName.put("google", RepositorySpec.GOOGLE_MAVEN);
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(byName);
        assertThat(effective).extracting(RepositorySpec::name).containsExactly("jumpkick", "google", "central");
    }

    private static final String UNBOUND_MARKER = "multiple repositories configured without exclusive";

    private static final List<RepositorySpec> UNBOUND_PAIR = List.of(
            new RepositorySpec("central", URI.create("https://repo.maven.apache.org/maven2/")),
            new RepositorySpec("corp", URI.create("https://corp.example/maven/")));

    private static final List<List<String>> NO_BINDINGS = List.of(List.of(), List.of());

    /**
     * {@code buildFor} runs once per module per planner — twenty call sites in the engine — so a
     * warning it emits per call is a warning printed a dozen times for one fact. Both directions
     * are asserted: exactly once across many calls, and not zero.
     */
    @Test
    void the_unbound_multi_repo_warning_is_said_once_however_many_times_the_group_is_built() {
        String out = inOneRun(() -> {
            for (int i = 0; i < 15; i++) {
                RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(UNBOUND_PAIR, NO_BINDINGS);
            }
        });
        assertThat(occurrencesOf(out, UNBOUND_MARKER)).isEqualTo(1);
        assertThat(out).contains("dependency-confusion risk");
    }

    /**
     * The other half of "once per run": the engine is a resident daemon, so a note armed with a
     * process-wide flag is said to the first build after a restart and to nobody afterwards.
     */
    @Test
    void the_next_run_says_it_again() {
        assertThat(occurrencesOf(
                        inOneRun(() -> RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(UNBOUND_PAIR, NO_BINDINGS)),
                        UNBOUND_MARKER))
                .isEqualTo(1);
        assertThat(occurrencesOf(
                        inOneRun(() -> RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(UNBOUND_PAIR, NO_BINDINGS)),
                        UNBOUND_MARKER))
                .as("a second build of the same project is a second run, and gets told too")
                .isEqualTo(1);
    }

    /** A single repo, or any set with an exclusive binding, has nothing to warn about. */
    @Test
    void a_bound_or_single_repo_set_stays_quiet() {
        String out = inOneRun(() -> {
            RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(
                    List.of(RepositorySpec.MAVEN_CENTRAL), List.of(List.of()));
            RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(
                    List.of(RepositorySpec.MAVEN_CENTRAL, RepositorySpec.GOOGLE_MAVEN),
                    List.of(List.of(), RepositorySpec.GOOGLE_ANDROID_GROUPS));
            RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(
                    List.of(new RepositorySpec(
                            "internal", URI.create("https://i.example/"), null, null, List.of("com.acme"))),
                    List.of(List.of("com.acme")));
        });
        assertThat(out).doesNotContain(UNBOUND_MARKER);
    }

    private static final String USER = "alice";
    private static final String PASSWORD = "s3cr3t-nexus-token";
    private static final RepositorySpec WITH_USER_INFO =
            new RepositorySpec("nexus", URI.create("https://" + USER + ":" + PASSWORD + "@nexus.example.com/repo/"));

    /**
     * jk strips the credential out of a repository URL before using it, which is right — the JDK's
     * HTTP client never authenticates from userinfo, and the base URL is committed to
     * {@code jk-lock.toml}. Doing it silently is what left the user staring at a 401.
     */
    @Test
    void a_url_credential_with_no_other_source_warns_and_names_the_bindings_that_would_send_one() {
        String warning = RepoGroupBuilder.urlUserInfoWarning(
                WITH_USER_INFO.name(), WITH_USER_INFO.url(), RepoCredential.ANONYMOUS);

        assertThat(warning).contains("nexus").contains("anonymously").contains("401");
        // Each remedy is a binding, spelled for this repository: following it must not land in the
        // refusal warning next.
        assertThat(warning)
                .contains("`jk repo login nexus --url https://nexus.example.com/repo/`")
                .contains("[repositories.nexus] table with this URL and a ${VAR} credential in ~/.jk/config.toml")
                .contains("JK_REPO_NEXUS_TOKEN")
                .contains("JK_REPO_NEXUS_USERNAME + JK_REPO_NEXUS_PASSWORD")
                .contains("JK_REPO_NEXUS_HOST=nexus.example.com")
                .contains("~/.m2/settings.xml")
                .contains("repositories.md § Credentials");
        assertThat(warning)
                .as("a warning about a leaked credential that prints the credential is the original defect")
                .doesNotContain(USER)
                .doesNotContain(PASSWORD);
        assertThat(warning).contains("https://nexus.example.com/repo/");
    }

    /** With a credential resolved elsewhere the URL's is merely redundant — say so and move on. */
    @Test
    void a_url_credential_alongside_a_resolved_one_is_reported_as_redundant() {
        String warning = RepoGroupBuilder.urlUserInfoWarning(
                WITH_USER_INFO.name(),
                WITH_USER_INFO.url(),
                new RepoCredential.Bearer("resolved-from-the-environment"));

        assertThat(warning).contains("redundant").doesNotContain("401").doesNotContain("anonymously");
        assertThat(warning).doesNotContain(USER).doesNotContain(PASSWORD).doesNotContain("resolved-from-the-env");
    }

    /**
     * A lock rebuilds the repo group once per module over a few hundred artifacts. A warning
     * printed a few hundred times is a warning nobody reads.
     */
    @Test
    void the_warning_is_emitted_once_per_repository_and_never_for_a_clean_url() {
        String out = inOneRun(() -> {
            RepoGroupBuilder.maybeWarnUrlUserInfo(WITH_USER_INFO, RepoCredential.ANONYMOUS);
            RepoGroupBuilder.maybeWarnUrlUserInfo(WITH_USER_INFO, RepoCredential.ANONYMOUS);
            RepoGroupBuilder.maybeWarnUrlUserInfo(RepositorySpec.MAVEN_CENTRAL, RepoCredential.ANONYMOUS);
        });
        String marker = "jk: warning: repository `nexus`";
        assertThat(out).doesNotContain(PASSWORD).doesNotContain("central");
        assertThat(occurrencesOf(out, marker)).isEqualTo(1);

        // And the next build hears it too — the dedup is scoped to the run, not to the daemon.
        assertThat(occurrencesOf(
                        inOneRun(() -> RepoGroupBuilder.maybeWarnUrlUserInfo(WITH_USER_INFO, RepoCredential.ANONYMOUS)),
                        marker))
                .isEqualTo(1);
    }

    /**
     * The wiring, not only the wording: building the group for a project that declares a credential
     * in its repository URL emits the note, and the URL the repository actually uses still has the
     * credential removed.
     */
    @Test
    void building_a_group_warns_for_a_declared_url_credential(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "demo"
                name = "demo"
                version = "1.0.0"

                [repositories.nexus]
                url = "https://%s:%s@nexus.example.com/repo/"
                """.formatted(USER, PASSWORD));
        var project = JkBuildParser.parse(tmp.resolve("jk.toml"));
        Files.writeString(tmp.resolve("config.toml"), "");
        System.setProperty("jk.env.JK_HOME", tmp.toString());

        var built = new AtomicReference<RepoGroup>();
        String out;
        try {
            out = inOneRun(() ->
                    built.set(RepoGroupBuilder.buildFor(project, null, new Cas(tmp.resolve("store")), name -> null)));
        } finally {
            System.clearProperty("jk.env.JK_HOME");
        }
        RepoGroup group = built.get();
        assertThat(out)
                .contains("jk: warning: repository `nexus`")
                .doesNotContain(USER)
                .doesNotContain(PASSWORD);
        assertThat(group.repos())
                .filteredOn(repo -> "nexus".equals(repo.name()))
                .singleElement()
                .satisfies(repo -> assertThat(repo.baseUrl().toString()).isEqualTo("https://nexus.example.com/repo/"));
    }

    private static final URI CORP_BUCKET = URI.create("s3://corp-bucket/releases/");
    private static final String VICTIM_HOME = "/home/victim";

    /** The engine's resolver with every source empty but the two provenance inputs a test varies. */
    private static RepoCredentialResolver resolver(
            Path dir,
            Map<String, String> env,
            Map<String, String> hostBindings,
            List<RepositorySpec> userRepositories) {
        return new RepoCredentialResolver(
                env::get,
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                new ForgeAuth(),
                (endpoint, field, token) -> Optional.empty(),
                hostBindings::get,
                () -> userRepositories);
    }

    /** A project's {@code [repositories.corp]} on an object store, keys written as {@code cfg}. */
    private static RepositorySpec corp(ObjectStoreConfig cfg) {
        return new RepositorySpec("corp", CORP_BUCKET, null, cfg);
    }

    /**
     * The object-store hole an inline credential no longer has: a cloned project writing
     * {@code secret-key = "${HOME}"} beside its own bucket. The key is left unset — the transport
     * goes unsigned rather than signing with the caller's value — the literal key beside it stays,
     * and the once-per-run warning names the variable, never what it holds.
     */
    @Test
    void a_project_object_store_key_naming_a_foreign_variable_is_left_unset_and_warned_once(@TempDir Path dir) {
        RepositorySpec spec = corp(new ObjectStoreConfig("us-east-1", null, "AKIAEXAMPLE", "${HOME}", null));
        RepoCredentialResolver creds = resolver(dir, Map.of("HOME", VICTIM_HOME), Map.of(), List.of());
        Map<String, String> env = Map.of("HOME", VICTIM_HOME);

        var expanded = new AtomicReference<ObjectStoreConfig>();
        String out = inOneRun(() -> {
            expanded.set(RepoGroupBuilder.expandObjectStore(spec, creds, env::get));
            RepoGroupBuilder.expandObjectStore(spec, creds, env::get);
            RepoGroupBuilder.expandObjectStore(spec, creds, env::get);
        });

        assertThat(expanded.get()).isEqualTo(new ObjectStoreConfig("us-east-1", null, "AKIAEXAMPLE", null, null));
        assertThat(expanded.get().hasExplicitCredentials()).isFalse();
        assertThat(occurrencesOf(out, "interpolates ${HOME}")).isEqualTo(1);
        assertThat(out)
                .contains("repository `corp`")
                .contains("object-store keys")
                .contains("JK_REPO_CORP_TOKEN")
                .doesNotContain(VICTIM_HOME);
    }

    /** The same declaration in the user's own config is the user's word, and its value is sent. */
    @Test
    void the_same_object_store_declaration_in_the_user_config_is_expanded_and_sent(@TempDir Path dir) {
        ObjectStoreConfig declared = new ObjectStoreConfig(null, null, "AKIAEXAMPLE", "${HOME}", null);
        RepoCredentialResolver creds = resolver(dir, Map.of("HOME", VICTIM_HOME), Map.of(), List.of(corp(declared)));

        var expanded = new AtomicReference<ObjectStoreConfig>();
        String out = inOneRun(() -> expanded.set(
                RepoGroupBuilder.expandObjectStore(corp(declared), creds, Map.of("HOME", VICTIM_HOME)::get)));

        assertThat(expanded.get().secretKey()).isEqualTo(VICTIM_HOME);
        assertThat(expanded.get().hasExplicitCredentials()).isTrue();
        assertThat(out).isEmpty();
    }

    /**
     * Project and user both declare the repository at one origin: the user's object-store table is
     * the one expanded, whatever the project wrote into its own.
     */
    @Test
    void the_users_own_object_store_table_is_used_whatever_the_project_wrote(@TempDir Path dir) {
        RepositorySpec users = corp(new ObjectStoreConfig("eu-west-1", null, "${CORP_ACCESS}", "${CORP_SECRET}", null));
        RepositorySpec projects = corp(new ObjectStoreConfig(null, null, "${HOME}", "${HOME}", null));
        Map<String, String> env = Map.of("HOME", VICTIM_HOME, "CORP_ACCESS", "AKIACORP", "CORP_SECRET", "corp-secret");
        RepoCredentialResolver creds = resolver(dir, env, Map.of(), List.of(users));

        ObjectStoreConfig expanded = RepoGroupBuilder.expandObjectStore(projects, creds, env::get);

        assertThat(expanded).isEqualTo(new ObjectStoreConfig("eu-west-1", null, "AKIACORP", "corp-secret", null));
    }

    /**
     * A project may spell out the convention — the repository's own {@code JK_REPO_<ID>_*} names —
     * and under a binding they expand strictly: an unset one is a {@link JkBuildParseException}
     * naming the position, not a silent fall-through to the ambient chain. Unbound, the same
     * reference is refused like any other name-keyed source.
     */
    @Test
    void a_project_reference_to_the_repositorys_own_variables_expands_strictly_under_a_binding(@TempDir Path dir) {
        RepositorySpec spec = corp(new ObjectStoreConfig(
                "${JK_REPO_CORP_REGION}", null, "${JK_REPO_CORP_ACCESS_KEY}", "${JK_REPO_CORP_SECRET_KEY}", null));
        Map<String, String> bound = Map.of("JK_REPO_CORP_HOST", "corp-bucket");
        Map<String, String> shell = Map.of(
                "JK_REPO_CORP_REGION", "us-east-1",
                "JK_REPO_CORP_ACCESS_KEY", "AKIAEXAMPLE",
                "JK_REPO_CORP_SECRET_KEY", "s3cr3t");

        ObjectStoreConfig expanded =
                RepoGroupBuilder.expandObjectStore(spec, resolver(dir, shell, bound, List.of()), shell::get);
        assertThat(expanded).isEqualTo(new ObjectStoreConfig("us-east-1", null, "AKIAEXAMPLE", "s3cr3t", null));

        assertThatThrownBy(() -> RepoGroupBuilder.expandObjectStore(
                        spec, resolver(dir, Map.of(), bound, List.of()), var -> null))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.corp")
                .hasMessageContaining("${JK_REPO_CORP_REGION}");

        var unbound = new AtomicReference<ObjectStoreConfig>();
        String out = inOneRun(() -> unbound.set(
                RepoGroupBuilder.expandObjectStore(spec, resolver(dir, shell, Map.of(), List.of()), shell::get)));
        assertThat(unbound.get()).isEqualTo(ObjectStoreConfig.EMPTY);
        assertThat(out)
                .contains("nothing binds the name `corp`")
                .contains("JK_REPO_CORP_HOST=corp-bucket")
                .doesNotContain("s3cr3t");
    }

    /** Keys written out in full are the declaring manifest's own secret; no resolver is consulted. */
    @Test
    void literal_object_store_keys_are_used_as_written(@TempDir Path dir) {
        ObjectStoreConfig literal =
                new ObjectStoreConfig("us-east-1", "https://minio.corp:9000", "AKIA", "s3cr3t", null);

        String out = inOneRun(() -> assertThat(RepoGroupBuilder.expandObjectStore(
                        corp(literal), resolver(dir, Map.of(), Map.of(), List.of()), var -> null))
                .isSameAs(literal));

        assertThat(out).isEmpty();
    }

    /**
     * The wiring: building the group for a project whose object-store repository interpolates a
     * variable of the caller's shell says so once, and never prints the value.
     */
    @Test
    void building_a_group_refuses_a_projects_object_store_reference(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "demo"
                name = "demo"
                version = "1.0.0"

                [repositories.corp]
                url = "s3://corp-bucket/releases/"
                access-key = "AKIAEXAMPLE"
                secret-key = "${HOME}"
                """);
        var project = JkBuildParser.parse(tmp.resolve("jk.toml"));
        Files.writeString(tmp.resolve("config.toml"), "");
        System.setProperty("jk.env.JK_HOME", tmp.toString());

        String out;
        try {
            out = inOneRun(() -> RepoGroupBuilder.buildFor(
                    project, null, new Cas(tmp.resolve("store")), Map.of("HOME", VICTIM_HOME)::get));
        } finally {
            System.clearProperty("jk.env.JK_HOME");
        }
        assertThat(out)
                .contains("repository `corp`")
                .contains("interpolates ${HOME}")
                .doesNotContain(VICTIM_HOME);
    }
}
