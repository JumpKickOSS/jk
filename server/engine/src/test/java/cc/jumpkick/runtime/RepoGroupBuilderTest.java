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
import cc.jumpkick.host.Log;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.RepositorySpec;
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
        System.setProperty(RepositorySpec.OFFICIAL_REPO_URL_PROPERTY, "http://127.0.0.1:1/mirror");
        try {
            RepositorySpec jumpkick = RepoGroupBuilder.defaultRemoteRepos().getFirst();
            assertThat(jumpkick.name()).isEqualTo("jumpkick");
            assertThat(jumpkick.url().toString()).isEqualTo("http://127.0.0.1:1/mirror/");
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
                    List.of(List.of(), RepositorySpec.GOOGLE_ANDROID_EXCLUSIVE_GROUPS));
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
    void a_url_credential_with_no_other_source_warns_and_names_the_alternatives() {
        String warning = RepoGroupBuilder.urlUserInfoWarning(
                WITH_USER_INFO.name(), SafeUri.forMessage(WITH_USER_INFO.url()), RepoCredential.ANONYMOUS);

        assertThat(warning).contains("nexus").contains("anonymously").contains("401");
        assertThat(warning)
                .contains("JK_REPO_NEXUS_TOKEN")
                .contains("JK_REPO_NEXUS_USERNAME")
                .contains("JK_REPO_NEXUS_PASSWORD")
                .contains("jk repo login nexus")
                .contains("~/.m2/settings.xml");
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
                SafeUri.forMessage(WITH_USER_INFO.url()),
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

    /**
     * Object-store {@code ${VAR}} expansion is the owner's STRICT policy against the request env:
     * an unset variable is a {@link JkBuildParseException} naming the {@code repositories.<name>}
     * position, and a set one resolves from the injected lookup, never the real environ.
     */
    @Test
    void object_store_vars_expand_strictly_against_the_request_env() {
        ObjectStoreConfig cfg = new ObjectStoreConfig("${JK_TEST_S3_REGION}", null, "${JK_TEST_S3_ACCESS}", null, null);

        assertThatThrownBy(() -> RepoGroupBuilder.expandObjectStore("corp", cfg, var -> null))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.corp")
                .hasMessageContaining("${JK_TEST_S3_REGION}");

        Map<String, String> env = Map.of("JK_TEST_S3_REGION", "us-east-1", "JK_TEST_S3_ACCESS", "AKIAEXAMPLE");
        ObjectStoreConfig expanded = RepoGroupBuilder.expandObjectStore("corp", cfg, env::get);
        assertThat(expanded.region()).isEqualTo("us-east-1");
        assertThat(expanded.accessKey()).isEqualTo("AKIAEXAMPLE");
        assertThat(expanded.endpoint()).isNull();
    }
}
