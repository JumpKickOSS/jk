// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.RepositoryRoute;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.task.IoLedger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin step that declares {@link In#repositories()} receives the module's remotes the way jk
 * asks them — the mirror standing in for a repository ({@code ~/.jk/config.toml} or {@code
 * settings.xml}), Central rewritten to
 * its mirror while the failover window is open, the credential each request carries — so a
 * worker's own resolver reaches nothing jk would not, and Central never directly. The action-key
 * token names the declared set alone.
 */
class PluginRepositoriesTest {

    private static final URI CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");
    private static final URI MIRROR = URI.create("https://maven-central.storage-download.googleapis.com/maven2/");
    private static final URI NEXUS = URI.create("https://nexus.example.test/repository/all/");
    private static final URI INTERNAL = URI.create("https://repo.acme.test/maven/");

    /** Central rewritten to the mirror, as the failover does while its window is open. */
    private static final UnaryOperator<URI> WINDOW_OPEN =
            uri -> "repo.maven.apache.org".equals(uri.getHost()) ? MIRROR : uri;

    @Test
    void the_routes_are_the_groups_repositories_in_order_at_the_url_each_request_opens(@TempDir Path tmp) {
        Http http = new Http();
        Cas cas = new Cas(tmp.resolve("cache"));
        MavenRepo central = new MavenRepo("central", CENTRAL, http, cas);
        MavenRepo internal = new MavenRepo("internal", INTERNAL, http, cas, new RepoCredential.Bearer("t0k3n"));
        MavenRepo mirrored = new MavenRepo("jumpkick", URI.create("https://repo.jumpkick.build/maven/"), http, cas)
                .mirroredThrough(new MavenRepo.Mirror(
                        "nexus", NEXUS, new RepoCredential.Basic("deploy", "s3cret"), "mirror `nexus`"));

        PluginRepositories routed =
                PluginRepositories.of(new RepoGroup(List.of(mirrored, internal, central)), UnaryOperator.identity());

        assertThat(routed.routes())
                .containsExactly(
                        new RepositoryRoute("jumpkick", NEXUS, "deploy", "s3cret"),
                        new RepositoryRoute("internal", INTERNAL, null, "t0k3n"),
                        RepositoryRoute.anonymous("central", CENTRAL));
        assertThat(routed.routes().get(0).anonymous()).isFalse();
        assertThat(routed.routes().get(1).bearer()).isTrue();
    }

    /** Each route carries the proxy jk's own downloads take to the URL it opens, so a step's resolver goes the same way. */
    @Test
    void each_route_carries_the_proxy_for_the_url_it_opens(@TempDir Path tmp) {
        Http http = new Http();
        Cas cas = new Cas(tmp.resolve("cache"));
        RepoGroup group = new RepoGroup(
                List.of(new MavenRepo("central", CENTRAL, http, cas), new MavenRepo("internal", INTERNAL, http, cas)));
        URI corp = URI.create("http://corp:p%40ss@proxy.acme.test:3128");

        // The internal host is on the bypass list; Central, rewritten to its mirror, goes through.
        PluginRepositories routed =
                PluginRepositories.of(group, WINDOW_OPEN, url -> "repo.acme.test".equals(url.getHost()) ? null : corp);

        assertThat(routed.routes())
                .containsExactly(
                        RepositoryRoute.anonymous("central", MIRROR).through(corp),
                        RepositoryRoute.anonymous("internal", INTERNAL));
        assertThat(routed.token())
                .as("the proxy is routing, not the declared set: it re-runs nothing")
                .isEqualTo(PluginRepositories.of(group, WINDOW_OPEN).token());
    }

