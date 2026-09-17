// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.RepositoryRoute;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin step that declares {@link In#repositories()} receives the module's remotes the way jk
 * asks them — the {@code settings.xml} mirror standing in for a repository, Central rewritten to
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
    void a_step_that_did_not_declare_the_input_gets_no_routes(@TempDir Path tmp) {
        PluginRepositories none = PluginRepositories.forInputs(
                List.of(In.classes().wireName(), In.config().wireName()),
                JkBuild.of(new Project("com.example", "app", "1.0", 25)),
                new Cas(tmp.resolve("cache")),
                name -> null);

        assertThat(none).isSameAs(PluginRepositories.NONE);
    }
}
