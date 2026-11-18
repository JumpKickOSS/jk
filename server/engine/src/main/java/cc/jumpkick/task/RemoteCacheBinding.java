// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.HttpCacheBackend;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvValues;
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
 * go. With nothing set, nothing is bound and the request is the local cache alone.
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
            return new RemoteTier(new DirectoryCacheBackend(Path.of(dir)), writes);
        }
        RemoteCacheSettings settings = RemoteCacheSettings.resolve(BuildEnv::get, JkDirs.userConfigFile());
        if (settings == null || SessionContext.current().config().offlineOr(false)) return null;
        return new RemoteTier(service(settings.url()), settings.writes());
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

    private static void drain(RemoteTier tier) {
        try {
            if (tier.writes() && !tier.drain(DRAIN)) {
                Log.warn("jk: remote cache " + tier.name() + " uploads still pending after " + DRAIN.toSeconds()
                        + " s; left unfinished");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
