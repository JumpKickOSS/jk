// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.model.MirrorOf;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
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
 * error.
 *
 * <p>Every value is expanded the way Maven expands it — {@code ${env.NAME}}, {@code ${user.home}}
 * and the other system properties, see {@link SettingsValues} — against the environment of the
 * request that asks: {@link #current()} keeps the parse while the files are unchanged and expands
 * it per request, so {@code NEXUS_PASSWORD=… jk build} is seen by a resident engine started
 * without it. An encrypted {@code {...}} password is decrypted with the master password of {@code
 * settings-security.xml} — for compatibility only, see {@link MavenPasswords}. {@code privateKey}
 * / {@code configuration} on a server, {@code <pluginRepositories>} and {@code <pluginGroups>} are
 * not read.
 */
public final class MavenSettings {

    /** The system property naming the user settings file — how a test points jk at a fixture. */
    public static final String SETTINGS_PROPERTY = "jk.m2.settings";

    /** The environment variable naming the user settings file: Maven's {@code -s} for jk. */
    public static final String SETTINGS_ENV = "JK_M2_SETTINGS";

    /** A server entry: a username/password keyed by repository id, and the key passphrase it names. */
    public record Server(
            String id,
            @Nullable String username,
            @Nullable String password,
            @Nullable String passphrase) {

        public Server(String id, @Nullable String username, @Nullable String password) {
            this(id, username, password, null);
        }
    }

    /**
     * A {@code <mirror>}: the repositories {@code mirrorOf} matches are fetched from {@code url}
     * instead. Its credential is the {@code <server>} with the mirror's own {@code id}.
     * {@code file} is the settings file that declared it, for a diagnostic.
     */
    public record Mirror(String id, String mirrorOf, URI url, Path file) {

        /**
         * Whether this mirror stands in for the repository {@code repoId} at {@code repoUrl},
         * under Maven's {@code mirrorOf} grammar — {@link MirrorOf}, the matcher a {@code
         * mirror-of} list in {@code ~/.jk/config.toml} is read by too.
         */
        public boolean matches(String repoId, URI repoUrl) {
            return MirrorOf.parse(mirrorOf).matches(repoId, repoUrl);
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
            new MavenSettings(Map.of(), List.of(), List.of(), List.of(), Set.of(), List.of(), List.of());

    private final Map<String, Server> servers;
    private final List<Mirror> mirrors;
    private final List<Proxy> proxies;
    private final List<Profile> profiles;
    private final Set<String> activeProfileIds;
    private final List<Path> files;
    private final List<SettingsValues.Notice> notices;

    MavenSettings(
            Map<String, Server> servers,
            List<Mirror> mirrors,
            List<Proxy> proxies,
            List<Profile> profiles,
            Set<String> activeProfileIds,
            List<Path> files,
            List<SettingsValues.Notice> notices) {
        this.servers = Map.copyOf(servers);
        this.mirrors = List.copyOf(mirrors);
        this.proxies = List.copyOf(proxies);
        this.profiles = List.copyOf(profiles);
        this.activeProfileIds = Set.copyOf(activeProfileIds);
        this.files = List.copyOf(files);
        this.notices = List.copyOf(notices);
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

    /**
     * What reading the files found wrong for this request — an expression that did not expand, a
     * password that did not decrypt — one line each, as the run's warnings say it.
     */
    public List<String> warnings() {
        return notices.stream().map(SettingsValues.Notice::message).toList();
    }

    /** Say {@link #warnings()} on this run, once each. */
    private MavenSettings warn() {
        for (SettingsValues.Notice notice : notices) notice.say();
        return this;
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
        Set<SettingsValues.Notice> said = new LinkedHashSet<>(notices);
        said.addAll(global.notices);
        return new MavenSettings(
                mergedServers,
                byId(mirrors, global.mirrors, Mirror::id),
                byId(proxies, global.proxies, Proxy::id),
                byId(profiles, global.profiles, Profile::id),
                ids,
                read,
                List.copyOf(said));
    }

    private static <T> List<T> byId(List<T> user, List<T> global, Function<T, String> id) {
        Map<String, T> merged = new LinkedHashMap<>();
        for (T t : user) merged.putIfAbsent(id.apply(t), t);
        for (T t : global) merged.putIfAbsent(id.apply(t), t);
        return List.copyOf(merged.values());
    }

    /** Load from an explicit file alone, expanded against the request's environment; missing/invalid → empty. */
    public static MavenSettings loadFrom(@Nullable Path settingsXml) {
        return loadFrom(settingsXml, BuildEnv.ambient()).warn();
    }

    /** As {@link #loadFrom(Path)} expanding against {@code env}, without saying the warnings — tests. */
    static MavenSettings loadFrom(@Nullable Path settingsXml, Function<String, @Nullable String> env) {
        return settingsXml == null ? EMPTY : SettingsXml.read(SettingsXml.parse(settingsXml), SettingsValues.of(env));
    }

    /** The user file over the installation file, both located through the ambient environment. */
    public static MavenSettings load() {
        return load(BuildEnv.ambient()).warn();
    }

    /** As {@link #load()} with the environment given, without saying the warnings — tests. */
    static MavenSettings load(Function<String, @Nullable String> env) {
        return Parsed.of(env).expand(SettingsValues.of(env));
    }

    /**
     * {@link #load()} for the request being served. The parse is memoized on the two files'
     * identity: the engine is resident and asks per request — the proxy selector once per HTTP
     * request — so the files are re-read only when they change, and a file that appears or is
     * edited is seen by the next request. The expansion is the request's own: it is reused only
     * while every variable it read answers the same, so one shell's {@code ${env.NEXUS_PASSWORD}}
     * is never another's.
     */
    public static MavenSettings current() {
        Function<String, @Nullable String> env = BuildEnv.ambient();
        Path user = userSettingsPath(env);
        Path global = globalSettingsPath(env);
        String key = user + "\n" + global;
        List<StampedMemo.FileStamp> stamp = List.of(
                Objects.requireNonNullElse(StampedMemo.FileStamp.of(user), ABSENT),
                Objects.requireNonNullElse(StampedMemo.FileStamp.of(global), ABSENT));
        return CURRENT.get(key, stamp, () -> Parsed.of(env)).current(env).warn();
    }

    /**
     * {@code raw}, a value of the settings file the user names, expanded against the request's
     * environment the way every other value of the file is — for a reader of the file that is not
     * this class ({@code <localRepository>}). Says nothing: what did not resolve is listed.
     */
    public static Expansion expand(String raw) {
        SettingsValues.Expanded expanded = SettingsValues.of(BuildEnv.ambient()).expandQuietly(raw);
        return new Expansion(expanded.value(), expanded.unresolved());
    }

    /** One expanded value, and the expressions in it ({@code env.HOME}) that did not resolve. */
    public record Expansion(String value, List<String> unresolved) {

        public Expansion {
            unresolved = List.copyOf(unresolved);
        }
    }

    /**
     * The parse of the user and installation files, and the last expansion of it with the lookups
     * that expansion made. Concurrent requests from different shells each get their own values;
     * at worst they take turns recomputing.
     */
    private static final class Parsed {

        private final SettingsXml.@Nullable Document user;
        private final SettingsXml.@Nullable Document global;
        private volatile @Nullable Expanded last;

        private record Expanded(SettingsValues values, MavenSettings settings) {}

        private Parsed(SettingsXml.@Nullable Document user, SettingsXml.@Nullable Document global) {
            this.user = user;
            this.global = global;
        }

        static Parsed of(Function<String, @Nullable String> env) {
            Path userPath = userSettingsPath(env);
            Path globalPath = globalSettingsPath(env);
            return new Parsed(
                    userPath == null ? null : SettingsXml.parse(userPath),
                    globalPath == null ? null : SettingsXml.parse(globalPath));
        }

        MavenSettings current(Function<String, @Nullable String> env) {
            SettingsValues values = SettingsValues.of(env);
            Expanded hit = last;
            if (hit != null && hit.values().stillHolds(values)) return hit.settings();
            MavenSettings fresh = expand(values);
            last = new Expanded(values, fresh);
            return fresh;
        }

        MavenSettings expand(SettingsValues values) {
            MavenSettings userSettings = SettingsXml.read(user, values);
            return global == null ? userSettings : userSettings.over(SettingsXml.read(global, values));
        }
    }

    /** The stamp of a file that is not there, so an absent file is a state and not a cache miss. */
    private static final StampedMemo.FileStamp ABSENT = new StampedMemo.FileStamp(new StatIdentity(-1, 0, 0, ""));

    private static final StampedMemo<String, List<StampedMemo.FileStamp>, Parsed> CURRENT = StampedMemo.create();

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
