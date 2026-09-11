// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlParseResult;

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
                JkDirs.userConfigFile(), System.getenv("JK_NERD_FONT"), System.getenv("NERD_FONT"), colorEnabled());
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
        return ansiSuppressed(SessionContext.current().config(), System::getenv);
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
        return colorEnabled(SessionContext.current().config(), System::getenv);
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
        return nerdFont(configFile, System.getenv("JK_NERD_FONT"), System.getenv("NERD_FONT"), colorEnabled());
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
        return mode == NerdFontMode.AUTO ? NerdFontDetect.detect().caps() : mode.fixedCaps();
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
        return engineJdkPin(JkDirs.userConfigFile(), System.getenv("JK_ENGINE_JDK"));
    }

    /** As {@link #engineJdkPin()} but against an explicit config file + env value — for tests. */
    static Optional<String> engineJdkPin(Path file, @Nullable String envValue) {
        if (envValue != null && !envValue.isBlank()) return Optional.of(envValue.trim());
        return stringFromGlobal(file, "toolchain", "jdk");
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
    private static final StampedMemo<String, StampedMemo.FileStamp, TomlParseResult> CONFIG_CACHE =
            StampedMemo.create();

    private static Optional<TomlParseResult> parseConfig(Path file) {
        if (file == null) return Optional.empty();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(file);
        if (stamp == null) {
            // No stamp is either "no file" (no config) or a stat failure on a file that is there,
            // and only the second one is worth an uncached read.
            return Files.exists(file) ? TomlValues.parse(file) : Optional.empty();
        }
        return Optional.ofNullable(
                CONFIG_CACHE.get(file.toAbsolutePath().toString(), stamp, () -> TomlValues.parse(file)
                        .orElse(null)));
    }

    /** Clear the memoized config parse. For tests that rewrite {@code ~/.jk/config.toml} in one JVM. */
    static void clearCache() {
        CONFIG_CACHE.clear();
        SCAN_CACHE.clear();
        resolvedNerdFont = null;
    }

    /**
     * User-global {@code [image]} defaults from {@code ~/.jk/config.toml} — the layer under
     * a project's {@code [image]} table ({@link JkBuildParser#imageConfig(Path)}). Lenient, like
     * everything else read from this file: a malformed config yields
     * {@link ManifestImage.ImageConfigData#EMPTY} rather than failing a packaging run.
     */
    public static ManifestImage.ImageConfigData image() {
        return image(JkDirs.userConfigFile());
    }

    /** As {@link #image()} but against an explicit config file — for tests. */
    static ManifestImage.ImageConfigData image(Path configFile) {
        try {
            return parseConfig(configFile).map(ManifestImage::parse).orElse(ManifestImage.ImageConfigData.EMPTY);
        } catch (RuntimeException e) {
            return ManifestImage.ImageConfigData.EMPTY;
        }
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
}
