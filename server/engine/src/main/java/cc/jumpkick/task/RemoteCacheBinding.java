// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.HttpCacheBackend;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.IoLedger;
import cc.jumpkick.config.RunNotices;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.RepoCredentialResolver;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where a request gets its remote cache tier: one {@link RemoteTier}, so one circuit breaker, per
 * request, bound around the request's whole body ({@link RemoteCaches#where}) so its forecast, its
 * steps and {@code jk explain} ask the same tier.
 *
 * <p>A shared cache service is named by the user's own settings ({@link RemoteCacheSettings}) and
 * reached over HTTP ({@link HttpCacheBackend}) with the repository credential chain's credential for
 * its host. A request run {@code --offline} binds none.
 *
 * <p>For exercising the remote tier between two homes without a service, a directory standing in
 * for a shared cache ({@link DirectoryCacheBackend}) is named by the experimental {@value #DIR}
 * setting of the shell that ran the command; {@value #WRITE}{@code =true} also uploads what the
 * request stores. Those two are not a supported feature: the names and the behaviour may change or
 * go. With no remote cache and the team's own key seed set, the request's own action cache stands
 * as its tier for the shared forms of the results that read the environment ({@link #seedForms});
 * with neither, nothing is bound and the request is the local cache alone.
 */
public final class RemoteCacheBinding {

    /** The directory a request reads cache records from, as if it were a shared store. Experimental. */
    public static final String DIR = "JK_EXPERIMENTAL_REMOTE_CACHE_DIR";

    /** {@code true} to also upload what the request stores to {@link #DIR}. Experimental. */
    public static final String WRITE = "JK_EXPERIMENTAL_REMOTE_CACHE_WRITE";

    /** How long a writing request waits, at its end, for its uploads to land. */
    static final Duration DRAIN = Duration.ofMinutes(2);

    private RemoteCacheBinding() {}

    /**
     * How long a call to a shared cache service waits for its answer to start. A lookup is on the
     * build's path; a service slower than this is off for the build, which goes on locally.
     */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** The tier the current request's settings name, or null when they name none. */
    static @Nullable RemoteTier forRequest() {
        String dir = EnvValues.string(BuildEnv::get, DIR).orElse(null);
        if (dir != null) {
            boolean writes = EnvValues.bool(BuildEnv::get, WRITE).orElse(false);
            return new RemoteTier(new DirectoryCacheBackend(Path.of(dir)), writes).seededBy(seed(), credentialsLocal());
        }
        RemoteCacheSettings settings = RemoteCacheSettings.resolve(BuildEnv::get, JkDirs.userConfigFile());
        if (settings == null || SessionContext.current().config().offlineOr(false)) return seedForms(seed());
        return new RemoteTier(service(settings.url()), settings.writes()).seededBy(seed(), credentialsLocal());
    }

    /**
     * With no remote cache, the tier that keeps the request's suite results that read the
     * environment under their key's shared form in its own action cache too ({@link SeedFormStore}),
     * when {@code source} is the team's own seed; null otherwise. An issued seed never keys it, and
     * there is none without a cache to issue one: nothing matches on another machine without a
     * seed the team set or a cache service coordinating it.
     */
    static @Nullable RemoteTier seedForms(CacheSeeds.Source source) {
        if (!(source instanceof CacheSeeds.Team)) return null;
        return new RemoteTier(new SeedFormStore(), true).seededBy(source, credentialsLocal());
    }

    /** Whether the request keeps results keyed on a credential's value here even under a seed. */
    private static boolean credentialsLocal() {
        return CacheSeeds.credentialsLocal(BuildEnv::get, JkDirs.userConfigFile());
    }

    /** Where the request's key seed comes from: the team's, set in its shell or the user's settings, else the cache's. */
    private static CacheSeeds.Source seed() {
        return CacheSeeds.resolve(BuildEnv::get, JkDirs.userConfigFile());
    }

    /** The service at {@code url}, with the credential the repository chain holds for its {@code host[:port]}. */
    static HttpCacheBackend service(URI url) {
        return new HttpCacheBackend(url, Clients.HTTP, () -> authorization(url));
    }

    /** One client for every request's service: no retries, a short wait. */
    private static final class Clients {
        static final Http HTTP = new Http().withoutRetries().withRequestTimeout(TIMEOUT);
    }

    private static Optional<String> authorization(URI url) {
        String id = url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort());
        RepoCredential credential =
                RepoCredentialResolver.withEnv(BuildEnv::get).resolve(id, url, Optional.empty());
        return switch (credential) {
            case RepoCredential.Bearer b -> Optional.of("Bearer " + b.token());
            case RepoCredential.Basic b ->
                Optional.of("Basic "
                        + Base64.getEncoder()
                                .encodeToString((b.username() + ":" + b.password()).getBytes(StandardCharsets.UTF_8)));
            default -> Optional.empty();
        };
    }

    /**
     * Run {@code body} with the request's remote tier bound, and wait for its uploads before
     * returning; run it as it is when the request names no tier.
     */
    public static <T extends @Nullable Object, X extends Throwable> T around(ScopedValue.CallableOp<T, X> body)
            throws X {
        RemoteTier tier = forRequest();
        if (tier == null) return body.call();
        try {
            return RemoteCaches.call(tier, body);
        } finally {
            drain(tier);
        }
    }

    /** The run notice that says, once at its end, which of the build's uploads did not land and why. */
    public static final String NOT_STORED = "remote-cache-not-stored";

    /**
     * Wait for the request's uploads, then say in one line what was not stored ({@link
     * RemoteTier#notStoredLine}); the counts are also the run's {@code cache*NotStored} totals.
     * A tier that stopped writing mid-build still drains what it queued.
     */
    static void drain(RemoteTier tier) {
        try {
            if (!tier.drain(DRAIN)) {
                Log.warn("jk: remote cache " + tier.name() + " uploads still pending after " + DRAIN.toSeconds()
                        + " s; left unfinished");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String line = tier.notStoredLine();
        // The run's own notices: the build's body, which bound its session, has returned.
        IoLedger run = tier.ledger();
        if (line != null && run != null) RunNotices.warnOnce(run, NOT_STORED, () -> "jk: " + line);
    }
}
