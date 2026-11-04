// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.model.ImageTable;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositoryMirror;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/**
 * Machine-scoped preferences from {@code ~/.jk/config.toml}: root-level UI flags (e.g.
 * {@code nerd-font}) and global {@code [repositories]}. Not project-overridable; env overrides
 * apply. Project {@code [repositories]} win on name collision; global fills gaps.
 */
public final class GlobalConfig {

    private GlobalConfig() {}

    /**
     * Which Nerd Font glyph families may be painted. Precedence, highest first:
     *
     * <ol>
     *   <li>the color/ANSI gate — {@code --no-ansi}, {@code TERM=dumb}, {@code CI}, {@code NO_COLOR},
     *       {@code --color never}. Absolute: beats even an explicit env override.
     *   <li>env {@code JK_NERD_FONT} — the full value set, including the mode words.
     *   <li>env {@code NERD_FONT} — the host-wide cross-tool variable, booleans only.
     *   <li>{@code ~/.jk/config.toml} root-level {@code nerd-font}.
     *   <li>default {@code "auto"} → {@link NerdFontDetect}.
     * </ol>
     *
     * <p>Memoized for the life of the process. This is load-bearing, not an optimization:
     * {@code RenderContext.current()} calls it on every animation frame, and {@code auto} can reach
     * the filesystem.
     */
    public static NerdFontCaps nerdFont() {
        NerdFontCaps hit = resolvedNerdFont;
        if (hit != null) return hit;
        NerdFontCaps fresh = nerdFont(
                JkDirs.userConfigFile(), BuildEnv.get("JK_NERD_FONT"), BuildEnv.get("NERD_FONT"), colorEnabled());
        resolvedNerdFont = fresh;
        return fresh;
    }

    /** The process-wide resolved value; see {@link #nerdFont()}. Cleared by {@link #clearCache()}. */
    private static volatile @Nullable NerdFontCaps resolvedNerdFont;

    /**
     * The bare no-ANSI triple — {@code --no-ansi}, {@code TERM=dumb}, {@code CI=true/1}. When true,
     * <em>all</em> ANSI is off: color, Unicode glyphs, animations, cursor movement. Deliberately
     * narrower than {@link #colorEnabled()}: {@code NO_COLOR} / {@code --color never} only disable
     * color, never glyphs or animation. Owner of the triple — the interactivity axis
     * ({@code Interactivity}: {@code JK_NONINTERACTIVE}, CI-set-at-all) and the nerd-font probe
     * ({@code NerdFontDetect}: injectable env, reason strings) are different facts, not copies.
     */
    public static boolean ansiSuppressed() {
        return ansiSuppressed(SessionContext.current().config(), BuildEnv::get);
    }

    /** Injectable overload of {@link #ansiSuppressed()} — tests pin the trigger matrix here. */
    static boolean ansiSuppressed(JkConfig config, Function<String, @Nullable String> env) {
        if (config.noAnsiOr(false)) return true;
        // Forced ANSI outranks the environment suppressors: without it the mode cannot be pinned
        // in the ANSI direction at all, so an assertion about ANSI output passes or fails on
        // whatever TERM/CI the host happens to set.
        if (config.forceAnsiOr(false)) return false;
        if ("dumb".equals(env.apply("TERM"))) return true;
        return EnvValues.isCi(env);
    }

    /**
     * True when foreground color should be emitted: {@link #ansiSuppressed()} wins outright, then
     * the resolved {@code --color} choice ({@code AUTO} honors {@code NO_COLOR}, never isatty).
     */
    public static boolean colorEnabled() {
        return colorEnabled(SessionContext.current().config(), BuildEnv::get);
    }

    /** Injectable overload of {@link #colorEnabled()} — tests pin the trigger matrix here. */
    static boolean colorEnabled(JkConfig config, Function<String, @Nullable String> env) {
        if (ansiSuppressed(config, env)) return false;
        var choice = config.colorOr(JkConfig.ColorChoice.AUTO);
        return switch (choice) {
            case ALWAYS -> true;
            case NEVER -> false;
            // AUTO: emit color unless NO_COLOR is set. We don't gate on isatty —
            // many jk consumers (CI logs, `less -R`, pipes into other formatters)
            // benefit from preserved color, and users who want strictly plain
            // output can pass `--color never`.
            case AUTO -> {
                String nc = env.apply("NO_COLOR");
                yield nc == null || nc.isEmpty();
            }
        };
    }

