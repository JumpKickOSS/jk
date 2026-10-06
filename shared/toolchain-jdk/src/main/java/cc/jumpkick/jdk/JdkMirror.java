// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.compat.DownloadOrigin;
import cc.jumpkick.compat.DownloadOrigins;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where JDK downloads come from: {@code [mirrors] jdk} in {@code ~/.jk/config.toml}, overridable by
 * {@code JK_JDK_DIST_MIRROR}, else a settings.xml mirror whose {@code mirrorOf} names {@code jdk}.
 * The catalog feed and the vendor archives it names live on many hosts, so a mirror proxies each
 * host under a path prefix: {@code https://host/path} is fetched from {@code <mirror>/host/path}.
 * Without one, every URL is used as the feed names it.
 */
public record JdkMirror(@Nullable DownloadOrigin origin) {

    /** The {@code [mirrors]} key. */
    public static final String KEY = "jdk";

    /** The environment variable that overrides the table. */
    public static final String ENV = "JK_JDK_DIST_MIRROR";

    /** The mirror this process's configuration names. */
    public static JdkMirror current() {
        return of(JkDirs::env, GlobalConfig.mirrors(), MavenSettings.current());
    }

    /** As {@link #current()} against explicit inputs, for tests. */
    public static JdkMirror of(
            Function<String, @Nullable String> env, Map<String, String> mirrors, MavenSettings settings) {
        return new JdkMirror(
                DownloadOrigins.configured(env, settings, ENV, mirrors.get(KEY), "[mirrors] " + KEY, List.of(KEY)));
    }

    /** {@code uri} as this mirror serves it: {@code <mirror>/host[:port]/path[?query]}, or {@code uri} itself. */
    public URI map(URI uri) {
        if (origin == null || uri.getHost() == null) return uri;
        String host = DownloadOrigin.hostId(uri);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return URI.create(origin.url() + host + path + query);
    }

    /** {@code http} carrying the mirror's credential on requests under it; {@code http} itself without one. */
    public Http authorized(Http http) {
        return origin == null ? http : http.withAuthorization(DownloadOrigins.authorization(List.of(origin)));
    }
}
