// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What jk reads from Maven's {@code settings.xml}: {@code <server>} credentials, {@code <mirror>}
 * entries, active {@code <proxy>} entries and the {@code <repositories>} of the profiles that
 * are active, so a team already on Maven gets its repository setup with zero reconfiguration.
 *
 * <p>Two files, merged the way Maven merges them: the user's ({@code ~/.m2/settings.xml}, or the
 * file {@code JK_M2_SETTINGS} / the {@code jk.m2.settings} system property names — Maven's
 * {@code -s}) over the installation's ({@code $M2_HOME/conf/settings.xml}, else {@code
 * $MAVEN_HOME/conf/settings.xml} — Maven's {@code -gs}). An entry of the user's file wins over
 * one of the installation's with the same id; active-profile ids are the union of both.
 *
 * <p>A mirror is a <em>transport</em> fact: the resolver keeps asking the repository it names,
 * the lock records that repository, and only the URL the HTTP client opens changes on this
 * machine. Best-effort and read-only: a missing or malformed file is an empty result, never an
 * error. {@code privateKey} / {@code passphrase} / {@code configuration} on a server, Maven's
 * {@code ${...}} property expansion and password encryption, {@code <pluginRepositories>} and
 * {@code <pluginGroups>} are not read.
 */
public final class MavenSettings {

    /** The system property naming the user settings file — how a test points jk at a fixture. */
    public static final String SETTINGS_PROPERTY = "jk.m2.settings";

    /** The environment variable naming the user settings file: Maven's {@code -s} for jk. */
    public static final String SETTINGS_ENV = "JK_M2_SETTINGS";

    /** A server entry: a username/password keyed by repository id. */
    public record Server(
            String id, @Nullable String username, @Nullable String password) {}

    /**
     * A {@code <mirror>}: the repositories {@code mirrorOf} matches are fetched from {@code url}
     * instead. Its credential is the {@code <server>} with the mirror's own {@code id}.
     * {@code file} is the settings file that declared it, for a diagnostic.
     */
    public record Mirror(String id, String mirrorOf, URI url, Path file) {

        /**
         * Whether this mirror stands in for the repository {@code repoId} at {@code repoUrl},
         * under Maven's {@code mirrorOf} grammar: {@code *} every repository; {@code external:*}
         * every repository not on this machine (localhost or {@code file:}); {@code external:http:*}
         * every plaintext-http repository not on this machine; {@code a,b} either id; {@code
         * *,!excluded} every repository but that one. An exact id match or an exclusion decides
         * outright; a wildcard keeps reading so a later exclusion can veto it.
         */
        public boolean matches(String repoId, URI repoUrl) {
            String pattern = mirrorOf.strip();
            if (pattern.equals("*") || pattern.equals(repoId)) return true;
            boolean result = false;
            for (String token : pattern.split(",")) {
                String t = token.strip();
                if (t.length() > 1 && t.startsWith("!")) {
                    if (t.substring(1).equals(repoId)) return false;
                } else if (t.equals(repoId)) {
                    return true;
                } else if (t.equals("external:*") && external(repoUrl)) {
                    result = true;
                } else if (t.equals("external:http:*") && externalHttp(repoUrl)) {
                    result = true;
                } else if (t.equals("*")) {
                    result = true;
                }
            }
            return result;
        }

        /** Not on this machine: neither a loopback host nor a {@code file:} URL. */
        static boolean external(URI url) {
            return !"file".equalsIgnoreCase(url.getScheme()) && !RepositorySpec.loopback(url.getHost());
        }

        private static boolean externalHttp(URI url) {
            return "http".equalsIgnoreCase(url.getScheme()) && external(url);
        }

        /** {@code mirror `nexus` (~/.m2/settings.xml)} — how a diagnostic names this entry. */
        public String label() {
            return "mirror `" + id + "` (" + file + ")";
        }
    }

