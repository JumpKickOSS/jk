// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.build.RepositoryRoute;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.MirrorSelector;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.jspecify.annotations.Nullable;

/**
 * The remote repositories the engine routed for the module, carried from the plugin worker to the
 * forked Maven resolver as one JSONL file — {@code id}, {@code url}, and the credential — and read
 * back as Aether's {@link RemoteRepository} list, in the same order.
 *
 * <p>The fork's resolver reads its deployment closure — Quarkus's build-time jars, which jk's lock
 * does not carry — from exactly these: the module's {@code [repositories]} over the built-in
 * remotes, each at the URL jk itself opens. Maven's own defaults (Central, the user's
 * {@code settings.xml} repositories) are never consulted, so Central refusing this host is answered
 * by jk's mirror window, not by a failed package step.
 */
final class RepositoryRoutes {

    /** One routed remote: what one JSONL line says. */
    record Route(
            String id,
            String url,
            @Nullable String username,
            @Nullable String secret) {}

    private static final String ID = "id";
    private static final String URL = "url";
    private static final String USERNAME = "username";
    private static final String SECRET = "secret";

    private final List<Route> routes;

    private RepositoryRoutes(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    static RepositoryRoutes of(List<Route> routes) {
        return new RepositoryRoutes(routes);
    }

    /** Write {@code routes} for a fork; the file carries credentials and belongs to the caller to delete. */
    static void write(List<RepositoryRoute> routes, Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (RepositoryRoute r : routes) {
            lines.add(JsonFields.object()
                    .string(ID, r.id())
                    .string(URL, r.url().toString())
                    .optionalString(USERNAME, r.username())
                    .optionalString(SECRET, r.secret())
                    .finish());
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Read the file {@link #write} produced; a blank line is skipped, a line without id or url refused. */
    static RepositoryRoutes read(Path file) throws IOException {
        List<Route> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            @Nullable String id = Jsonl.str(line, ID);
            @Nullable String url = Jsonl.str(line, URL);
            if (id == null || url == null) {
                throw new IOException("repository route without id or url: " + line);
            }
            out.add(new Route(id, url, Jsonl.str(line, USERNAME), Jsonl.str(line, SECRET)));
        }
        return new RepositoryRoutes(out);
    }

    List<Route> routes() {
        return routes;
    }

    /**
     * Aether's view: one {@code default}-layout remote per route, in order, a basic credential
     * attached where the route names a user. A bearer token rides the session instead — see
     * {@link #attachBearerTokens}.
     */
    List<RemoteRepository> remoteRepositories() {
        List<RemoteRepository> out = new ArrayList<>(routes.size());
        for (Route r : routes) out.add(remote(r).build());
        return out;
    }

    private static RemoteRepository.Builder remote(Route r) {
        RemoteRepository.Builder b = new RemoteRepository.Builder(r.id(), "default", r.url());
        if (r.username() != null && r.secret() != null) {
            b.setAuthentication(new AuthenticationBuilder()
                    .addUsername(r.username())
                    .addPassword(r.secret())
                    .build());
        }
        return b;
    }

    /**
     * Send a repository a dependency POM declares under one of these ids — Maven's super POM
     * declares {@code central} at Central's own address, and Aether keeps that beside the list it
     * was given — to the route's URL with the route's credential, the way Maven's {@code
     * <mirrorOf>} does. A repository under no route's id keeps whatever the session's own
     * selector says. A session that cannot take a selector (read-only) is left as it is.
     */
    void attachMirrors(RepositorySystemSession session) {
        if (!(session instanceof DefaultRepositorySystemSession mutable)) return;
        Map<String, Route> byId = new LinkedHashMap<>();
        for (Route r : routes) byId.putIfAbsent(r.id(), r);
        @Nullable MirrorSelector previous = mutable.getMirrorSelector();
        try {
            mutable.setMirrorSelector(repository -> {
                Route route = byId.get(repository.getId());
                if (route == null) return previous == null ? null : previous.getMirror(repository);
                if (route.url().equals(repository.getUrl())) return null;
                return remote(route)
                        .setMirroredRepositories(List.of(repository))
                        .build();
            });
        } catch (IllegalStateException readOnly) {
            System.err.println("jk-quarkus: warning: the resolver session cannot take jk's repository routing;"
                    + " a repository a POM declares is asked where the POM says");
        }
    }

    /**
     * Give each bearer-token route its {@code Authorization} header, the way Aether's HTTP
     * transport reads per-repository headers off the session. A session that cannot take a
     * property (read-only) leaves the repository anonymous, and says so.
     */
    void attachBearerTokens(RepositorySystemSession session) {
        for (Route r : routes) {
            if (r.username() != null || r.secret() == null) continue;
            if (session instanceof DefaultRepositorySystemSession mutable) {
                try {
                    mutable.setConfigProperty(
                            HTTP_HEADERS + "." + r.id(), Map.of("Authorization", "Bearer " + r.secret()));
                    continue;
                } catch (IllegalStateException readOnly) {
                    // fall through to the note
                }
            }
            System.err.println("jk-quarkus: warning: repository `" + r.id()
                    + "` has a bearer credential the resolver session cannot carry; it is asked anonymously");
        }
    }

    /** Aether's per-repository header table key ({@code ConfigurationProperties.HTTP_HEADERS}). */
    static final String HTTP_HEADERS = "aether.connector.http.headers";

    /** The ids in order — for the augment's log line. */
    String describe() {
        StringBuilder b = new StringBuilder();
        for (Route r : routes) {
            if (b.length() > 0) b.append(", ");
            b.append(r.id()).append('=').append(r.url());
        }
        return b.toString();
    }
}
