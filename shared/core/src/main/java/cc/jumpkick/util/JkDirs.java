// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves jk's on-disk tree under {@code JK_HOME} (default {@code $HOME/.jk}): config, cache,
 * state, data, bin, lib, jdks. Per-directory env overrides ({@code JK_CACHE_DIR}, …) win over
 * {@code JK_HOME}. Does not create directories; layout is identical on every OS (no XDG).
 */
public final class JkDirs {

    private static final String DEFAULT_HOME_SUFFIX = ".jk";

    private final Function<String, String> env;
    private final String userHome;

    private JkDirs(Function<String, String> env, String userHome) {
        this.env = Objects.requireNonNull(env, "env");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
    }

    /** Live resolver bound to {@link System#getenv} and {@code user.home}. */
    public static JkDirs current() {
        return new JkDirs(System::getenv, System.getProperty("user.home"));
    }

    /** Test seam: fully synthetic environment. */
    public static JkDirs of(Function<String, String> env, String userHome) {
        return new JkDirs(env, userHome);
    }

    /** Same as {@link #of(Function, String)}; {@code ignoredOs} is unused. */
    public static JkDirs of(Function<String, String> env, String userHome, String ignoredOs) {
        return new JkDirs(env, userHome);
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

    /** Pre-split location of the fetched set; see {@link #legacyStoreDir()}. */
    public static Path legacyStore() {
        return current().legacyStoreDir();
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

    /** {@code ~/.jk/versions} — side-by-side materialized jk versions. */
    public static Path versions() {
        return home().resolve("versions");
    }

    public static Path jdks() {
        return current().jdksDir();
    }

    /** The root of jk's on-disk tree. {@code JK_HOME} overrides; otherwise {@code $HOME/.jk}. */
    public Path homeDir() {
        String override = nonBlank(env.apply("JK_HOME"));
        if (override != null) return Path.of(override);
        return Path.of(userHome).resolve(DEFAULT_HOME_SUFFIX);
    }

    /**
     * Single-file user config at {@code ~/.jk/config.toml}. Overridable via {@code JK_CONFIG_FILE}.
     */
    public Path userConfigFilePath() {
        String override = nonBlank(env.apply("JK_CONFIG_FILE"));
        if (override != null) return Path.of(override);
        return homeDir().resolve("config.toml");
    }

    public Path cacheDir() {
        return resolve("JK_CACHE_DIR", "cache");
    }

    /**
     * Everything jk fetched from somewhere else: the CAS ({@code sha256/}), the per-repo views
     * ({@code repos/}), {@code maven-metadata.xml} copies, git clones, and the JDK catalog. Defaults
     * to {@code ~/.jk/store/}; override via {@code JK_STORE_DIR}.
     *
     * <h2>Why this is not under {@code cache/}</h2>
     *
     * Both are caches in the sense that both can be re-created, but they differ in what re-creating
     * them costs and who it affects. Rebuilding {@code actions/} costs local CPU. Rebuilding {@code
     * store/} means re-downloading from Maven Central — and Sonatype enforces a sticky per-IP quota,
     * so it costs a 429 that outlives the build (JK-1277).
     *
     * <p>That distinction matters because {@code JK_CACHE_DIR} is how jk's own tests isolate
     * themselves. Pointed at a fresh directory, every one of them re-fetched every artifact and every
     * metadata document, which is what was tripping the rate limit. With the fetched set living here
     * instead, an isolated run reuses the downloads and still gets a clean action cache.
     *
     * <p>Sharing the CAS across runs is safe by construction rather than by convention: a sha either
     * matches the requested content or it does not, so one run cannot corrupt another's view of a
     * blob. The mapping that genuinely needs isolating is the action cache — key to outputs — and that
     * stays under {@link #cacheDir()}.
     *
     * <p>{@code JK_HOME} still relocates this along with everything else, which is the way to get a
     * genuinely cold start.
     */
    public Path storeDir() {
        return resolve("JK_STORE_DIR", "store");
    }

    /**
     * The pre-split location of the fetched set: {@code ~/.jk/cache/}. Read-only fallback, so an
     * install that predates {@link #storeDir()} keeps its downloads instead of silently re-fetching
     * ~1.6 GB the first time it runs a new jk.
     */
    public Path legacyStoreDir() {
        return resolve("JK_CACHE_DIR", "cache");
    }

    public Path stateDir() {
        return resolve("JK_STATE_DIR", "state");
    }

    /**
     * Machine-scoped build state that must survive {@code jk clean}: the host calibration and the
     * persisted build-history journal. Defaults to {@code ~/.jk/state/builds/}; override via {@code
     * JK_BUILDS_DIR} (an absolute path, not relative to {@code state/}).
     */
    public Path buildsDir() {
        return resolve("JK_BUILDS_DIR", "state/builds");
    }

    /**
     * Scratch space for transient, regenerable artifacts (e.g. {@code jk import} reports). Defaults
     * to {@code ~/.jk/tmp/}; override via {@code JK_TMP_DIR}.
     */
    public Path tmpDir() {
        return resolve("JK_TMP_DIR", "tmp");
    }

    public Path dataDir() {
        return resolve("JK_DATA_DIR", "data");
    }

    /**
     * Where {@code jk tool install} writes launchers. Defaults to {@code ~/.jk/bin/} (cargo-style).
     * Override via {@code JK_BIN_DIR}. On a fresh install jk asks the user to add this directory to
     * {@code $PATH}.
     */
    public Path binDirectory() {
        return resolve("JK_BIN_DIR", "bin");
    }

    /**
     * Where jk keeps the jars its binaries need: the engine's {@code jk-engine-<version>.jar} and
     * the jar(s) {@code jk install} places for an application — the app jar and its hard-linked
     * runtime dependencies, or a single fat jar. Defaults to {@code ~/.jk/lib/}. Override via
     * {@code JK_LIB_DIR}. Launchers in {@link #binDirectory()} reference jars here by absolute
     * path.
     */
    public Path libDir() {
        return resolve("JK_LIB_DIR", "lib");
    }

    /**
     * Where {@code jk jdk install} extracts JDK tarballs. Defaults to {@code ~/.jk/jdks/} on every
     * platform. Override via {@code JK_JDKS_DIR}. JDKs installed elsewhere (IntelliJ's {@code
     * ~/.jdks} or {@code ~/Library/Java/JavaVirtualMachines}, SDKMAN, mise, system packages) are
     * still discovered by the probe chain; they're not stored here.
     */
    public Path jdksDir() {
        return resolve("JK_JDKS_DIR", "jdks");
    }

    /** Per-method env override wins; otherwise {@code $JK_HOME/<segment>}. */
    private Path resolve(String jkEnv, String segment) {
        String override = nonBlank(env.apply(jkEnv));
        if (override != null) return Path.of(override);
        return homeDir().resolve(segment);
    }

    private static String nonBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