    /**
     * An active {@code <proxy>}: what the requests for {@code protocol} targets go through. The
     * proxy itself is always reached over http; {@code nonProxyHosts} is Maven's {@code |}-separated
     * list of host globs that go direct.
     */
    public record Proxy(
            String id,
            String protocol,
            String host,
            int port,
            @Nullable String username,
            @Nullable String password,
            List<String> nonProxyHosts,
            Path file) {

        public Proxy {
            nonProxyHosts = List.copyOf(nonProxyHosts);
        }

        /** True when {@code targetHost} is on {@code nonProxyHosts}; {@code *} matches any run of characters. */
        public boolean bypasses(String targetHost) {
            String target = targetHost.toLowerCase(Locale.ROOT);
            for (String glob : nonProxyHosts) {
                String g = glob.strip().toLowerCase(Locale.ROOT);
                if (g.isEmpty()) continue;
                StringBuilder regex = new StringBuilder();
                for (String literal : g.split("\\*", -1)) {
                    if (!regex.isEmpty()) regex.append(".*");
                    regex.append(Pattern.quote(literal));
                }
                if (Pattern.matches(regex.toString(), target)) return true;
            }
            return false;
        }

        /**
         * The proxy as {@code http://[user:password@]host:port}, the spelling every other proxy
         * source uses; the multi-argument {@link URI} constructor quotes what the credential
         * needs quoted.
         */
        public URI url() {
            String userInfo = username == null ? null : username + ":" + (password == null ? "" : password);
            try {
                return new URI("http", userInfo, host, port, null, null, null);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("proxy `" + id + "` in " + file + " is not a URL", e);
            }
        }

        /** {@code <proxy> `corp` (~/.m2/settings.xml)} — how a diagnostic names this entry. */
        public String label() {
            return "<proxy> `" + id + "` (" + file + ")";
        }
    }

    /** One {@code <profile>}: its id, whether {@code activeByDefault}, and its repositories. */
    record Profile(String id, boolean activeByDefault, List<RepositorySpec> repositories) {}

    private static final MavenSettings EMPTY =
            new MavenSettings(Map.of(), List.of(), List.of(), List.of(), Set.of(), List.of());

    private final Map<String, Server> servers;
    private final List<Mirror> mirrors;
    private final List<Proxy> proxies;
    private final List<Profile> profiles;
    private final Set<String> activeProfileIds;
    private final List<Path> files;

    MavenSettings(
            Map<String, Server> servers,
            List<Mirror> mirrors,
            List<Proxy> proxies,
            List<Profile> profiles,
            Set<String> activeProfileIds,
            List<Path> files) {
        this.servers = Map.copyOf(servers);
        this.mirrors = List.copyOf(mirrors);
        this.proxies = List.copyOf(proxies);
        this.profiles = List.copyOf(profiles);
        this.activeProfileIds = Set.copyOf(activeProfileIds);
        this.files = List.copyOf(files);
    }

    public static MavenSettings empty() {
        return EMPTY;
    }

    /** True when no file contributed anything jk reads. */
    public boolean isEmpty() {
        return servers.isEmpty()
                && mirrors.isEmpty()
                && proxies.isEmpty()
                && profileRepositories().isEmpty();
    }

    /** Credentials for the given repository id, if a {@code <server>} defined them. */
    public Optional<Server> server(String id) {
        return Optional.ofNullable(servers.get(id));
    }

    /** Every {@code <mirror>}, user file first, in declaration order. */
    public List<Mirror> mirrors() {
        return mirrors;
    }

    /** Every active {@code <proxy>}, user file first, in declaration order. */
    public List<Proxy> proxies() {
        return proxies;
    }

    /** The settings files that were read, user file first; empty when neither exists. */
    public List<Path> files() {
        return files;
    }

    /** The first mirror whose {@code mirrorOf} matches the repository {@code repoId} at {@code url}. */
    public Optional<Mirror> mirrorFor(String repoId, URI url) {
        for (Mirror mirror : mirrors) {
            if (mirror.matches(repoId, url)) return Optional.of(mirror);
        }
        return Optional.empty();
    }

    /**
     * The first active proxy for targets of {@code scheme}. Maven matches the proxy's {@code
     * <protocol>} against the repository URL's scheme, so an https repository needs a proxy entry
     * whose protocol is {@code https}.
     */
    public Optional<Proxy> proxyFor(String scheme) {
        for (Proxy proxy : proxies) {
            if (proxy.protocol().equalsIgnoreCase(scheme)) return Optional.of(proxy);
        }
        return Optional.empty();
    }

