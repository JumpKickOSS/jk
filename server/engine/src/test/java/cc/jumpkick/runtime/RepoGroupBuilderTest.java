// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepoGroupBuilderTest {

    @Test
    void empty_declaration_defaults_to_central_then_google() {
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(Map.of());
        assertThat(effective).containsExactly(RepositorySpec.MAVEN_CENTRAL, RepositorySpec.GOOGLE_MAVEN);
    }

    @Test
    void partial_list_appends_missing_public_baseline() {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        byName.put("corp", new RepositorySpec("corp", URI.create("https://corp.example/maven/")));
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(byName);
        assertThat(effective)
                .extracting(RepositorySpec::name)
                .containsExactly("corp", "central", "google");
    }

    @Test
    void declared_central_and_google_urls_are_not_replaced() {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        RepositorySpec customGoogle =
                new RepositorySpec("google", URI.create("https://dl.google.com/dl/android/maven2/"));
        byName.put("google", customGoogle);
        byName.put("central", RepositorySpec.MAVEN_CENTRAL);
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(byName);
        assertThat(effective).containsExactly(customGoogle, RepositorySpec.MAVEN_CENTRAL);
        assertThat(effective.getFirst().url()).hasToString("https://dl.google.com/dl/android/maven2/");
    }

    @Test
    void project_order_is_preserved_when_appending_defaults() {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        byName.put("central", RepositorySpec.MAVEN_CENTRAL);
        byName.put("internal", new RepositorySpec("internal", URI.create("https://internal.example/")));
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(byName);
        assertThat(effective)
                .extracting(RepositorySpec::name)
                .containsExactly("central", "internal", "google");
    }

    @Test
    void default_remote_repos_constant_is_central_then_google() {
        assertThat(RepoGroupBuilder.DEFAULT_REMOTE_REPOS)
                .containsExactly(RepositorySpec.MAVEN_CENTRAL, RepositorySpec.GOOGLE_MAVEN);
        assertThat(RepositorySpec.GOOGLE_MAVEN.url().toString()).contains("google");
    }

    @Test
    void only_google_declared_still_appends_central() {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        byName.put("google", RepositorySpec.GOOGLE_MAVEN);
        List<RepositorySpec> effective = RepoGroupBuilder.effectiveRepos(byName);
        assertThat(effective)
                .extracting(RepositorySpec::name)
                .containsExactly("google", "central");
    }

    @Test
    void multi_repo_without_groups_emits_warn() {
        List<RepositorySpec> multi = List.of(
                new RepositorySpec("central", URI.create("https://repo.maven.apache.org/maven2/")),
                new RepositorySpec("corp", URI.create("https://corp.example/maven/")));
        // Smoke: does not throw; warn goes to stderr (once per call).
        RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(multi);
        RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(List.of(RepositorySpec.MAVEN_CENTRAL));
        RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(List.of(
                new RepositorySpec(
                        "internal",
                        URI.create("https://i.example/"),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        List.of("com.acme"))));
    }
}
