// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.build.RepositoryRoute;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The augment's Maven resolver asks the remotes jk routed for the module and nothing else: the
 * plugin writes the engine's routes for the fork, the fork reads them back in order, and each
 * becomes an Aether remote at the routed URL with the credential the route carries — so Central's
 * mirror stands where Central was while the failover window is open, and a repository jk never
 * declared is never asked.
 */
class RepositoryRoutesTest {

    private static final URI MIRROR = URI.create("https://maven-central.storage-download.googleapis.com/maven2/");
    private static final URI NEXUS = URI.create("https://nexus.example.test/repository/all/");
    private static final URI INTERNAL = URI.create("https://repo.acme.test/maven/");

    @Test
    void the_routes_cross_the_fork_boundary_in_order_with_their_credentials(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("routes.jsonl");
        RepositoryRoutes.write(
                List.of(
                        new RepositoryRoute("jumpkick", NEXUS, "deploy", "s3c\"ret"),
                        new RepositoryRoute("internal", INTERNAL, null, "t0k3n"),
                        RepositoryRoute.anonymous("central", MIRROR)),
                file);

        RepositoryRoutes routes = RepositoryRoutes.read(file);

        assertThat(routes.routes())
                .containsExactly(
                        new RepositoryRoutes.Route("jumpkick", NEXUS.toString(), "deploy", "s3c\"ret"),
                        new RepositoryRoutes.Route("internal", INTERNAL.toString(), null, "t0k3n"),
                        new RepositoryRoutes.Route("central", MIRROR.toString(), null, null));
    }

    @Test
    void each_route_is_one_aether_remote_at_the_routed_url_and_central_is_its_mirror() {
        RepositoryRoutes routes = RepositoryRoutes.of(List.of(
                new RepositoryRoutes.Route("jumpkick", NEXUS.toString(), "deploy", "s3cret"),
                new RepositoryRoutes.Route("central", MIRROR.toString(), null, null)));

        List<RemoteRepository> remotes = routes.remoteRepositories();

        assertThat(remotes).extracting(RemoteRepository::getId).containsExactly("jumpkick", "central");
        assertThat(remotes).extracting(RemoteRepository::getUrl).containsExactly(NEXUS.toString(), MIRROR.toString());
        assertThat(remotes.get(1).getUrl()).doesNotContain("repo.maven.apache.org");
        assertThat(remotes.get(1).getAuthentication()).isNull();
        var session = new DefaultRepositorySystemSession();
        try (AuthenticationContext auth = AuthenticationContext.forRepository(session, remotes.get(0))) {
            assertThat(auth.get(AuthenticationContext.USERNAME)).isEqualTo("deploy");
            assertThat(auth.get(AuthenticationContext.PASSWORD)).isEqualTo("s3cret");
        }
    }

    @Test
    void a_bearer_token_rides_the_session_as_the_repositorys_authorization_header() {
        RepositoryRoutes routes = RepositoryRoutes.of(
                List.of(new RepositoryRoutes.Route("internal", INTERNAL.toString(), null, "t0k3n")));
        var session = new DefaultRepositorySystemSession();

        routes.attachBearerTokens(session);

        assertThat(session.getConfigProperties())
                .containsEntry(RepositoryRoutes.HTTP_HEADERS + ".internal", Map.of("Authorization", "Bearer t0k3n"));
        assertThat(routes.remoteRepositories().get(0).getAuthentication()).isNull();
    }

    @Test
    void a_line_without_an_id_or_url_is_refused_and_a_blank_line_skipped(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("routes.jsonl");
        Files.writeString(file, "\n{\"id\":\"central\",\"url\":\"" + MIRROR + "\"}\n\n", StandardCharsets.UTF_8);
        assertThat(RepositoryRoutes.read(file).routes()).hasSize(1);

        Files.writeString(file, "{\"id\":\"central\"}\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> RepositoryRoutes.read(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("without id or url");
    }

    @Test
    void an_empty_file_is_no_remotes_at_all(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("routes.jsonl");
        RepositoryRoutes.write(List.of(), file);

        assertThat(RepositoryRoutes.read(file).remoteRepositories()).isEmpty();
    }
}
