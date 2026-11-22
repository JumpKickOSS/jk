// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.config.RunNotices;
import cc.jumpkick.config.TomlScan;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where a request's shared cache is and whether the request writes to it: the user's own setting,
 * never the project's. A project's {@code jk.toml} is committed and read by every checkout, forks
 * included, so neither the URL a build sends its keys to nor the credential it sends with them may
 * come from there.
 *
 * <p>The URL is {@value #URL_ENV} of the shell that ran the command, else {@code [cache] remote} in
 * {@code ~/.jk/config.toml}; {@code https}, or {@code http} to this machine only, since the request
 * carries a credential. The credential is the repository credential chain's under the URL's {@code
 * host[:port]} ({@code JK_REPO_<HOST>_TOKEN}, {@code jk repo login <host>}).
 *
 * <p>Whether the request writes: never for a pull request's CI run, which may run code its author
 * wrote; else {@value #WRITE_ENV}, else {@code [cache] remote-write}; else on CI and not on a
 * developer's machine. The service decides what a credential may write in the end: a caller it
 * gives no write scope sends nothing.
 *
 * @param url the service's base URL
 * @param writes whether the request uploads what it stores
 */
public record RemoteCacheSettings(URI url, boolean writes) {

    /** The shared cache's URL for one shell; {@code off} or empty for none. */
    public static final String URL_ENV = "JK_CACHE_REMOTE";

    /** {@code true} or {@code false}: whether a request uploads what it stores. */
    public static final String WRITE_ENV = "JK_CACHE_REMOTE_WRITE";

    /** The settings {@code env} and {@code userConfig} give, or null when they name no shared cache. */
    static @Nullable RemoteCacheSettings resolve(Function<String, @Nullable String> env, Path userConfig) {
        TomlScan file = Files.isRegularFile(userConfig)
                ? TomlScan.scan(userConfig, JkCacheConfig.REMOTE.dotted(), JkCacheConfig.REMOTE_WRITE.dotted())
                : null;
        String raw =
                EnvValues.string(env, URL_ENV).orElse(file == null ? null : file.get(JkCacheConfig.REMOTE.dotted()));
        // A URL, or a word of the truth set saying no (off, false): none.
        if (raw == null || raw.isBlank() || EnvValues.parseBool(raw).isPresent()) return null;
        URI url = url(raw.strip());
        if (url == null) return null;
        Boolean explicit = EnvValues.bool(env, WRITE_ENV)
                .orElse(
                        file == null
                                ? null
                                : EnvValues.parseBool(file.get(JkCacheConfig.REMOTE_WRITE.dotted()))
                                        .orElse(null));
        boolean writes = !pullRequest(env) && (explicit != null ? explicit : EnvValues.isCi(env));
        return new RemoteCacheSettings(url, writes);
    }

    /** {@code raw} as a URL a credential may be sent to, or null, with the reason, when it is not one. */
    static @Nullable URI url(String raw) {
        URI url;
        try {
            url = URI.create(raw);
        } catch (IllegalArgumentException malformed) {
            refused(raw, "it is not a URL");
            return null;
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (url.getRawUserInfo() != null) {
            refused(raw, "a credential does not go in the URL; use JK_REPO_<HOST>_TOKEN or jk repo login <host>");
            return null;
        }
        if (url.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            refused(raw, "it is not an https URL");
            return null;
        }
        if (scheme.equals("http") && !loopback(url.getHost())) {
            refused(raw, "plain http is for this machine only; the request carries a credential");
            return null;
        }
        return url;
    }

    /**
     * Whether {@code env} is a CI run for a pull or merge request: its code is the request's author's,
     * so its results are not for others to take.
     */
    static boolean pullRequest(Function<String, @Nullable String> env) {
        String github = env.apply("GITHUB_EVENT_NAME");
        if (github != null && github.startsWith("pull_request")) return true;
        if ("merge_request_event".equals(env.apply("CI_PIPELINE_SOURCE"))) return true;
        if (set(env.apply("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME"))) return true;
        // Buildkite says false for a push, else the pull request's number.
        String buildkite = env.apply("BUILDKITE_PULL_REQUEST");
        if (set(buildkite) && EnvValues.parseBool(buildkite).orElse(true)) return true;
        return set(env.apply("CIRCLE_PULL_REQUEST"));
    }

    private static boolean set(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static boolean loopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) return true;
        if (!Character.isDigit(h.charAt(0)) && !h.contains(":")) return false;
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException notAnAddress) {
            return false;
        }
    }

    private static void refused(String raw, String why) {
        String shown = raw.replaceAll("//[^/@]*@", "//");
        RunNotices.warnOnce(
                "remote-cache-url:" + shown,
                () -> "jk: warning: shared cache " + shown + " not used: " + why + " (" + URL_ENV
                        + " or [cache] remote in ~/.jk/config.toml)");
    }
}