    @Test
    void central_is_rewritten_to_its_mirror_while_the_window_is_open_and_nothing_else_moves(@TempDir Path tmp) {
        Http http = new Http();
        Cas cas = new Cas(tmp.resolve("cache"));
        RepoGroup group = new RepoGroup(
                List.of(new MavenRepo("central", CENTRAL, http, cas), new MavenRepo("internal", INTERNAL, http, cas)));

        PluginRepositories routed = PluginRepositories.of(group, WINDOW_OPEN);

        assertThat(routed.routes()).extracting(RepositoryRoute::url).containsExactly(MIRROR, INTERNAL);
        assertThat(routed.routes()).extracting(RepositoryRoute::id).containsExactly("central", "internal");
    }

    @Test
    void the_token_names_the_declared_set_not_the_routing_or_a_credential(@TempDir Path tmp) {
        Http http = new Http();
        Cas cas = new Cas(tmp.resolve("cache"));
        MavenRepo plain = new MavenRepo("internal", INTERNAL, http, cas);
        MavenRepo withToken = new MavenRepo("internal", INTERNAL, http, cas, new RepoCredential.Bearer("t0k3n"));
        MavenRepo central = new MavenRepo("central", CENTRAL, http, cas);

        String direct = PluginRepositories.of(new RepoGroup(List.of(central, plain)), UnaryOperator.identity())
                .token();
        String mirrored = PluginRepositories.of(new RepoGroup(List.of(central, plain)), WINDOW_OPEN)
                .token();
        String rotated = PluginRepositories.of(new RepoGroup(List.of(central, withToken)), UnaryOperator.identity())
                .token();
        String added = PluginRepositories.of(
                        new RepoGroup(List.of(central, plain, new MavenRepo("extra", NEXUS, http, cas))),
                        UnaryOperator.identity())
                .token();

        assertThat(mirrored).isEqualTo(direct);
        assertThat(rotated).isEqualTo(direct);
        assertThat(added).isNotEqualTo(direct);
        assertThat(PluginRepositories.NONE.routes()).isEmpty();
    }

    @Test
    void a_packager_is_read_by_the_same_rule_as_a_step(@TempDir Path tmp) {
        JkBuild project = JkBuild.of(new Project("com.example", "app", "1.0", 25));
        Cas cas = new Cas(tmp.resolve("cache"));

        PluginRepositories declared = PackagingKeys.packagerRepositories(
                new PluginDeclarations.PackagerDecl(
                        "oci-image",
                        List.of(In.classes().wireName(), In.repositories().wireName())),
                project,
                cas,
                name -> null);
        PluginRepositories undeclared = PackagingKeys.packagerRepositories(
                new PluginDeclarations.PackagerDecl(
                        "boot-jar", List.of(In.classes().wireName())),
                project,
                cas,
                name -> null);

        assertThat(declared.routes()).extracting(RepositoryRoute::id).contains("central");
        assertThat(declared.token()).isNotEqualTo(PluginRepositories.NONE.token());
        assertThat(undeclared).isSameAs(PluginRepositories.NONE);
    }

    @Test
    void a_step_that_did_not_declare_the_input_gets_no_routes(@TempDir Path tmp) {
        PluginRepositories none = PluginRepositories.forInputs(
                List.of(In.classes().wireName(), In.config().wireName()),
                JkBuild.of(new Project("com.example", "app", "1.0", 25)),
                new Cas(tmp.resolve("cache")),
                name -> null);

        assertThat(none).isSameAs(PluginRepositories.NONE);
    }

    /** A {@code mirror-of} table of the user's config reaches the fork as it reaches jk's own requests. */
    @Test
    void a_config_toml_mirror_is_the_url_and_credential_every_route_carries(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("config.toml"), """
                [repositories.corp]
                url = "https://artifactory.acme.test/maven-virtual/"
                mirror-of = ["*"]
                token = "${JK_REPO_CORP_TOKEN}"
                """);
        System.setProperty("jk.env.JK_HOME", tmp.toString());
        PluginRepositories routed;
        try {
            routed = PluginRepositories.forInputs(
                    List.of(In.repositories().wireName()),
                    JkBuild.of(new Project("com.example", "app", "1.0", 25)),
                    new Cas(tmp.resolve("cache")),
                    name -> "JK_REPO_CORP_TOKEN".equals(name) ? "corp-secret" : null);
        } finally {
            System.clearProperty("jk.env.JK_HOME");
        }

