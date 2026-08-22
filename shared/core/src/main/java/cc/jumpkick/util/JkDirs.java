// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves JumpKick's on-disk layout.
 *
 * <p><strong>Resolution order</strong> (per role):
 *
 * <ol>
 *   <li>Role-specific env ({@code JK_CACHE_DIR}, {@code JK_STORE_DIR}, …) wins.
 *   <li>Else if {@code JK_HOME} is set → single-tree umbrella {@code $JK_HOME/<segment>}
 *       (hermetic tests / CI cold roots).
 *   <li>Else → platform defaults (XDG on Linux/macOS; Windows Known Folders).
 * </ol>
 *
 * <p><strong>Platform defaults (no {@code JK_HOME})</strong>
 *
 * <table>
 *   <caption>Layout by role</caption>
 *   <tr><th>Role</th><th>Linux / macOS</th><th>Windows</th></tr>
 *   <tr><td>bin</td><td>{@code $XDG_BIN_HOME} → {@code $XDG_DATA_HOME/../bin} →
 *       {@code ~/.local/bin}</td><td>{@code %USERPROFILE%\.local\bin}</td></tr>
 *   <tr><td>data</td><td>{@code $XDG_DATA_HOME/jk} → {@code ~/.local/share/jk}</td>
 *       <td>{@code %LOCALAPPDATA%\jk\data}</td></tr>
 *   <tr><td>cache</td><td>{@code $XDG_CACHE_HOME/jk} → {@code ~/.cache/jk}</td>
 *       <td>{@code %LOCALAPPDATA%\jk\cache}</td></tr>
 *   <tr><td>state</td><td>{@code $XDG_STATE_HOME/jk} → {@code ~/.local/state/jk}</td>
 *       <td>{@code %LOCALAPPDATA%\jk\state}</td></tr>
 *   <tr><td>config</td><td>{@code $JK_HOME/config} when set; else
 *       {@code $XDG_CONFIG_HOME/jk} → {@code ~/.config/jk}
 *       (global {@code config.toml} + per-app {@code <bin>/config.toml})</td>
 *       <td>{@code %APPDATA%\jk}</td></tr>
 *   <tr><td>store / product lib</td><td>under <em>data</em> (or {@code $JK_HOME})</td>
 *       <td>under <em>data</em> (or {@code $JK_HOME})</td></tr>
 *   <tr><td>jdks (write root)</td><td>IntelliJ shared root — not under product data
 *       (see {@link #jdksDir()})</td><td>same</td></tr>
 * </table>
 *
 * <p>{@code JK_HOME} does <strong>not</strong> relocate the default JDK write root; set
 * {@code JK_JDKS_DIR} for hermetic JDK isolation.
 */
public final class JkDirs {

    private final Function<String, String> env;
    private final String userHome;
    private final String osName;

    private JkDirs(Function<String, String> env, String userHome, String osName) {
        this.env = Objects.requireNonNull(env, "env");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
        this.osName = osName != null ? osName : "";
    }

    /**
     * Live resolver bound to {@link System#getenv} and system properties. A {@code jk.env.<NAME>}
     * system property wins over the real environment variable — the in-process test seam for
     * per-test layout isolation (env vars are fixed at JVM start; properties are not). The engine
     * spawner forwards {@code jk.env.*} properties to child engines so a spawned engine sees the
     * same layout as the client that asked for it.
     */
    public static JkDirs current() {
        return new JkDirs(
                name -> {
                    String prop = System.getProperty("jk.env." + name);
                    return prop != null ? prop : System.getenv(name);
                },
                System.getProperty("user.home"),
                System.getProperty("os.name", ""));
    }

    /** Test seam: fully synthetic environment (host OS name). */
    public static JkDirs of(Function<String, String> env, String userHome) {
        return new JkDirs(env, userHome, System.getProperty("os.name", ""));
    }

    /** Test seam: synthetic environment + OS name (linux / mac / windows path shapes). */
    public static JkDirs of(Function<String, String> env, String userHome, String osName) {
        return new JkDirs(env, userHome, osName);
    }

    public static Path home() {
        return current().homeDir();
    }

    public static Path userConfigFile() {
        return current().userConfigFilePath();
    }

    public static Path cache() {
        return current().cacheDir();
    }

    /** The fetched-artifact store; see {@link #storeDir()}. */
    public static Path store() {
        return current().storeDir();
    }

    public static Path state() {
        return current().stateDir();
    }

    public static Path builds() {
        return current().buildsDir();
    }

    public static Path data() {
        return current().dataDir();
    }

    public static Path binDir() {
        return current().binDirectory();
    }

    public static Path tmp() {
        return current().tmpDir();
    }

    public static Path lib() {
        return current().libDir();
    }

    /**
     * Product library for the live engine jar and installed fat/minified app jars:
     * {@code $JK_HOME/lib} when set, otherwise {@code <data>/lib}. Distinct from {@link #lib()}
     * ({@code store/lib}, installed tools).
     */
    public static Path productLib() {
        return current().productLibDir();
    }

    /**
     * Leftover side-by-side tree ({@code …/versions/<v>/}) from pre-product-lib installs. New
     * installs do not write here and nothing sweeps it any more (the legacy {@code gc} overload that
     * did was deprecated) — it lingers until removed by hand. Retained only so old paths resolve.
     */
    public static Path versions() {
        return current().versionsDir();
    }

    public static Path jdks() {
        return current().jdksDir();
    }

    /**
     * Logical product home: {@code JK_HOME} when set; otherwise the platform <em>data</em>
     * root (credentials, libs catalog, and other files that historically lived next to a
     * single tree). Prefer role-specific methods ({@link #cacheDir()}, {@link #stateDir()}, …)
     * over resolving under home.
     */
    public Path homeDir() {
        String override = nonBlank(env.apply("JK_HOME"));
        if (override != null) return Path.of(override);
        return dataDir();
    }

    /**
     * User config file. Override via {@code JK_CONFIG_FILE}. Otherwise
     * {@link #configDir()}{@code /config.toml} — under {@code JK_HOME} that is
     * {@code $JK_HOME/config/config.toml}.
     */
    public Path userConfigFilePath() {
        String override = nonBlank(env.apply("JK_CONFIG_FILE"));
        if (override != null) return Path.of(override);
        Path current = configDir().resolve("config.toml");
        // Back-compat: under JK_HOME the config moved from $JK_HOME/config.toml to
        // $JK_HOME/config/config.toml. Keep honoring an existing legacy file so an upgrade doesn't
        // silently drop the user's engine JDK pin / heap caps / trusted keys (JK-2324).
        String jkHome = jkHomeOrNull();
        if (jkHome != null && !Files.isRegularFile(current)) {
            Path legacy = Path.of(jkHome).resolve("config.toml");
            if (Files.isRegularFile(legacy)) return legacy;
        }
        return current;
    }

    /**
     * Config root: global {@code config.toml} and per-app {@code <bin>/config.toml}. Override via
     * {@code JK_CONFIG_DIR}. Under {@code JK_HOME}: {@code $JK_HOME/config}. Otherwise the platform
     * config dir ({@code ~/.config/jk}, {@code %APPDATA%\\jk}, …).
     */
    public Path configDir() {
        String override = nonBlank(env.apply("JK_CONFIG_DIR"));
        if (override != null) return Path.of(override);
        if (jkHomeOrNull() != null) return Path.of(jkHomeOrNull()).resolve("config");
        return platformConfigDir();
    }

    public Path cacheDir() {
        return resolve("JK_CACHE_DIR", "cache", this::platformCacheDir);
    }

    /**
     * Downloaded artifacts: Maven-layout jars under {@code repos/} with {@code .jk} memos, plugin
     * short classpaths under {@code lib/&lt;id&gt;/}, {@code maven-metadata.xml} copies, git clones,
     * the JDK catalog ({@code jdks.json}), and the library registry ({@code libs.global.toml}).
     * Engine/client install blobs may still sit under {@code sha256/}. Defaults to {@code
     * <data>/store} (or {@code $JK_HOME/store}); override via {@code JK_STORE_DIR}.
     *
     * <h2>Why this is not under {@code cache/}</h2>
     *
     * Both are caches in the sense that both can be re-created, but they differ in what re-creating
     * them costs and who it affects. Rebuilding action cache costs local CPU. Rebuilding store means
     * re-downloading from Maven Central — and Sonatype enforces a sticky per-IP quota. Platform
     * cache cleaners may wipe XDG/Local cache dirs; store lives under <em>data</em> for that reason.
     *
     * <p>{@code JK_CACHE_DIR} isolates the action cache for tests without forcing a cold CAS.
     */
    public Path storeDir() {
        return resolve("JK_STORE_DIR", "store", () -> dataDir().resolve("store"));
    }

    public Path stateDir() {
        return resolve("JK_STATE_DIR", "state", this::platformStateDir);
    }

    /**
     * Machine-scoped build state that must survive {@code jk clean}: host calibration and the
     * persisted build-history journal. Defaults to {@code <state>/builds}; override via
     * {@code JK_BUILDS_DIR}.
     */
    public Path buildsDir() {
        String override = nonBlank(env.apply("JK_BUILDS_DIR"));
        if (override != null) return Path.of(override);
        if (jkHomeOrNull() != null)
            return Path.of(jkHomeOrNull()).resolve("state").resolve("builds");
        return stateDir().resolve("builds");
    }

    /**
     * Scratch space for transient, regenerable artifacts (e.g. {@code jk import} reports). Defaults
     * to {@code <state>/tmp} or {@code $JK_HOME/tmp}; override via {@code JK_TMP_DIR}.
     */
    public Path tmpDir() {
        return resolve("JK_TMP_DIR", "tmp", () -> stateDir().resolve("tmp"));
    }

    public Path dataDir() {
        return resolve("JK_DATA_DIR", "data", this::platformDataDir);
    }

    /**
     * Where {@code jk} / {@code jkx} and {@code jk tool install} launchers live on PATH.
     * Platform default is the user executable directory ({@code ~/.local/bin} hierarchy).
     * Override via {@code JK_BIN_DIR}.
     */
    public Path binDirectory() {
        return resolve("JK_BIN_DIR", "bin", this::platformBinDir);
    }

    /**
     * Shared jar library for installed tools: {@code <store>/lib/} by default. Override via
     * {@code JK_LIB_DIR}.
     */
    public Path libDir() {
        String override = nonBlank(env.apply("JK_LIB_DIR"));
        if (override != null) return Path.of(override);
        return storeDir().resolve("lib");
    }

    /**
     * Live engine and installed fat/minified app jars: {@code $JK_HOME/lib} when set, otherwise
     * {@code <data>/lib} ({@code jk-engine/<jar>} / {@code <bin>/…}). Not {@link #libDir()}.
     */
    public Path productLibDir() {
        return homeDir().resolve("lib");
    }

    /** Leftover {@code versions/} tree under {@code $JK_HOME} or {@code <data>}. */
    public Path versionsDir() {
        if (jkHomeOrNull() != null) return Path.of(jkHomeOrNull()).resolve("versions");
        return dataDir().resolve("versions");
    }

    /**
     * Where {@code jk jdk install} extracts JDK tarballs. Default is the <strong>IntelliJ shared
     * root</strong> (Linux/Windows {@code ~/.jdks}, macOS {@code ~/Library/Java/JavaVirtualMachines}),
     * not under product data — so IDE and JumpKick share managed runtimes.
     *
     * <p>Override via {@code JK_JDKS_DIR}. {@code JK_HOME} does <strong>not</strong> relocate this
     * default; hermetic tests must set {@code JK_JDKS_DIR}.
     */
    public Path jdksDir() {
        String override = nonBlank(env.apply("JK_JDKS_DIR"));
        if (override != null) return Path.of(override);
        return platformJdksDir();
    }

    // ---- resolution helpers -------------------------------------------------

    private Path resolve(String jkEnv, String segment, Supplier<Path> platformDefault) {
        String override = nonBlank(env.apply(jkEnv));
        if (override != null) return Path.of(override);
        String jkHome = jkHomeOrNull();
        if (jkHome != null) return Path.of(jkHome).resolve(segment);
        return platformDefault.get();
    }

    private String jkHomeOrNull() {
        return nonBlank(env.apply("JK_HOME"));
    }

    private Path userHomePath() {
        return Path.of(userHome);
    }

    private boolean isWindows() {
        String lower = osName.toLowerCase(Locale.ROOT);
        return lower.contains("win");
    }

    private boolean isMac() {
        String lower = osName.toLowerCase(Locale.ROOT);
        return lower.contains("mac") || lower.contains("darwin");
    }

    private Path platformDataDir() {
        if (isWindows()) {
            return localAppData().resolve("jk").resolve("data");
        }
        return xdgDir("XDG_DATA_HOME", ".local/share").resolve("jk");
    }

    private Path platformCacheDir() {
        if (isWindows()) {
            return localAppData().resolve("jk").resolve("cache");
        }
        return xdgDir("XDG_CACHE_HOME", ".cache").resolve("jk");
    }

    private Path platformStateDir() {
        if (isWindows()) {
            return localAppData().resolve("jk").resolve("state");
        }
        return xdgDir("XDG_STATE_HOME", ".local/state").resolve("jk");
    }

    private Path platformConfigDir() {
        if (isWindows()) {
            return roamingAppData().resolve("jk");
        }
        return xdgDir("XDG_CONFIG_HOME", ".config").resolve("jk");
    }

    private Path platformBinDir() {
        if (isWindows()) {
            return userHomePath().resolve(".local").resolve("bin");
        }
        String binHome = nonBlank(env.apply("XDG_BIN_HOME"));
        if (binHome != null) return Path.of(binHome);
        String dataHome = nonBlank(env.apply("XDG_DATA_HOME"));
        if (dataHome != null) {
            Path parent = Path.of(dataHome).getParent();
            if (parent != null) return parent.resolve("bin");
        }
        return userHomePath().resolve(".local").resolve("bin");
    }

    /**
     * IntelliJ-compatible managed JDK root (same as {@code IntellijProbe.defaultRoot}).
     */
    private Path platformJdksDir() {
        if (isMac()) {
            return userHomePath().resolve("Library").resolve("Java").resolve("JavaVirtualMachines");
        }
        return userHomePath().resolve(".jdks");
    }

    private Path xdgDir(String xdgEnv, String homeRelativeDefault) {
        String xdg = nonBlank(env.apply(xdgEnv));
        if (xdg != null) return Path.of(xdg);
        Path home = userHomePath();
        for (String part : homeRelativeDefault.split("/")) {
            home = home.resolve(part);
        }
        return home;
    }

    private Path localAppData() {
        String local = nonBlank(env.apply("LOCALAPPDATA"));
        if (local != null) return Path.of(local);
        return userHomePath().resolve("AppData").resolve("Local");
    }

    private Path roamingAppData() {
        String roaming = nonBlank(env.apply("APPDATA"));
        if (roaming != null) return Path.of(roaming);
        return userHomePath().resolve("AppData").resolve("Roaming");
    }

    private static String nonBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
