// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Os;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Resolves JumpKick's on-disk layout. Everything jk owns lives under one home directory,
 * {@code $HOME/.jk}, on every platform.
 *
 * <pre>
 * $HOME/.jk/
 *   config.toml   global user config
 *   bin/          PATH launchers: jk, jkx, tool shims
 *   cache/        action cache, CAS, hash memos, per-project scratch
 *   config/       per-app config, &lt;bin&gt;/config.toml
 *   creds/        forge tokens (creds/forge) and per-repo credentials (creds/repo)
 *   lib/          live engine jar, installed fat/minified app jars
 *   state/        engine sockets, builds, JDK inventory, tmp
 *   store/        repos, tools, templates, completions, jdks.json, libs.global.toml
 * </pre>
 *
 * <p>All root overrides must be absolute; relative values are rejected rather than resolved
 * against the caller's working directory. {@code JK_HOME} moves the whole tree. Three roots also take an individual override —
 * {@code JK_STORE_DIR}, {@code JK_CACHE_DIR}, {@code JK_STATE_DIR} — because they are the large
 * ones and putting them on another filesystem is a legitimate ask; each wins over {@code JK_HOME}.
 * Managed JDKs are the one thing deliberately outside the home tree: they share IntelliJ's root so
 * the IDE and jk see the same runtimes ({@link #jdksDir()}, {@code JK_JDKS_DIR}).
 *
 * <p>The settings come in two layers: the {@code jk.env.<NAME>} system properties — the
 * <em>overlay</em>, the in-process seam a test sets and the engine spawner forwards — over the
 * real environment. A root override binds to the home it was set beside. A home named by the
 * overlay therefore takes its store, cache and state from the overlay alone: an environment
 * {@code JK_STORE_DIR} describes the shell's home, and letting it reach into an overlay home would
 * point a test's {@code jk self nuke --store} at the developer's store. A home named by the
 * environment keeps the environment's overrides, so {@code JK_HOME=/scratch JK_STORE_DIR=~/.jk/store}
 * still shares one store.
 *
 * <p>The home and {@code state} are owner-only ({@link #secureRoots()}): the engine socket under
 * {@code state/engine} is trusted on directory permissions alone, so any process running as the
 * user can drive the engine and nobody else can reach it. {@code store} and {@code cache} hold
 * nothing secret and are left to the umask.
 *
 * <p>The roots are also the <strong>deletion units</strong>. {@code jk self nuke --store} cannot
 * remove a credential because {@code creds} is a sibling of {@code store} rather than a child, and
 * {@code jk clean} cannot remove build history because {@code builds} lives under {@code state}.
 * Where a directory sits is the guard; nothing has to remember to exclude it.
 */
public final class JkDirs {

    /** Invalid ambient root override supplied before command dispatch. */
    public static final class InvalidOverrideException extends IllegalArgumentException {
        public InvalidOverrideException(String message) {
            super(message);
        }
    }

    /** The home directory name under {@code $HOME}. */
    public static final String HOME_DIR = ".jk";

    /** Downloaded library registry basename under {@link #storeDir()}. */
    public static final String LIBRARY_REGISTRY_FILE = "libs.global.toml";

    /** Cloned Giter8 catalog directory under {@link #storeDir()}. */
    public static final String TEMPLATES_DIR = "templates";

    /** Provisioned build-tool distribution directory under {@link #storeDir()}. */
    public static final String TOOLS_DIR = "tools";

    /** The {@code jk.env.<NAME>} property that overrides one variable. */
    private static final String OVERLAY = "jk.env.";

    private static final Function<String, @Nullable String> NO_OVERLAY = name -> null;

    private final Function<String, @Nullable String> overlay;
    private final Function<String, @Nullable String> env;
    private final String userHome;
    private final String osName;

    private JkDirs(
            Function<String, @Nullable String> overlay,
            Function<String, @Nullable String> env,
            String userHome,
            String osName) {
        this.overlay = Objects.requireNonNull(overlay, "overlay");
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
        return new JkDirs(JkDirs::overlay, System::getenv, System.getProperty("user.home"), Os.name());
    }

    private static @Nullable String overlay(String name) {
        return System.getProperty(OVERLAY + name);
    }

    /**
     * One environment variable, with that override applied. Anything resolving ambient jk settings
     * — not just the layout — should read through here, so a test can vary one setting for one
     * test without the JVM-wide value every other test depends on.
     */
    public static String env(String name) {
        String prop = overlay(name);
        return prop != null ? prop : System.getenv(name);
    }

    /** Test seam: fully synthetic environment (host OS name), with no overlay over it. */
    public static JkDirs of(Function<String, @Nullable String> env, String userHome) {
        return new JkDirs(NO_OVERLAY, env, userHome, Os.name());
    }

    /**
     * Test seam: synthetic environment + OS name. The layout is identical on every OS; the name
     * only reaches {@link #jdksDir()}.
     */
    public static JkDirs of(Function<String, @Nullable String> env, String userHome, String osName) {
        return new JkDirs(NO_OVERLAY, env, userHome, osName);
    }

    /**
     * Test seam: both layers synthetic — {@code overlay} stands for the {@code jk.env.*} properties,
     * {@code env} for the environment beneath them.
     */
    public static JkDirs of(
            Function<String, @Nullable String> overlay, Function<String, @Nullable String> env, String userHome) {
        return new JkDirs(overlay, env, userHome, Os.name());
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

    /** Downloaded library registry: {@link #libraryRegistryFile()}. */
    public static Path libraryRegistry() {
        return current().libraryRegistryFile();
    }

    /** Cloned Giter8 catalogs: {@link #templatesDir()}. */
    public static Path templates() {
        return current().templatesDir();
    }

    /** Provisioned build-tool distributions: {@link #toolsDir()}. */
    public static Path tools() {
        return current().toolsDir();
    }

    public static Path state() {
        return current().stateDir();
    }

    public static Path builds() {
        return current().buildsDir();
    }

    /** Forge tokens and per-repo credentials: {@link #credsDir()}. */
    public static Path creds() {
        return current().credsDir();
    }

    public static Path binDir() {
        return current().binDirectory();
    }

    public static Path toolEnvs() {
        return current().toolEnvsDir();
    }

    public static Path tmp() {
        return current().tmpDir();
    }

    /** Product library for the live engine jar and installed fat/minified app jars. */
    public static Path productLib() {
        return current().productLibDir();
    }

    public static Path jdks() {
        return current().jdksDir();
    }

    /**
     * The one root: {@code $JK_HOME} when set, else {@code $HOME/.jk}. Every other directory here
     * hangs off it, so relocating this relocates the product.
     */
    public Path homeDir() {
        String override = nonBlank(overlay.apply("JK_HOME"));
        if (override == null) override = nonBlank(env.apply("JK_HOME"));
        if (override != null) return absoluteOverride("JK_HOME", override);
        return Path.of(userHome).resolve(HOME_DIR);
    }

    /** True when the home comes from the overlay, so the environment's root overrides describe another home. */
    private boolean overlayHome() {
        return nonBlank(overlay.apply("JK_HOME")) != null;
    }

    /**
     * Create the home and {@code state} roots {@code rwx------}, tightening pre-existing looser
     * ones. Best-effort: a root that cannot be created fails with a real error at the first write,
     * and {@code jk doctor} reports a mode that would not tighten.
     */
    public void secureRoots() {
        for (Path root : new Path[] {homeDir(), stateDir()}) {
            try {
                OwnerOnlyFiles.directory(root);
            } catch (IOException ignored) {
                // surfaces at the first write into the root
            }
        }
    }

    /** User config file: {@code <home>/config.toml}. */
    public Path userConfigFilePath() {
        return homeDir().resolve(ManifestPaths.CONFIG);
    }

    /**
     * Per-app config root: {@code <home>/config}, holding one {@code <bin>/config.toml} per
     * installed app. The global {@code config.toml} is not in here — it sits at the home root
     * ({@link #userConfigFilePath()}), so this directory has exactly one kind of child.
     */
    public Path configDir() {
        return homeDir().resolve("config");
    }

    /**
     * Forge tokens ({@code creds/forge}) and per-repo credentials ({@code creds/repo}).
     *
     * <p>A root rather than a corner of {@link #storeDir()}, because roots are what nukes delete.
     * Everything in the store is re-fetchable; a token is not, and {@code jk repo logout} is the
     * only thing that should ever remove one. As a sibling it is out of reach of every nuke target
     * by construction. Permissions are {@link OwnerOnlyFiles}' business, wherever the files live.
     */
    public Path credsDir() {
        return homeDir().resolve("creds");
    }

    /** Rebuildable action-cache tier. Override with {@code JK_CACHE_DIR}. */
    public Path cacheDir() {
        return root("JK_CACHE_DIR", "cache");
    }

    /**
     * Downloaded artifacts: Maven-layout jars under {@code repos/} with {@code .jk} memos, plugin
     * short classpaths under {@code lib/&lt;id&gt;/}, {@code maven-metadata.xml} copies, git clones,
     * the JDK catalog ({@code jdks.json}), the library registry ({@link #libraryRegistryFile()}),
     * shell completions, and cloned Giter8 catalogs ({@link #templatesDir()}). Override with
     * {@code JK_STORE_DIR}.
     *
     * <h4>Why this is not under {@code cache/}</h4>
     *
     * Both are caches in the sense that both can be re-created, but they differ in what re-creating
     * them costs and who it affects. Rebuilding action cache costs local CPU. Rebuilding store means
     * re-downloading from Maven Central — and Sonatype enforces a sticky per-IP quota.
     *
     * <p>{@code JK_CACHE_DIR} isolates the action cache for tests without forcing a cold CAS.
     */
    public Path storeDir() {
        return root("JK_STORE_DIR", "store");
    }

    /**
     * Downloaded short-name library registry. Always {@code <store>}/{@value
     * #LIBRARY_REGISTRY_FILE} — the only on-disk copy.
     */
    public Path libraryRegistryFile() {
        return storeDir().resolve(LIBRARY_REGISTRY_FILE);
    }

    /**
     * Cloned Giter8 template catalogs (official + {@code [templates.sources]}). Always {@code
     * <store>}/{@value #TEMPLATES_DIR} — the only on-disk copy. One subdirectory per source cache
     * key; user drop-ins may use the {@code <lang>/<framework>/*.g8} layout at this root.
     */
    public Path templatesDir() {
        return storeDir().resolve(TEMPLATES_DIR);
    }

    /**
     * Provisioned build-tool distributions — Kotlin, Maven, Gradle, and the jars the Groovy
     * build-logic host forks against. Always {@code <store>}/{@value #TOOLS_DIR}.
     *
     * <p>The <strong>store</strong>, not the cache, and the difference is not cosmetic. The cache
     * holds rebuildable bytes and {@code CacheRetention} enforces that by deleting every top-level
     * entry {@code CacheTree} does not name — a total table this directory was never in. An
     * 83 MB Kotlin distribution therefore became residue an hour after it landed, and could be
     * deleted while a build was using it. A fetched distribution is an artifact, and artifacts live
     * beside {@code repos}, {@code templates} and the managed JDKs.
     */
    public Path toolsDir() {
        return storeDir().resolve(TOOLS_DIR);
    }

    /** Engine sockets, build history, JDK inventory, scratch. Override with
     * {@code JK_STATE_DIR}. */
    public Path stateDir() {
        return root("JK_STATE_DIR", "state");
    }

    /**
     * Machine-scoped build state that must survive {@code jk clean}: host calibration and the
     * persisted build-history journal. Always {@code <state>/builds}.
     */
    public Path buildsDir() {
        return stateDir().resolve("builds");
    }

    /**
     * Scratch space for transient, regenerable artifacts (e.g. {@code jk import} reports). Always
     * {@code <state>/tmp}.
     */
    public Path tmpDir() {
        return stateDir().resolve("tmp");
    }

    /**
     * Installed tool environments — one {@code <name>/env.json} per {@code jk install}ed tool,
     * paired with the launcher {@link #binDirectory()} carries for it. Always
     * {@code <state>/tools/envs}: an env records absolute CAS classpaths, so it is state that a
     * store wipe invalidates, not an artifact.
     */
    public Path toolEnvsDir() {
        return toolEnvsDir(stateDir());
    }

    /** {@link #toolEnvsDir()} beneath an explicit state root — the {@code --state-dir} seams. */
    public static Path toolEnvsDir(Path stateDir) {
        return stateDir.resolve("tools").resolve("envs");
    }

    /**
     * Where {@code jk} / {@code jkx} and {@code jk tool install} launchers live: {@code <home>/bin},
     * which shell activation puts on {@code PATH}. jk owns this directory outright, so a name in it
     * is jk's to replace.
     */
    public Path binDirectory() {
        return homeDir().resolve("bin");
    }

    /**
     * Live engine and installed fat/minified app jars: {@code <home>/lib} ({@code jk-engine/<jar>}
     * / {@code <bin>/…}). jk hosts exactly one engine, so this is a single live tree with no
     * per-version subdirectories.
     */
    public Path productLibDir() {
        return homeDir().resolve("lib");
    }

    /**
     * Where {@code jk jdk install} extracts JDK tarballs. Default is the <strong>IntelliJ shared
     * root</strong> (Linux/Windows {@code ~/.jdks}, macOS {@code ~/Library/Java/JavaVirtualMachines}),
     * not under the jk home — so IDE and JumpKick share managed runtimes. This is the only path here
     * that differs by platform, and the only one {@code JK_HOME} does not move; hermetic tests must
     * set {@code JK_JDKS_DIR}.
     */
    public Path jdksDir() {
        String override = nonBlank(overlay.apply("JK_JDKS_DIR"));
        if (override == null) override = nonBlank(env.apply("JK_JDKS_DIR"));
        if (override != null) return absoluteOverride("JK_JDKS_DIR", override);
        Path home = Path.of(userHome);
        if (Os.isDarwin(osName)) {
            return home.resolve("Library").resolve("Java").resolve("JavaVirtualMachines");
        }
        return home.resolve(".jdks");
    }

    /**
     * A root with its own override: the {@code JK_*_DIR} when set, else {@code <home>/<name>}. The
     * override is read from the layer the home came from — the overlay's alone under an overlay
     * home, else the overlay's over the environment's.
     */
    private Path root(String jkEnv, String name) {
        String override = nonBlank(overlay.apply(jkEnv));
        if (override == null && !overlayHome()) override = nonBlank(env.apply(jkEnv));
        if (override != null) return absoluteOverride(jkEnv, override);
        return homeDir().resolve(name);
    }

    private static Path absoluteOverride(String name, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new InvalidOverrideException(name + " must be an absolute path: " + value);
        }
        return path.normalize();
    }

    private static @Nullable String nonBlank(@Nullable String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