        URI corp = URI.create("https://artifactory.acme.test/maven-virtual/");
        assertThat(routed.routes()).extracting(RepositoryRoute::id).containsExactly("jumpkick", "central", "google");
        assertThat(routed.routes()).allSatisfy(route -> {
            assertThat(route.url()).isEqualTo(corp);
            assertThat(route.secret()).isEqualTo("corp-secret");
        });
    }

    /**
     * The worker hands its routes to a forked resolver that cannot ask for a credential again. A
     * {@code ${cmd:}} value cached by an earlier command could be most of its time to live old when
     * the fork starts; the fork is handed one this command's run printed instead.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_fork_is_handed_a_helper_value_printed_during_this_command(@TempDir Path tmp) throws Exception {
        Path count = tmp.resolve("count");
        Path helper = tmp.resolve("helper.sh");
        Files.writeString(helper, """
                #!/bin/sh
                n=0
                [ -f '%1$s' ] && read n < '%1$s'
                n=$((n + 1))
                echo "$n" > '%1$s'
                echo "token-$n"
                """.formatted(count));
        assertThat(helper.toFile().setExecutable(true)).isTrue();
        Files.writeString(tmp.resolve("config.toml"), """
                [repositories.corp]
                url = "https://artifactory.acme.test/maven-virtual/"
                mirror-of = ["*"]
                token = "${cmd:%s}"
                """.formatted(helper));
        System.setProperty("jk.env.JK_HOME", tmp.toString());
        // An earlier command resolved the credential; the value is cached for its time to live.
        inRequest(() -> assertThat(secrets(routes(tmp).forHandOff())).containsOnly("token-1"));

        inRequest(() -> {
            PluginRepositories routed = routes(tmp);
            assertThat(secrets(routed.routes()))
                    .as("the step's own plan reads the cached value")
                    .containsOnly("token-1");
            assertThat(secrets(routed.forHandOff()))
                    .as("the fork is handed a fresh one")
                    .containsOnly("token-2");
            assertThat(secrets(routes(tmp).forHandOff()))
                    .as("a second fork in the same command shares that run")
                    .containsOnly("token-2");
        });
        assertThat(Files.readString(count).strip()).isEqualTo("2");
    }

    private static PluginRepositories routes(Path tmp) {
        return PluginRepositories.forInputs(
                List.of(In.repositories().wireName()),
                JkBuild.of(new Project("com.example", "app", "1.0", 25)),
                new Cas(tmp.resolve("cache")),
                name -> null);
    }

    private static List<String> secrets(List<RepositoryRoute> routes) {
        return routes.stream().map(route -> String.valueOf(route.secret())).toList();
    }

    /** {@code body} as one command: its own request scope, released after. */
    private static void inRequest(Runnable body) {
        Request.of(1, new IoLedger()).run(() -> {
            try {
                body.run();
            } finally {
                RequestScope.release();
            }
        });
    }

    @Test
    void a_repository_behind_a_refused_mirror_fails_the_routes_naming_it(@TempDir Path tmp) {
        MavenRepo refused = new MavenRepo("central", CENTRAL, new Http(), new Cas(tmp.resolve("cache")))
                .mirroredThrough(new MavenRepo.Mirror(
                        "nexus",
                        URI.create("http://nexus.example.test/m2/"),
                        RepoCredential.ANONYMOUS,
                        "mirror `nexus` (settings.xml)",
                        "it uses plain http"));

        assertThatThrownBy(() -> PluginRepositories.of(RepoGroup.of(refused), UnaryOperator.identity()))
                .hasMessageContaining("mirror `nexus` (settings.xml)")
                .hasMessageContaining("plain http");
    }
}
