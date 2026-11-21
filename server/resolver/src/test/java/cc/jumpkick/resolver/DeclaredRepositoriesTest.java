// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which repositories a dependency's POM may send the resolve to. A published POM is anyone's, so
 * it may name an https repository on a public host, a loopback one only when the project itself
 * resolves through this machine, and one whose host resolves to an internal address only when the
 * project already resolves through that host. Names resolve through a table here, never DNS.
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
    void under_pom_repositories_ignore_no_pom_repository_is_granted_and_each_is_noted_once() {
        DeclaredRepositories declared = declaredOver(base("https://repo1.maven.org/maven2/"));
        declared.ignoreAll();

        assertThat(declared.propagate(
                        "org.acme:parent", pomDeclaring("https://jitpack.io/"), List.of("org.acme:child")))
                .isEmpty();
        declared.propagate("org.acme:parent", pomDeclaring("https://jitpack.io/"), List.of("org.acme:other"));
        declared.propagate("org.acme:parent", pomDeclaring("https://jitpack.io/"), List.of());

        assertThat(declared.grants()).isEmpty();
        assertThat(declared.byUrl()).isEmpty();
        assertThat(declared.reposFor("org.acme:child").repos())
                .extracting(r -> r.baseUrl().toString())
                .containsExactly("https://repo1.maven.org/maven2/");
        assertThat(declared.notes(List.of()))
                .containsExactly("repository `jitpack` (https://jitpack.io/) from the POM of org.acme:parent:1.0"
                        + " not used: [resolve] pom-repositories = \"ignore\"");
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "10.1.2.3",
                "172.16.0.9",
                "192.168.1.20",
                "169.254.169.254",
                "100.64.0.1",
                "0.0.0.0",
                "fd00::1",
                "fe80::1",
                "93.184.215.14,10.0.0.7"
            })
    void a_pom_repository_whose_host_resolves_to_an_internal_address_is_not_used(String resolvesTo) {
        DeclaredRepositories declared =
                declaredOver(base("https://repo1.maven.org/maven2/"), Map.of("repo.evil.example", resolvesTo));
        declared.propagate(
                "org.acme:parent", pomDeclaring("https://repo.evil.example/maven2/"), List.of("org.acme:child"));

        assertThat(declared.reposFor("org.acme:child").repos())
                .extracting(r -> r.baseUrl().toString())
                .containsExactly("https://repo1.maven.org/maven2/");
        assertThat(declared.notes(List.of())).singleElement().satisfies(note -> assertThat(note)
                .contains("from the POM of org.acme:parent:1.0 not used: it resolves to an internal address (")
                .endsWith("declare it in [repositories] to use it"));
    }

    @Test
    void a_pom_repository_whose_host_resolves_to_loopback_is_used_only_when_the_project_is_on_loopback() {
        Map<String, String> table = Map.of("lo.example", "127.0.0.1");
        DeclaredRepositories remote = declaredOver(base("https://repo1.maven.org/maven2/"), table);
        remote.propagate("org.acme:parent", pomDeclaring("https://lo.example/"), List.of("org.acme:child"));
        DeclaredRepositories local = declaredOver(base("http://127.0.0.1:41234/"), table);
        local.propagate("org.acme:parent", pomDeclaring("https://lo.example/"), List.of("org.acme:child"));

        assertThat(remote.reposFor("org.acme:child").repos()).hasSize(1);
        assertThat(remote.notes(List.of())).singleElement().satisfies(note -> assertThat(note)
                .contains("not used: it resolves to this machine (127.0.0.1)"));
        assertThat(local.reposFor("org.acme:child").repos()).hasSize(2);
    }

    @Test
    void a_used_pom_repository_whose_host_resolves_inward_later_is_refused_before_the_request_and_passed_over() {
        // Loopback, so the test launcher's deny list spares the name and only the address check speaks.
        Map<String, String> table = new ConcurrentHashMap<>(Map.of("localhost", "127.0.0.1"));
        DeclaredRepositories declared = declaredOver(base("http://127.0.0.1:41234/"), table);
        declared.propagate(
                "org.acme:parent", pomDeclaring("https://localhost:41234/maven2/"), List.of("org.acme:child"));
        MavenRepo fromPom = declared.reposFor("org.acme:child").repos().getLast();
        assertThat(fromPom.baseUrl()).hasToString("https://localhost:41234/maven2/");

        table.put("localhost", "10.0.0.7");

        assertThatThrownBy(() -> fromPom.availableVersions(Coordinate.of("org.acme", "child", "1.0")))
                .isInstanceOf(MavenRepo.RepositoryUnreachableException.class)
                .hasMessageContaining("it resolves to an internal address (10.0.0.7)");
        assertThat(fromPom.passedOver()).isTrue();
        assertThat(fromPom.unreachableFault())
                .isEqualTo("JumpKick refuses it: it resolves to an internal address (10.0.0.7);"
                        + " declare it in [repositories] to use it");
    }

    @Test
    void an_internal_literal_address_is_not_used() {
        DeclaredRepositories declared = declaredOver(base("https://repo1.maven.org/maven2/"));
        declared.propagate(
                "org.acme:parent", pomDeclaring("https://10.20.30.40:8443/maven/"), List.of("org.acme:child"));

        assertThat(declared.reposFor("org.acme:child").repos()).hasSize(1);
        assertThat(declared.notes(List.of())).singleElement().satisfies(note -> assertThat(note)
                .contains("not used: it resolves to an internal address (10.20.30.40)"));
    }

    @Test
    void a_host_the_project_already_resolves_through_may_be_internal() {
        Map<String, String> table = Map.of("nexus.corp.example", "10.0.0.5");
        DeclaredRepositories declared = declaredOver(base("https://nexus.corp.example/releases/"), table);
        declared.propagate(
                "org.acme:parent", pomDeclaring("https://NEXUS.corp.example/thirdparty/"), List.of("org.acme:child"));

        assertThat(declared.reposFor("org.acme:child").repos())
                .extracting(r -> r.baseUrl().toString())
                .containsExactly("https://nexus.corp.example/releases/", "https://NEXUS.corp.example/thirdparty/");
        assertThat(declared.notes(List.of())).isEmpty();
    }

    @Test
    void a_public_host_and_a_name_that_does_not_resolve_are_used() {
        DeclaredRepositories declared = declaredOver(
                base("https://repo1.maven.org/maven2/"), Map.of("jitpack.io", "93.184.215.14,2606:2800:21f:cb07::1"));
        declared.propagate("org.acme:parent", pomDeclaring("https://jitpack.io/"), List.of("org.acme:child"));
        declared.propagate("org.acme:other", pomDeclaring("https://nowhere.invalid/"), List.of("org.acme:leaf"));

        assertThat(declared.reposFor("org.acme:child").repos()).hasSize(2);
        assertThat(declared.reposFor("org.acme:leaf").repos()).hasSize(2);
        assertThat(declared.notes(List.of())).isEmpty();
    }

    private RepoGroup base(String url) {
        return RepoGroup.of(new MavenRepo("central", URI.create(url), new Http(), new Cas(dir.resolve("cache"))));
    }

    private static DeclaredRepositories declaredOver(RepoGroup base) {
        return declaredOver(base, Map.of());
    }

    /** Over {@code table}: host → its addresses, comma-separated. A literal parses; any other name does not resolve. */
    private static DeclaredRepositories declaredOver(RepoGroup base, Map<String, String> table) {
        return new DeclaredRepositories(base, new EffectivePomBuilder(base), host -> {
            String listed = table.get(host);
            if (listed != null) {
                return Arrays.stream(listed.split(","))
                        .map(InetAddress::ofLiteral)
                        .toArray(InetAddress[]::new);
            }
            try {
                return new InetAddress[] {
                    InetAddress.ofLiteral(host.replace("[", "").replace("]", ""))
                };
            } catch (IllegalArgumentException notALiteral) {
                throw new UnknownHostException(host);
            }
        });
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
