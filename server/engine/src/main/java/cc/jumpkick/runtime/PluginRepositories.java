// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.CentralMirror;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.RepositoryRoute;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The remote repositories a plugin step's {@code repositories} input hands the worker, as jk
 * routes them: the module's {@link RepoGroup} in resolve order, each at the URL jk itself opens —
 * the {@code settings.xml} mirror when one stands in for it, Central's mirror while Central is
 * refusing this host — with the credential that URL is asked with.
 *
 * <p>The action-key token names the declared set (repository names and their own URLs), never the
 * routing or a credential: a mirror window opening or a token rotating re-runs nothing, a
 * repository added to {@code [repositories]} does.
 *
 * @param routes what the worker receives
 * @param token the declared set's identity for the action key
 */
record PluginRepositories(List<RepositoryRoute> routes, String token) {

    /** A step that declared no {@code repositories} input. */
    static final PluginRepositories NONE = new PluginRepositories(List.of(), "none");

    /**
     * The routes for {@code inputs}: built from the module's repository group when the input is
     * declared, {@link #NONE} otherwise so an undeclared step never resolves a credential.
     */
    static PluginRepositories forInputs(
            List<String> inputs, JkBuild project, Cas cas, Function<String, @Nullable String> env) {
        if (!inputs.contains(In.repositories().wireName())) return NONE;
        return of(RepoGroupBuilder.buildFor(project, null, cas, env), CentralMirror.standard()::route);
    }

    /**
     * {@code group}'s repositories in order, each request URL passed through {@code centralRoute}
     * — the Central failover, which rewrites a Central-bound URL while the window is open and
     * leaves every other URL alone.
     */
    static PluginRepositories of(RepoGroup group, UnaryOperator<URI> centralRoute) {
        List<RepositoryRoute> routes = new ArrayList<>();
        StringBuilder declared = new StringBuilder();
        for (MavenRepo repo : group.repos()) {
            routes.add(route(repo, centralRoute.apply(repo.fetchBase())));
            declared.append(repo.name()).append('|').append(repo.baseUrl()).append('\n');
        }
        return new PluginRepositories(List.copyOf(routes), Hashing.sha256Hex(declared.toString()));
    }

    private static RepositoryRoute route(MavenRepo repo, URI url) {
        return switch (repo.credential()) {
            case RepoCredential.Anonymous a -> RepositoryRoute.anonymous(repo.name(), url);
            case RepoCredential.Basic b -> new RepositoryRoute(repo.name(), url, b.username(), b.password());
            case RepoCredential.Bearer t -> new RepositoryRoute(repo.name(), url, null, t.token());
        };
    }
}
