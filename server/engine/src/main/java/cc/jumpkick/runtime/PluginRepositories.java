// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.CredentialHelpers;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.CentralMirror;
import cc.jumpkick.http.ProxyEnvironment;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.RepositoryRoute;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The remote repositories a plugin step's {@code repositories} input hands the worker, as jk
 * routes them: the module's {@link RepoGroup} in resolve order, each at the URL jk itself opens —
 * the mirror when one stands in for it ({@code ~/.jk/config.toml} or {@code settings.xml}), Central's mirror while Central is
 * refusing this host — with the credential that URL is asked with.
 *
 * <p>The action-key token names the declared set (repository names and their own URLs), never the
 * routing or a credential: a mirror window opening or a token rotating re-runs nothing, a
 * repository added to {@code [repositories]} does.
 *
 * <p>The worker hands the routes to a forked resolver that cannot ask jk for a credential again,
 * so a fork is given {@link #forHandOff()}: a {@code ${cmd:}} helper's value printed during this
 * command, not one cached from an earlier one.
 *
 * @param routes what the step's key and its plan read
 * @param token the declared set's identity for the action key
 * @param rebuild the routes resolved again, or null when they were built from a group in hand
 */
record PluginRepositories(
        List<RepositoryRoute> routes,
        String token,
        @Nullable Supplier<List<RepositoryRoute>> rebuild) {

    /** A step that declared no {@code repositories} input. */
    static final PluginRepositories NONE = new PluginRepositories(List.of(), "none", null);

    /**
     * The routes a forked worker receives: {@link #routes} again, unless a credential among them is
     * a helper's value that no run of this request printed. That value is dropped, the helper runs,
     * and the routes are resolved again with what it prints, so the fork holds a value as young as
     * the command rather than one up to its time to live old.
     */
    List<RepositoryRoute> forHandOff() {
        if (rebuild == null) return routes;
        boolean renewed = false;
        for (RepositoryRoute route : routes) {
            if (CredentialHelpers.renewForHandOff(route.secret())) renewed = true;
        }
        return renewed ? rebuild.get() : routes;
    }

    /**
     * The routes for {@code inputs}: built from the module's repository group when the input is
     * declared, {@link #NONE} otherwise so an undeclared step never resolves a credential.
     */
    static PluginRepositories forInputs(
            List<String> inputs, JkBuild project, Cas cas, Function<String, @Nullable String> env) {
        if (!inputs.contains(In.repositories().wireName())) return NONE;
        ProxyEnvironment proxies = ProxyEnvironment.ambient();
        Function<URI, @Nullable URI> proxyFor =
                url -> proxies.proxyUrl(url).map(URI::create).orElse(null);
        PluginRepositories built =
                of(RepoGroupBuilder.buildFor(project, null, cas, env), CentralMirror.standard()::route, proxyFor);
        Supplier<List<RepositoryRoute>> rebuild =
                () -> of(RepoGroupBuilder.buildFor(project, null, cas, env), CentralMirror.standard()::route, proxyFor)
                        .routes();
        return new PluginRepositories(built.routes(), built.token(), rebuild);
    }

    /** As below, every route going direct. */
    static PluginRepositories of(RepoGroup group, UnaryOperator<URI> centralRoute) {
        return of(group, centralRoute, url -> null);
    }

    /**
     * {@code group}'s repositories in order, each request URL passed through {@code centralRoute}
     * — the Central failover, which rewrites a Central-bound URL while the window is open and
     * leaves every other URL alone — and through {@code proxyFor}, the proxy jk's own downloads
     * take to that URL (null to go direct), so a step's resolver reaches it the same way.
     */
    static PluginRepositories of(
            RepoGroup group, UnaryOperator<URI> centralRoute, Function<URI, @Nullable URI> proxyFor) {
        List<RepositoryRoute> routes = new ArrayList<>();
        StringBuilder declared = new StringBuilder();
        for (MavenRepo repo : group.repos()) {
            refuseRefusedMirror(repo);
            URI url = centralRoute.apply(repo.fetchBase());
            routes.add(route(repo, url).through(proxyFor.apply(url)));
            declared.append(repo.name()).append('|').append(repo.baseUrl()).append('\n');
        }
        return new PluginRepositories(List.copyOf(routes), Hashing.sha256Hex(declared.toString()), null);
    }

    /**
     * A mirror jk refuses stands in for {@code repo} all the same: the step's resolver would dial
     * the refused URL, and jk's own requests go nowhere in its place, so the step fails naming it.
     */
    private static void refuseRefusedMirror(MavenRepo repo) {
        MavenRepo.Mirror mirror = repo.mirror().orElse(null);
        if (mirror == null || mirror.refusal() == null) return;
        throw new IllegalStateException("repository " + repo.name() + " is reached through " + mirror.label()
                + ", which jk refuses: " + mirror.refusal());
    }

    private static RepositoryRoute route(MavenRepo repo, URI url) {
        return switch (repo.credential()) {
            case RepoCredential.Anonymous a -> RepositoryRoute.anonymous(repo.name(), url);
            case RepoCredential.Basic b -> new RepositoryRoute(repo.name(), url, b.username(), b.password());
            case RepoCredential.Bearer t -> new RepositoryRoute(repo.name(), url, null, t.token());
        };
    }
}
