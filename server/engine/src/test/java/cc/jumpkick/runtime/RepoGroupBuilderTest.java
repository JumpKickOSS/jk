// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoGroup;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoGroupBuilderTest {

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

    @Test
    void multi_repo_without_groups_emits_warn() {
        List<RepositorySpec> multi = List.of(
                new RepositorySpec("central", URI.create("https://repo.maven.apache.org/maven2/")),
                new RepositorySpec("corp", URI.create("https://corp.example/maven/")));
        // Smoke: does not throw; warn goes to stderr (once per call).
        RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(multi, List.of(List.of(), List.of()));
        RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(List.of(RepositorySpec.MAVEN_CENTRAL), List.of(List.of()));
        // With exclusive bindings (JumpKick/Google defaults or user groups) — no warn.
        RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(
                List.of(RepositorySpec.MAVEN_CENTRAL, RepositorySpec.GOOGLE_MAVEN),
                List.of(List.of(), RepositorySpec.GOOGLE_ANDROID_EXCLUSIVE_GROUPS));
        RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(
                List.of(new RepositorySpec(
                        "internal",
                        URI.create("https://i.example/"),
                        Optional.empty(),
                        Optional.empty(),
                        List.of("com.acme"))),
                List.of(List.of("com.acme")));
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
        var err = new ByteArrayOutputStream();
        var original = System.err;
        RepoGroupBuilder.resetUserInfoWarnings();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            RepoGroupBuilder.maybeWarnUrlUserInfo(WITH_USER_INFO, RepoCredential.ANONYMOUS);
            RepoGroupBuilder.maybeWarnUrlUserInfo(WITH_USER_INFO, RepoCredential.ANONYMOUS);
            RepoGroupBuilder.maybeWarnUrlUserInfo(RepositorySpec.MAVEN_CENTRAL, RepoCredential.ANONYMOUS);
        } finally {
            System.setErr(original);
        }
        String out = err.toString(StandardCharsets.UTF_8);
        String marker = "jk: warning: repository `nexus`";
        assertThat(out).contains(marker).doesNotContain(PASSWORD).doesNotContain("central");
        assertThat(out.indexOf(marker)).isEqualTo(out.lastIndexOf(marker));
        RepoGroupBuilder.resetUserInfoWarnings();
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
        System.setProperty("jk.env.JK_CONFIG_FILE", tmp.resolve("config.toml").toString());
        RepoGroupBuilder.resetUserInfoWarnings();

        var err = new ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        RepoGroup group;
        try {
            group = RepoGroupBuilder.buildFor(project, null, new Cas(tmp.resolve("store")), name -> null);
        } finally {
            System.setErr(original);
            System.clearProperty("jk.env.JK_CONFIG_FILE");
            RepoGroupBuilder.resetUserInfoWarnings();
        }

        String out = err.toString(StandardCharsets.UTF_8);
        assertThat(out)
                .contains("jk: warning: repository `nexus`")
                .doesNotContain(USER)
                .doesNotContain(PASSWORD);
        assertThat(group.repos())
                .filteredOn(repo -> "nexus".equals(repo.name()))
                .singleElement()
                .satisfies(repo -> assertThat(repo.baseUrl().toString()).isEqualTo("https://nexus.example.com/repo/"));
    }
}