    /** As {@link #nerdFont()} but against an explicit config file — for tests. */
    static NerdFontCaps nerdFont(Path configFile) {
        return nerdFont(configFile, BuildEnv.get("JK_NERD_FONT"), BuildEnv.get("NERD_FONT"), colorEnabled());
    }

    /** As {@link #nerdFont(Path)} but with explicit env values — bypasses the color gate for tests. */
    static NerdFontCaps nerdFont(Path configFile, @Nullable String jkEnv, @Nullable String hostEnv) {
        return nerdFont(configFile, jkEnv, hostEnv, true);
    }

    /**
     * Full testable overload: config file + both env values + explicit color-enabled flag. Resolves
     * {@code auto} against the real environment; {@link #nerdFontMode} is the seam for tests that
     * need to pin detection.
     */
    static NerdFontCaps nerdFont(
            Path configFile, @Nullable String jkEnv, @Nullable String hostEnv, boolean colorEnabled) {
        if (!colorEnabled) return NerdFontCaps.NONE;
        NerdFontMode mode = nerdFontMode(configFile, jkEnv, hostEnv);
        if (mode != NerdFontMode.AUTO) return mode.fixedCaps();
        NerdFontDetect.Result detected = NerdFontDetect.detect();
        lastAutoDetection = detected;
        return detected.caps();
    }

    /** The most recent {@code auto} detection in this process; null when none ran. */
    private static volatile NerdFontDetect.@Nullable Result lastAutoDetection;

    /**
     * What changed since the terminal's stored glyph confirmation, such as "terminal font and installed
     * fonts", reported once per change. Empty when detection was pinned, found nothing stale, or the
     * change was already reported.
     */
    public static Optional<String> nerdFontStaleChange() {
        NerdFontDetect.Result r = lastAutoDetection;
        if (r == null || r.stale() == null || !NerdFontDetect.claimStaleHint(r)) return Optional.empty();
        List<String> changed = r.stale().changed();
        return Optional.of(
                changed.size() < 2
                        ? String.join("", changed)
                        : String.join(", ", changed.subList(0, changed.size() - 1)) + " and " + changed.getLast());
    }

    /**
     * The declared mode, before {@code auto} is resolved. Split out so {@code jk self setup-terminal}
     * can report what was asked for separately from what was detected.
     */
    static NerdFontMode nerdFontMode(Path configFile, @Nullable String jkEnv, @Nullable String hostEnv) {
        return NerdFontMode.parse(jkEnv)
                .or(() -> NerdFontMode.parseBooleanOnly(hostEnv))
                .or(() -> stringFromRoot(configFile, "nerd-font").flatMap(NerdFontMode::parse))
                .orElse(NerdFontMode.AUTO);
    }

    /**
     * Engine-host JDK pin ({@code JK_ENGINE_JDK} → {@code [toolchain].jdk}), independent of
     * project {@code jdk}. Empty when unset.
     */
    public static Optional<String> engineJdkPin() {
        return engineJdkPin(JkDirs.userConfigFile(), ProcessEnv.get("JK_ENGINE_JDK"));
    }

    /** As {@link #engineJdkPin()} but against an explicit config file + env value — for tests. */
    static Optional<String> engineJdkPin(Path file, @Nullable String envValue) {
        if (envValue != null && !envValue.isBlank()) return Optional.of(envValue.trim());
        return stringFromGlobal(file, "toolchain", "jdk");
    }

    /** Root-level {@code self-update} as written, before {@link SelfUpdatePolicy} reads it. */
    public static Optional<String> selfUpdate() {
        return selfUpdate(JkDirs.userConfigFile());
    }

    /** As {@link #selfUpdate()} against an explicit config file. */
    public static Optional<String> selfUpdate(Path configFile) {
        return stringFromRoot(configFile, SelfUpdatePolicy.KEY);
    }

    /**
     * {@code [credentials] store}: where {@code jk repo login} and {@code jk auth login} keep a
     * secret ({@code keychain} or {@code file}), as written. Empty when unset.
     */
    public static Optional<String> credentialsStore() {
        return credentialsStore(JkDirs.userConfigFile());
    }

