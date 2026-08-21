// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
}
