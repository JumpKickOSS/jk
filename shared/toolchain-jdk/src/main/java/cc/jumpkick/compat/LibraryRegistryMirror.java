// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.repo.LibraryRegistryClient;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where the library registry ({@code libraries.toml}, the catalog behind {@code jk.toml}'s short
 * library names) is read from: {@code [mirrors] libraries} in {@code ~/.jk/config.toml},
 * overridable by {@code JK_LIBRARIES_DIST_MIRROR}, else a settings.xml mirror whose {@code mirrorOf}
 * names {@code jk-libraries}, else the public registry. A mirror is laid out as the directory that
 * holds the public file, so the registry is {@code <mirror>libraries.toml}.
 *
 * <p>The registry is not a Maven repository, so a {@code mirror-of} table never serves it.
 */
public record LibraryRegistryMirror(@Nullable DownloadOrigin origin) {

    /** The {@code [mirrors]} key. */
    public static final String KEY = "libraries";

    /** The environment variable that overrides the table. */
    public static final String ENV = "JK_LIBRARIES_DIST_MIRROR";

    /** The settings.xml {@code mirrorOf} token that names the registry. */
    public static final String MIRROR_OF = "jk-libraries";

    /** The registry file's name under the public directory and under a mirror. */
    public static final String FILE = "libraries.toml";

    /** The mirror this process's configuration names. */
    public static LibraryRegistryMirror current() {
        return of(JkDirs::env, GlobalConfig.mirrors(), MavenSettings.current());
    }

    /** As {@link #current()} against explicit inputs, for tests. */
    public static LibraryRegistryMirror of(
            Function<String, @Nullable String> env, Map<String, String> mirrors, MavenSettings settings) {
        return new LibraryRegistryMirror(DownloadOrigins.configured(
                env, settings, ENV, mirrors.get(KEY), "[mirrors] " + KEY, List.of(MIRROR_OF)));
    }

    /** The registry's URL: {@code <mirror>libraries.toml}, else {@link LibraryRegistryClient#DEFAULT_SOURCE}. */
    public URI source() {
        return origin == null
                ? LibraryRegistryClient.DEFAULT_SOURCE
                : origin.url().resolve(FILE);
    }

    /** {@code http} carrying the mirror's credential on requests under it; {@code http} itself without one. */
    public Http authorized(Http http) {
        return origin == null ? http : http.withAuthorization(DownloadOrigins.authorization(List.of(origin)));
    }
}