    /** As {@link #credentialsStore()} against an explicit config file. */
    public static Optional<String> credentialsStore(Path configFile) {
        return stringFromGlobal(configFile, "credentials", "store");
    }

    /** {@code [release] trusted-keys}: base64 RSA SPKI keys extending built-in release trust. */
    public static List<String> releaseTrustedKeys() {
        return stringFromGlobal(JkDirs.userConfigFile(), "release", "trusted-keys")
                .map(v -> Arrays.stream(v.split(","))
                        .map(String::trim)
                        .filter(k -> !k.isEmpty())
                        .toList())
                .orElse(List.of());
    }

    /** Read a single top-level string value, leniently, via TomlScan. */
    private static Optional<String> stringFromRoot(Path file, String key) {
        return stringFromGlobal(file, key);
    }

    /** Read a single string value from {@code [table].key} (or a bare top-level key), leniently. */
    private static Optional<String> stringFromGlobal(Path file, String table, String key) {
        return stringFromGlobal(file, table + "." + key);
    }

    /** Read a dotted or bare key via TomlScan, leniently. */
    private static Optional<String> stringFromGlobal(Path file, String dotted) {
        if (file == null) return Optional.empty();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(file);
        if (stamp == null) return Optional.empty(); // absent or unreadable — no value, nothing to memo
        String raw = SCAN_CACHE.get(
                file + "|" + dotted, stamp, () -> TomlScan.scan(file, dotted).get(dotted));
        return Optional.ofNullable(raw).map(String::trim).filter(s -> !s.isEmpty());
    }

    /** One entry per (file, dotted key). The staleness rule is {@link StampedMemo}'s. */
    private static final StampedMemo<String, StampedMemo.FileStamp, String> SCAN_CACHE = StampedMemo.create();

    /** One entry per path (the file is process-stable, and {@code nerd-font} hits this often). */
    private static final StampedMemo<String, StampedMemo.FileStamp, Parsed> CONFIG_CACHE = StampedMemo.create();

    /** One read of the file: its document, or why it has none. Both null when the file is absent. */
    private record Parsed(
            @Nullable TomlParseResult toml, @Nullable UserConfigParseException error) {
        static final Parsed ABSENT = new Parsed(null, null);
    }

    /**
     * The document of {@code file}, or empty when there is no file. A file that is there and does
     * not parse is never read as empty: that would drop its repositories, mirrors, proxy and
     * credentials, and send every request around them.
     *
     * <p>Memoized per version of the file, the error included, so a resident engine answers the
     * next request with the file as it is now: a fix needs no restart, and a break is reported
     * rather than served from the last good copy.
     *
     * @throws UserConfigParseException when the file exists and is not valid TOML
     */
    static Optional<TomlParseResult> parseConfig(@Nullable Path file) {
        if (file == null) return Optional.empty();
        Parsed parsed = parsed(file);
        if (parsed.error() != null) throw parsed.error();
        return Optional.ofNullable(parsed.toml());
    }

    /**
     * Why {@code ~/.jk/config.toml} cannot be read, or empty when it parses or is absent. For the
     * reports that must keep running on a broken file — {@code jk doctor}, the dashboard's
     * configuration panel — and say so.
     */
    public static Optional<UserConfigParseException> userConfigProblem() {
        return userConfigProblem(JkDirs.userConfigFile());
    }

    /** As {@link #userConfigProblem()} but against an explicit config file — for tests. */
    public static Optional<UserConfigParseException> userConfigProblem(@Nullable Path file) {
        return file == null
                ? Optional.empty()
                : Optional.ofNullable(parsed(file).error());
    }

    private static Parsed parsed(Path file) {
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(file);
        // No stamp is either "no file" (no config) or a stat failure on a file that is there, and
        // only the second one is worth an uncached read. An unsettled stamp may not tell this
        // version from the next, so it is read again too.
        if (stamp == null) return Files.exists(file) ? read(file) : Parsed.ABSENT;
        if (!stamp.settled()) return read(file);
        return CONFIG_CACHE.get(file.toAbsolutePath().toString(), stamp, () -> read(file));
    }

