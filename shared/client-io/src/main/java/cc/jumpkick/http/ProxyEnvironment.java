// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NetworkConfig;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The proxy jk's HTTP goes through, decided per request from {@code ~/.jk/config.toml}
 * {@code [network]}, failing that — for {@link Traffic#REPOSITORY repository} traffic only — the
 * active {@code <proxy>} of Maven's {@code settings.xml}, and failing that the proxy variables of
 * the shell that ran {@code jk}. Maven scopes its proxies to repositories, so jk does too: a
 * settings.xml proxy that admits only the artifact host never carries a JDK download or a forge
 * API call, which {@code [network]} and the shell variables keep covering.
 *
 * <p>Decided at {@link #select} time, not when the client is built: the engine is resident and
 * serves every later terminal, so a proxy captured once would be one network's answer for days.
 * {@link BuildEnv#ambient()} answers from the request's shell first and the engine's own
 * environment second, and both files are re-read when they change.
 *
 * <p>A Maven proxy is matched the way Maven matches it: its {@code <protocol>} against the
 * target's scheme, so https targets take the entry whose protocol is {@code https}; its {@code
 * <nonProxyHosts>} ({@code |}-separated host globs) go direct, as do the {@code no-proxy} hosts of
 * the file and the shell; its username and password ride as Basic exactly as a credential in a
 * proxy URL does.
 *
 * <p>Spellings: {@code https_proxy} / {@code HTTPS_PROXY} for https targets, {@code http_proxy} /
 * {@code HTTP_PROXY} for http ones (lower case wins when both are set), {@code no_proxy} /
 * {@code NO_PROXY} for the hosts that go direct — {@code *}, a host, a {@code .suffix} (a bare
 * suffix matches its subdomains too), or {@code host:port} for one port only. A proxy URL is
 * {@code http://[user:password@]host[:port]}; without a scheme it is taken as http, and https
 * targets tunnel through it with CONNECT. Loopback targets always go direct: a proxy for
 * {@code 127.0.0.1} is never what anyone meant, and every local stub jk's own tests stand up would
 * otherwise route through a developer's corporate proxy.
 *
 * <p>A proxy credential (Basic only) rides each request to the proxy as {@code
 * Proxy-Authorization} — {@link #proxyAuthorization} — rather than through an {@link
 * java.net.Authenticator}: a client with one installed fails every origin 401 the authenticator
 * declines, and jk's callers answer their own 401s. The credential is never part of a message: a
 * proxy URL is printed through {@link SafeUri#forMessage}, and an unusable value is reported by
 * the name that set it, not its text.
 */
public final class ProxyEnvironment extends ProxySelector {

    private static final String TUNNELING_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes";

    /**
     * One proxy setting: what wrote it (for a diagnostic), the URL it wrote, and the hosts this
     * setting alone sends direct — Maven's {@code <nonProxyHosts>}; empty for the file and the shell,
     * whose bypass lists apply to every source.
     */
    record Source(String name, String value, MavenSettings.@Nullable Proxy maven) {

        Source(String name, String value) {
            this(name, value, null);
        }

        static Source of(MavenSettings.Proxy proxy) {
            return new Source("~/.m2/settings.xml " + proxy.label(), proxy.url().toString(), proxy);
        }

        /** True when this source's own bypass list sends {@code host} direct. */
        boolean bypasses(String host) {
            return maven != null && maven.bypasses(host);
        }
    }

    /** What one lookup found: a proxy per target scheme, and the hosts that go direct. */
    record Settings(@Nullable Source http, @Nullable Source https, List<String> noProxy) {

        /** The file first, then Maven's settings, then the shell; the bypass lists of the file and the shell. */
        static Settings from(NetworkConfig config, MavenSettings maven, Function<String, @Nullable String> env) {
            Source configProxy = source("[network] proxy", config.proxy());
            Source mavenHttp = maven.proxyFor("http").map(Source::of).orElse(null);
            Source http = configProxy != null
                    ? configProxy
                    : mavenHttp != null ? mavenHttp : fromEnv(env, "http_proxy", "HTTP_PROXY");
            Source configHttps = source("[network] https-proxy", config.httpsProxy());
            Source mavenHttps = maven.proxyFor("https").map(Source::of).orElse(null);
            Source https = configHttps != null
                    ? configHttps
                    : configProxy != null
                            ? configProxy
                            : mavenHttps != null ? mavenHttps : fromEnv(env, "https_proxy", "HTTPS_PROXY");
            List<String> noProxy = new ArrayList<>(config.noProxy());
            addEntries(noProxy, env.apply("no_proxy"));
            addEntries(noProxy, env.apply("NO_PROXY"));
            return new Settings(http, https, List.copyOf(noProxy));
        }

        private static @Nullable Source fromEnv(Function<String, @Nullable String> env, String lower, String upper) {
            Source fromLower = source(lower, env.apply(lower));
            return fromLower != null ? fromLower : source(upper, env.apply(upper));
        }

        private static @Nullable Source source(String name, @Nullable String value) {
            return value == null || value.isBlank() ? null : new Source(name, value.trim());
        }

        private static void addEntries(List<String> into, @Nullable String csv) {
            if (csv == null) return;
            for (String entry : csv.split(",")) {
                if (!entry.isBlank()) into.add(entry.trim());
            }
        }
    }

    /** Where a proxy listens, the Basic credential its URL carried, and the URL safe to print. */
    record Endpoint(InetSocketAddress address, @Nullable PasswordAuthentication credential, URI shown) {

        /** Empty, after a once-per-run warning, when the value is not a proxy this client can use. */
        static Optional<Endpoint> parse(Source source) {
            String text = source.value().contains("://") ? source.value() : "http://" + source.value();
            URI uri;
            try {
                uri = new URI(text);
            } catch (URISyntaxException e) {
                return unusable(source, "is not a URL");
            }
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                return unusable(
                        source,
                        "names the scheme `" + uri.getScheme()
                                + "`; only an http proxy is supported (https targets tunnel through it)");
            }
            if (uri.getHost() == null) return unusable(source, "names no host");
            PasswordAuthentication credential = null;
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
                String password = colon < 0 ? "" : userInfo.substring(colon + 1);
                credential = new PasswordAuthentication(user, password.toCharArray());
            }
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            // Unresolved on purpose: the client resolves the proxy when it connects, so a laptop
            // that changes networks is not pinned to the address the first request saw.
            return Optional.of(new Endpoint(
                    InetSocketAddress.createUnresolved(uri.getHost(), port),
                    credential,
                    Objects.requireNonNull(SafeUri.withoutUserInfo(uri))));
        }

        private static Optional<Endpoint> unusable(Source source, String why) {
            // The value is not printed: a malformed proxy URL is the likeliest place for a
            // credential to sit unparsed.
            RunNotices.warnOnce(
                    "proxy-unusable:" + source.name(),
                    () -> "jk: warning: ignoring " + source.name() + ": its value " + why
                            + "; requests go direct. Expected http://[user:password@]host[:port].");
            return Optional.empty();
        }

        /** The {@code Proxy-Authorization} value for this proxy, or empty when its URL carried no credential. */
        Optional<String> proxyAuthorization() {
            if (credential == null) return Optional.empty();
            String pair = credential.getUserName() + ":" + new String(credential.getPassword());
            return Optional.of("Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private final Supplier<NetworkConfig> config;
    private final Supplier<MavenSettings> maven;
    private final Supplier<Function<String, @Nullable String>> env;

    /** What a client's requests are for: only repository traffic reads Maven's {@code <proxy>}. */
    public enum Traffic {
        /** JDK and tool distributions, forge APIs, the engine jar, release checks, readiness probes. */
        GENERAL,
        /** Artifacts, POMs and metadata from Maven repositories — what Maven's own proxy covers. */
        REPOSITORY
    }

    /**
     * Production, for {@link Traffic#GENERAL} requests: the user's config file and the request's
     * shell (then the engine's own), read per request.
     */
    public static ProxyEnvironment ambient() {
        return ambient(Traffic.GENERAL);
    }

    /**
     * Production for {@code traffic}: the user's config file, Maven's settings when the traffic is
     * a repository's, and the request's shell (then the engine's own), read per request.
     */
    public static ProxyEnvironment ambient(Traffic traffic) {
        return of(traffic, GlobalConfig::network, MavenSettings::current, BuildEnv::ambient);
    }

    /** {@link #ambient(Traffic)} with every source injected: Maven's settings are read for repository traffic alone. */
    static ProxyEnvironment of(
            Traffic traffic,
            Supplier<NetworkConfig> config,
            Supplier<MavenSettings> maven,
            Supplier<Function<String, @Nullable String>> env) {
        return new ProxyEnvironment(config, traffic == Traffic.REPOSITORY ? maven : MavenSettings::empty, env);
    }

    /** Visible for tests — the file and the shell injected, no Maven settings. */
    ProxyEnvironment(Supplier<NetworkConfig> config, Supplier<Function<String, @Nullable String>> env) {
        this(config, MavenSettings::empty, env);
    }

    /** Visible for tests — every source of the lookup injected. */
    ProxyEnvironment(
            Supplier<NetworkConfig> config,
            Supplier<MavenSettings> maven,
            Supplier<Function<String, @Nullable String>> env) {
        this.config = Objects.requireNonNull(config, "config");
        this.maven = Objects.requireNonNull(maven, "maven");
        this.env = Objects.requireNonNull(env, "env");
        // The JDK drops a Basic Proxy-Authorization from a CONNECT tunnel unless told otherwise, and
        // a proxy URL carrying a credential is that instruction. A value the user set is left alone.
        // Done here rather than in a static initializer so the native CLI, whose class
        // initialization may run at image build time, still sets it in the live process.
        if (System.getProperty(TUNNELING_SCHEMES) == null) System.setProperty(TUNNELING_SCHEMES, "");
    }

    @Override
    public List<Proxy> select(URI uri) {
        Objects.requireNonNull(uri, "uri");
        return endpointFor(uri, settings())
                .<List<Proxy>>map(endpoint -> List.of(new Proxy(Proxy.Type.HTTP, endpoint.address())))
                .orElseGet(() -> List.of(Proxy.NO_PROXY));
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        // Nothing to fall back to: a proxy that refuses is the request's failure, and Http's retry
        // ladder already decides how often it is asked again.
    }

    Settings settings() {
        return Settings.from(config.get(), maven.get(), env.get());
    }

    /** The proxy {@code target} goes through under {@code settings}, or empty for a direct connection. */
    static Optional<Endpoint> endpointFor(URI target, Settings settings) {
        String host = target.getHost();
        if (host == null || RepositorySpec.loopback(host)) return Optional.empty();
        Source source = "https".equalsIgnoreCase(target.getScheme()) ? settings.https() : settings.http();
        if (source == null || bypassed(host, effectivePort(target), settings.noProxy())) return Optional.empty();
        if (source.bypasses(host)) return Optional.empty();
        return Endpoint.parse(source);
    }

    /**
     * Whether {@code host:port} is on the bypass list. Entries are case-insensitive; {@code *}
     * bypasses everything; {@code .example.com}, {@code *.example.com} and {@code example.com} all
     * match {@code example.com} and its subdomains; {@code host:port} matches that port only.
     */
    static boolean bypassed(String host, int port, List<String> noProxy) {
        String target = stripBrackets(host.toLowerCase(Locale.ROOT));
        for (String raw : noProxy) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) continue;
            if (entry.equals("*")) return true;
            int entryPort = -1;
            int colon = entry.lastIndexOf(':');
            if (colon > 0 && entry.indexOf(':') == colon) {
                try {
                    entryPort = Integer.parseInt(entry.substring(colon + 1));
                    entry = entry.substring(0, colon);
                } catch (NumberFormatException notAPort) {
                    // host:notaport — no such host will match, which is the right outcome
                }
            }
            if (entryPort != -1 && entryPort != port) continue;
            entry = stripBrackets(entry);
            if (entry.startsWith("*.")) entry = entry.substring(2);
            else if (entry.startsWith(".")) entry = entry.substring(1);
            if (entry.isEmpty()) continue;
            if (target.equals(entry) || target.endsWith("." + entry)) return true;
        }
        return false;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * The {@code Proxy-Authorization} header a request for {@code target} carries: the Basic
     * credential of the proxy {@link #select} routes it through, or empty when the request goes
     * direct or the proxy URL carried none. The JDK forwards the header to the proxy on a plain
     * request and on the CONNECT of an https tunnel; it never reaches an origin because {@link
     * Http} recomputes it for every redirect hop.
     */
    public Optional<String> proxyAuthorization(URI target) {
        return endpointFor(target, settings()).flatMap(Endpoint::proxyAuthorization);
    }
}
