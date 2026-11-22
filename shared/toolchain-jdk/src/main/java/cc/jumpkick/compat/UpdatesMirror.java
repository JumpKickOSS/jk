// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where the engine reads the signed update manifest ({@code updates.json}) and the files it lists:
 * {@code [mirrors] updates} in {@code ~/.jk/config.toml}, overridable by {@code
 * JK_UPDATES_DIST_MIRROR}, else a settings.xml mirror whose {@code mirrorOf} names {@code
 * jk-updates}, else jumpkick.build and then GitHub. A mirror is the directory that holds the
 * manifest, so it is {@code <mirror>updates.json}, and each file it lists sits beside it.
 */
public record UpdatesMirror(@Nullable DownloadOrigin origin) {

    /** The {@code [mirrors]} key. */
    public static final String KEY = "updates";

    /** The environment variable that overrides the table. */
    public static final String ENV = "JK_UPDATES_DIST_MIRROR";

    /** The settings.xml {@code mirrorOf} token that names the update files. */
    public static final String MIRROR_OF = "jk-updates";

    /** The manifest's file name under each public origin and under a mirror. */
    public static final String FILE = "updates.json";

    /** The public copies, asked in order: the second only when the first fails. */
    public static final List<URI> PUBLIC_SOURCES = List.of(
            URI.create("https://jumpkick.build/" + FILE),
            URI.create("https://raw.githubusercontent.com/JumpKickOSS/jk-updates/refs/heads/main/" + FILE));

    /** The mirror this process's configuration names. */
    public static UpdatesMirror current() {
        return of(JkDirs::env, GlobalConfig.mirrors(), MavenSettings.current());
    }

    /** As {@link #current()} against explicit inputs, for tests. */
    public static UpdatesMirror of(
            Function<String, @Nullable String> env, Map<String, String> mirrors, MavenSettings settings) {
        return new UpdatesMirror(DownloadOrigins.configured(
                env, settings, ENV, mirrors.get(KEY), "[mirrors] " + KEY, List.of(MIRROR_OF)));
    }

    /** The manifest URLs to ask, in order: the mirror's copy alone when one is configured. */
    public List<URI> sources() {
        return origin == null ? PUBLIC_SOURCES : List.of(origin.url().resolve(FILE));
    }

    /** {@code http} carrying the mirror's credential on requests under it; {@code http} itself without one. */
    public Http authorized(Http http) {
        return origin == null ? http : http.withAuthorization(DownloadOrigins.authorization(List.of(origin)));
    }
}