    private static Parsed read(Path file) {
        TomlParseResult toml;
        try {
            toml = Toml.parse(file);
        } catch (NoSuchFileException gone) {
            return Parsed.ABSENT;
        } catch (IOException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Parsed(null, new UserConfigParseException(file, 0, 0, "cannot be read: " + why));
        }
        if (!toml.hasErrors()) return new Parsed(toml, null);
        TomlParseError first = toml.errors().getFirst();
        TomlPosition at = first.position();
        String reason = first.getMessage() == null ? "not valid TOML" : first.getMessage();
        return new Parsed(null, new UserConfigParseException(file, at.line(), at.column(), reason));
    }

    /** Clear the memoized config parse. For tests that rewrite {@code ~/.jk/config.toml} in one JVM. */
    static void clearCache() {
        CONFIG_CACHE.clear();
        SCAN_CACHE.clear();
        NETWORK_CACHE.clear();
        resolvedNerdFont = null;
        lastAutoDetection = null;
    }

    /**
     * User-global {@code [image]} defaults from {@code ~/.jk/config.toml} — the layer under
     * a project's {@code [image]} table ({@link JkBuild#image()}). Lenient, like
     * everything else read from this file: a malformed config yields
     * {@link ImageTable#EMPTY} rather than failing a packaging run.
     */
    public static ImageTable image() {
        return image(JkDirs.userConfigFile());
    }

    /** As {@link #image()} but against an explicit config file — for tests. */
    static ImageTable image(Path configFile) {
        try {
            return parseConfig(configFile).map(ManifestImage::parse).orElse(ImageTable.EMPTY);
        } catch (UserConfigParseException unparsed) {
            throw unparsed;
        } catch (RuntimeException e) {
            return ImageTable.EMPTY;
        }
    }

    // Network

    /**
     * The {@code [network]} table of {@code ~/.jk/config.toml} — the proxy jk's HTTP goes through.
     * {@link NetworkConfig#EMPTY} when the file, the table or a usable value is absent: like every
     * other read of this file, a malformed preference never fails a build.
     *
     * <p>Answered once per request, and off a request once per version of the file. The proxy
     * selector asks on every HTTP request the engine makes, and a lock of a large reactor makes tens
     * of thousands, so a stat and a table read per ask would be a measurable share of the io threads'
     * time. Within one request the file is what it was when the request began; the next request
     * sees an edit.
     */
    public static NetworkConfig network() {
        return network(JkDirs.userConfigFile());
    }

    /** As {@link #network()} but against an explicit config file — for tests. */
    static NetworkConfig network(Path configFile) {
        return RequestScope.current().get(new NetworkKey(configFile), key -> networkOfFile(key.file()));
    }

    /** The request-scope key of one config file's {@code [network]} table. */
    private record NetworkKey(Path file) {}

    /** One entry per path, replaced when the file's size or mtime moves. */
    private static final StampedMemo<String, StampedMemo.FileStamp, NetworkConfig> NETWORK_CACHE = StampedMemo.create();

    private static NetworkConfig networkOfFile(Path configFile) {
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(configFile);
        if (stamp == null) return parseNetwork(configFile); // absent, or a stat failure worth an uncached read
        return NETWORK_CACHE.get(configFile.toAbsolutePath().toString(), stamp, () -> parseNetwork(configFile));
    }

    private static NetworkConfig parseNetwork(Path configFile) {
        try {
            return parseConfig(configFile)
                    .map(toml -> NetworkConfig.parse(toml.getTable("network")))
                    .orElse(NetworkConfig.EMPTY);
        } catch (UserConfigParseException unparsed) {
            throw unparsed;
        } catch (RuntimeException e) {
            return NetworkConfig.EMPTY;
        }
    }

    // Node sources

    /**
     * {@code [node]} of {@code ~/.jk/config.toml}: the npm registry and {@code [node.scopes]} (an
     * {@code @scope} to its registry), as written, {@code ${VAR}} references unexpanded. The Node
     * distribution's mirror is {@code [mirrors] node} ({@link #mirrors}).
     */
    public record NodeSources(@Nullable String registry, Map<String, String> scopes) {
        public static final NodeSources EMPTY = new NodeSources(null, Map.of());

        public NodeSources {
            scopes = Map.copyOf(scopes);
        }
    }

    public static NodeSources nodeSources() {
        return nodeSources(JkDirs.userConfigFile());
    }