    /**
     * The repositories of the active profiles, in profile order, one per id. A profile is active
     * when {@code <activeProfiles>} lists it, or when it is {@code activeByDefault} and no profile
     * of the file is listed.
     */
    public List<RepositorySpec> profileRepositories() {
        boolean anyListed = profiles.stream().anyMatch(p -> activeProfileIds.contains(p.id()));
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            boolean active = activeProfileIds.contains(profile.id()) || (profile.activeByDefault() && !anyListed);
            if (!active) continue;
            for (RepositorySpec repo : profile.repositories()) byName.putIfAbsent(repo.name(), repo);
        }
        return List.copyOf(byName.values());
    }

    /**
     * The URL this file binds {@code id} to — a mirror's own, or an active profile repository's —
     * which is what lets a {@code <server>} credential for that id travel: the file is the user's,
     * not the project's.
     */
    public Optional<URI> declaredUrl(String id) {
        for (Mirror mirror : mirrors) {
            if (mirror.id().equals(id)) return Optional.of(mirror.url());
        }
        for (RepositorySpec repo : profileRepositories()) {
            if (repo.name().equals(id)) return Optional.of(repo.url());
        }
        return Optional.empty();
    }

    /** This file's entries over {@code global}'s: an id the user's file declares hides the installation's. */
    MavenSettings over(MavenSettings global) {
        Map<String, Server> mergedServers = new LinkedHashMap<>(servers);
        global.servers.forEach(mergedServers::putIfAbsent);
        Set<String> ids = new LinkedHashSet<>(activeProfileIds);
        ids.addAll(global.activeProfileIds);
        List<Path> read = new ArrayList<>(files);
        read.addAll(global.files);
        return new MavenSettings(
                mergedServers,
                byId(mirrors, global.mirrors, Mirror::id),
                byId(proxies, global.proxies, Proxy::id),
                byId(profiles, global.profiles, Profile::id),
                ids,
                read);
    }

    private static <T> List<T> byId(List<T> user, List<T> global, Function<T, String> id) {
        Map<String, T> merged = new LinkedHashMap<>();
        for (T t : user) merged.putIfAbsent(id.apply(t), t);
        for (T t : global) merged.putIfAbsent(id.apply(t), t);
        return List.copyOf(merged.values());
    }

    /** Load from an explicit file alone; missing/invalid → empty. */
    public static MavenSettings loadFrom(@Nullable Path settingsXml) {
        return settingsXml == null ? EMPTY : SettingsXml.read(settingsXml);
    }

    /** The user file over the installation file, both located through the ambient environment. */
    public static MavenSettings load() {
        return load(BuildEnv.ambient());
    }

    /** As {@link #load()} with the environment given — tests. */
    static MavenSettings load(Function<String, @Nullable String> env) {
        MavenSettings user = loadFrom(userSettingsPath(env));
        Path global = globalSettingsPath(env);
        return global == null ? user : user.over(loadFrom(global));
    }

    /**
     * {@link #load()}, memoized on the two files' size and mtime: the engine is resident and asks
     * per request — the proxy selector once per HTTP request — so the files are re-read only when
     * they change, and a file that appears or is edited is seen by the next request.
     */
    public static MavenSettings current() {
        Function<String, @Nullable String> env = BuildEnv.ambient();
        Path user = userSettingsPath(env);
        Path global = globalSettingsPath(env);
        String key = user + "\n" + global;
        List<StampedMemo.FileStamp> stamp = List.of(
                Objects.requireNonNullElse(StampedMemo.FileStamp.of(user), ABSENT),
                Objects.requireNonNullElse(StampedMemo.FileStamp.of(global), ABSENT));
        return CURRENT.get(key, stamp, () -> load(env));
    }

    /** The stamp of a file that is not there, so an absent file is a state and not a cache miss. */
    private static final StampedMemo.FileStamp ABSENT = new StampedMemo.FileStamp(-1, FileTime.fromMillis(0));

    private static final StampedMemo<String, List<StampedMemo.FileStamp>, MavenSettings> CURRENT = StampedMemo.create();

    /**
     * The user settings file: the {@code jk.m2.settings} system property, then {@code
     * JK_M2_SETTINGS}, then {@code ~/.m2/settings.xml}; null when there is no home directory.
     */
    public static @Nullable Path userSettingsPath() {
        return userSettingsPath(BuildEnv.ambient());
    }

    static @Nullable Path userSettingsPath(Function<String, @Nullable String> env) {
        String prop = System.getProperty(SETTINGS_PROPERTY);
        if (prop != null && !prop.isBlank()) return Path.of(prop.strip());
        String fromEnv = env.apply(SETTINGS_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) return Path.of(fromEnv.strip());
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        return Path.of(home, ".m2", "settings.xml");
    }

    /** {@code $M2_HOME/conf/settings.xml}, else {@code $MAVEN_HOME/conf/settings.xml}; null when neither is set. */
    static @Nullable Path globalSettingsPath(Function<String, @Nullable String> env) {
        for (String variable : new String[] {"M2_HOME", "MAVEN_HOME"}) {
            String home = env.apply(variable);
            if (home != null && !home.isBlank()) return Path.of(home.strip(), "conf", "settings.xml");
        }
        return null;
    }
}
