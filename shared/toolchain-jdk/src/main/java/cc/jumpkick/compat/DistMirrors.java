// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where each tool distribution is downloaded from: {@code [mirrors]} in {@code ~/.jk/config.toml}
 * ({@code node}, {@code kotlin}, {@code gradle}, {@code maven}), each overridable by
 * {@code JK_<TOOL>_DIST_MIRROR}, else a settings.xml mirror that names it, else the public origin
 * (see {@link DownloadOrigins} for the order and the credential).
 */
public final class DistMirrors {

    /** A mirrored distribution: its {@code [mirrors]} key, the settings.xml tokens that name it, and its public base. */
    public enum Dist {
        NODE("node", List.of("nodejs"), "https://nodejs.org/dist/"),
        KOTLIN("kotlin", List.of("kotlin"), "https://github.com/JetBrains/kotlin/releases/download/"),
        GRADLE("gradle", List.of("gradle"), "https://services.gradle.org/distributions/"),
        MAVEN(
                "maven",
                List.of(RepositorySpec.CENTRAL, "*"),
                RepositorySpec.MAVEN_CENTRAL.url().toString());

        private final String key;
        private final List<String> mirrorOf;
        private final String publicBase;

        Dist(String key, List<String> mirrorOf, String publicBase) {
            this.key = key;
            this.mirrorOf = mirrorOf;
            this.publicBase = publicBase;
        }

        /** The {@code [mirrors]} key. */
        public String key() {
            return key;
        }

        /** {@code JK_<TOOL>_DIST_MIRROR}. */
        public String env() {
            return "JK_" + key.toUpperCase(Locale.ROOT) + "_DIST_MIRROR";
        }

        public String publicBase() {
            return publicBase;
        }
    }

    private DistMirrors() {}

    /** The origin {@code dist} downloads from. */
    public static DownloadOrigin origin(Dist dist) {
        return origin(dist, JkDirs::env, GlobalConfig.mirrors(), MavenSettings.current());
    }

    /** As {@link #origin(Dist)} against explicit inputs, for tests. */
    public static DownloadOrigin origin(
            Dist dist, Function<String, @Nullable String> env, Map<String, String> mirrors, MavenSettings settings) {
        return DownloadOrigins.resolve(
                env,
                settings,
                dist.env(),
                mirrors.get(dist.key()),
                "[mirrors] " + dist.key(),
                dist.mirrorOf,
                dist.publicBase);
    }

    /** The base URL {@code dist} downloads from, ending in {@code /}. */
    public static URI base(Dist dist) {
        return origin(dist).url();
    }

    /** The distribution a {@link BuildTool} downloads from, or null for a tool with no fixed origin. */
    public static @Nullable Dist of(BuildTool tool) {
        return switch (tool) {
            case NODE -> Dist.NODE;
            case KOTLIN -> Dist.KOTLIN;
            case GRADLE -> Dist.GRADLE;
            case MAVEN -> Dist.MAVEN;
            default -> null;
        };
    }

    /** {@code http} with the credential of {@code tool}'s origin on requests under it; {@code http} itself for a tool with none. */
    public static Http authorized(Http http, BuildTool tool) {
        Dist dist = of(tool);
        return dist == null ? http : http.withAuthorization(DownloadOrigins.authorization(List.of(origin(dist))));
    }
}