    /** As {@link #nodeSources()} but against an explicit config file — for tests. */
    static NodeSources nodeSources(Path configFile) {
        try {
            return parseConfig(configFile)
                    .map(toml -> toml.getTable("node"))
                    .map(t -> new NodeSources(blankToNull(t.getString("registry")), nodeScopes(t.getTable("scopes"))))
                    .orElse(NodeSources.EMPTY);
        } catch (UserConfigParseException unparsed) {
            throw unparsed;
        } catch (RuntimeException e) {
            return NodeSources.EMPTY;
        }
    }

    /**
     * {@code [mirrors]} of {@code ~/.jk/config.toml}: a tool distribution's key ({@code node},
     * {@code kotlin}, {@code gradle}, {@code maven}) to the base URL it is downloaded from instead of
     * the public one, as written, {@code ${VAR}} references unexpanded. Empty when the table is absent.
     */
    public static Map<String, String> mirrors() {
        return mirrors(JkDirs.userConfigFile());
    }

    /** As {@link #mirrors()} but against an explicit config file — for tests. */
    static Map<String, String> mirrors(Path configFile) {
        try {
            return parseConfig(configFile)
                    .map(toml -> toml.getTable("mirrors"))
                    .map(t -> {
                        Map<String, String> out = new LinkedHashMap<>();
                        for (String key : t.keySet()) {
                            String url = blankToNull(t.getString(List.of(key)));
                            if (url != null) out.put(key, url);
                        }
                        return Map.copyOf(out);
                    })
                    .orElse(Map.of());
        } catch (UserConfigParseException unparsed) {
            throw unparsed;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static Map<String, String> nodeScopes(@Nullable TomlTable scopes) {
        if (scopes == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String scope : scopes.keySet()) {
            String url = blankToNull(scopes.getString(List.of(scope)));
            if (url != null) out.put(scope.startsWith("@") ? scope : "@" + scope, url);
        }
        return out;
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // Repositories

    /**
     * Repositories declared in the {@code [repositories]} table of {@code ~/.jk/config.toml}.
     * Returns an empty list when the file is absent, the table is missing, or any entry is
     * malformed (lenient — global config must never fail a build).
     */
    public static List<RepositorySpec> repositories() {
        return repositories(JkDirs.userConfigFile());
    }

    /** As {@link #repositories()} but against an explicit config file — for tests. */
    static List<RepositorySpec> repositories(Path configFile) {
        return parseConfig(configFile)
                .map(toml -> RepositoryToml.repositories(
                        toml.getTable("repositories"), RepositoryToml.VarPolicy.LENIENT, RepositoryToml.OnBad.SKIP))
                .orElse(List.of());
    }

    /**
     * The {@code [repositories.<id>]} tables of {@code ~/.jk/config.toml} that carry {@code
     * mirror-of}, in file order: the routes this machine's requests take, which no project can
     * declare. Strict where {@link #repositories()} is lenient — see {@link RepositoryToml#mirrors}.
     *
     * @throws JkBuildParseException when a mirror table is malformed
     */
    public static List<RepositoryMirror> repositoryMirrors() {
        return repositoryMirrors(JkDirs.userConfigFile());
    }

    /** As {@link #repositoryMirrors()} but against an explicit config file — for tests. */
    static List<RepositoryMirror> repositoryMirrors(Path configFile) {
        return parseConfig(configFile)
                .map(toml -> RepositoryToml.mirrors(toml.getTable("repositories"), configFile))
                .orElse(List.of());
    }

    /**
     * Every id {@code ~/.jk/config.toml} binds to a URL: its repositories, then its mirrors. What
     * the credential resolver treats as the user's own declaration — a mirror's {@code ${VAR}}
     * credential is the user's word for the mirror's URL exactly as a repository's is — and never
     * a repository group: a mirror is not a source.
     */
    public static List<RepositorySpec> repositoryDeclarations() {
        Path file = JkDirs.userConfigFile();
        List<RepositorySpec> out = new ArrayList<>(repositories(file));
        try {
            for (RepositoryMirror mirror : repositoryMirrors(file)) out.add(mirror.declaration());
        } catch (JkBuildParseException malformed) {
            // The resolve that uses the mirror fails on it, naming the table; a credential lookup is
            // not the place to say so.
        }
        return List.copyOf(out);
    }
}
