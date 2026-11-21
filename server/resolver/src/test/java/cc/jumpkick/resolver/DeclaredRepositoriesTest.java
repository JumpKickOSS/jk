// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which repositories a dependency's POM may send the resolve to. A published POM is anyone's, so
 * it may name an https repository on a network host, and a loopback one only when the project
 * itself resolves through this machine.
 */
class DeclaredRepositoriesTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file:///etc/",
                "file:/home/someone/.m2/repository/",
                "http://127.0.0.1:8080/",
                "http://localhost:9000/maven/",
                "https://localhost/maven/",
                "https://[::1]:8443/",
                "http://repo.example/maven2/",
                "s3://bucket/maven/",
                "ftp://ftp.example/maven/",
                "repo.example/maven2/"
            })
    void a_remote_project_does_not_use_a_pom_repository_off_https_or_on_this_machine(String url) {
        DeclaredRepositories declared = declaredOver(base("https://repo1.maven.org/maven2/"));
        declared.propagate("org.acme:parent", pomDeclaring(url), List.of("org.acme:child"));

        assertThat(declared.reposFor("org.acme:child").repos())
                .extracting(r -> r.baseUrl().toString())
                .containsExactly("https://repo1.maven.org/maven2/");
        assertThat(declared.notes(List.of())).singleElement().satisfies(note -> assertThat(note)
                .contains("from the POM of org.acme:parent:1.0 not used: "));
    }

    @Test
    void an_https_repository_on_a_network_host_is_used() {
        DeclaredRepositories declared = declaredOver(base("https://repo1.maven.org/maven2/"));
        declared.propagate("org.acme:parent", pomDeclaring("https://jitpack.io/"), List.of("org.acme:child"));

        assertThat(declared.reposFor("org.acme:child").repos())
                .extracting(r -> r.baseUrl().toString())
                .containsExactly("https://repo1.maven.org/maven2/", "https://jitpack.io/");
        assertThat(declared.notes(List.of())).isEmpty();
    }

    @Test
    void a_project_that_resolves_through_loopback_may_be_sent_to_loopback_by_a_pom() {
        DeclaredRepositories declared = declaredOver(base("http://127.0.0.1:41234/"));
        declared.propagate("org.acme:parent", pomDeclaring("http://127.0.0.1:41235/"), List.of("org.acme:child"));

        assertThat(declared.reposFor("org.acme:child").repos())
                .extracting(r -> r.baseUrl().toString())
                .containsExactly("http://127.0.0.1:41234/", "http://127.0.0.1:41235/");
    }

    @Test
    void a_file_tree_is_refused_even_when_the_project_resolves_through_loopback() {
        DeclaredRepositories declared = declaredOver(base("http://127.0.0.1:41234/"));
        declared.propagate("org.acme:parent", pomDeclaring("file:///etc/"), List.of("org.acme:child"));

        assertThat(declared.reposFor("org.acme:child").repos()).hasSize(1);
    }

    @Test
    void a_refused_repository_s_note_masks_the_credential_its_url_carries() {
        DeclaredRepositories declared = declaredOver(base("https://repo1.maven.org/maven2/"));
        declared.propagate(
                "org.acme:parent",
                pomDeclaring("http://alice:hunter2@repo.example/maven2/?token=abc123"),
                List.of("org.acme:child"));
        declared.reposFor("org.acme:child");

        assertThat(declared.notes(List.of())).singleElement().satisfies(note -> assertThat(note)
                .contains("(http://repo.example/maven2/?token=***)")
                .doesNotContain("hunter2")
                .doesNotContain("abc123"));
    }

    @Test
    void a_used_repository_s_note_masks_the_credential_its_url_carries() {
        String url = "https://alice:hunter2@jitpack.io/?api_key=abc123";
        DeclaredRepositories declared = declaredOver(base("https://repo1.maven.org/maven2/"));
        declared.propagate("org.acme:parent", pomDeclaring(url), List.of("org.acme:child"));
        declared.reposFor("org.acme:child");
        Lockfile.Artifact row =
                new Lockfile.Artifact("org.acme:child:jar:", "1.0", "jitpack+" + url, "sha256:00", null, List.of());

        assertThat(declared.notes(List.of(row))).singleElement().satisfies(note -> assertThat(note)
                .contains("(https://jitpack.io/?api_key=***)")
                .doesNotContain("hunter2")
                .doesNotContain("abc123"));
    }

    private RepoGroup base(String url) {
        return RepoGroup.of(new MavenRepo("central", URI.create(url), new Http(), new Cas(dir.resolve("cache"))));
    }

    private static DeclaredRepositories declaredOver(RepoGroup base) {
        return new DeclaredRepositories(base, new EffectivePomBuilder(base));
    }

    private static EffectivePom pomDeclaring(String url) {
        return new EffectivePom(
                "org.acme",
                "parent",
                "1.0",
                "jar",
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                null,
                Map.of(),
                List.of(new Pom.Repository("jitpack", url, "org.acme:parent:1.0")),
                List.of(),
                List.of(),
                List.of());
    }
}
