// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.model.EnvConfig;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The environment a forked worker starts with — the one owner behind every compiler, test JVM and
 * plugin-step fork.
 *
 * <p>A worker does not inherit the engine's environment. The engine is a daemon started by some
 * shell, and whatever that shell exported — a repository token, a cloud key — would otherwise reach
 * every compiler worker and, through the test JVM, the project's own test code. Instead a worker
 * gets the {@link #allowed allow-list} of the engine's variables, then the module's {@code [env]
 * vars}, then whatever the fork site adds (a sandboxed {@code JK_HOME}, a spec path). A module that
 * needs more says {@code [env] inherit = true}; docs/user/build.md spells out the list.
 */
public final class WorkerEnv {

    /**
     * Names inherited from the engine's environment without a manifest asking: where the machine is
     * and how it talks — the shell's search path and home, the JDK, the temp roots, locale and
     * terminal, and on Windows the system roots a process needs to run anything at all. Both
     * platforms' spellings, so the rule reads the same everywhere; {@link BuildEnv#MACHINE} is the
     * per-platform subset the client forwards on a request. {@code LC_*} is a prefix, matched in
     * {@link #allowed}. The proxy the machine talks through is part of how it talks:
     * {@link BuildEnv#PROXY} passes too, and {@link #environment()} lays the request's values over
     * the engine's, so a worker goes through the proxy of the shell running {@code jk}.
     */
    static final Set<String> MACHINE = Set.of(
            "PATH",
            "HOME",
            "USERPROFILE",
            "JAVA_HOME",
            "TMPDIR",
            "TMP",
            "TEMP",
            "LANG",
            "LANGUAGE",
            "TERM",
            "SystemRoot",
            "SystemDrive",
            "windir",
            "PATHEXT",
            "COMSPEC",
            "NUMBER_OF_PROCESSORS");

    /**
     * The {@code JK_*} variables a worker reads. Everything else in the {@code JK_} namespace stays
     * with the engine — including {@code JK_REPO_*_TOKEN}, which is a credential, and {@code JK_JDK},
     * which describes how the daemon was started rather than this build.
     */
    static final Set<String> JK = Set.of(
            // product roots — nested engines and suites resolve their layout from these
            "JK_HOME",
            "JK_STATE_DIR",
            "JK_STORE_DIR",
            "JK_CACHE_DIR",
            "JK_JDKS_DIR",
            "JK_M2_LOCAL",
            // terminal and output switches
            "JK_COLOR",
            "JK_NO_ANSI",
            "JK_FORCE_ANSI",
            "JK_NO_OSC",
            "JK_NO_PROGRESS",
            "JK_PROGRESS_MODE",
            "JK_QUIET",
            "JK_VERBOSE",
            "JK_NERD_FONT",
            "JK_NONINTERACTIVE",
            // worker tuning
            "JK_COMPILE_PHASES",
            "JK_FILE_OPS",
            "JK_WORKER_AOT",
            "JK_AOT_TRAIN",
            "JK_ANDROID_FEED_URL");

    /** Test seam: the environment {@link #environment()} filters instead of this process's. */
    private static final ScopedValue<Map<String, String>> ENGINE = ScopedValue.newInstance();

    private final boolean inherit;
    private final Map<String, String> extras;

    private WorkerEnv(boolean inherit, Map<String, String> extras) {
        this.inherit = inherit;
        this.extras = extras;
    }

    /** The allow-list alone — what a worker gets when no module has said otherwise. */
    public static WorkerEnv strict() {
        return new WorkerEnv(false, Map.of());
    }

    /** A module's inheritance policy alone — {@code [env] inherit} — with nothing laid on top yet. */
    public static WorkerEnv policy(EnvConfig config) {
        return new WorkerEnv(config.inherit(), Map.of());
    }

    /**
     * A module's policy with its {@code [env] vars} resolved through the build's environment for
     * {@code moduleDir}. {@code target} is what {@code ${target}} stands for; null when the worker
     * has no build output to speak of.
     */
    public static WorkerEnv forModule(EnvConfig config, Path moduleDir, @Nullable Path target) {
        return policy(config).with(declared(config, moduleDir, target));
    }

    /**
     * {@code [env] vars} resolved for a launch — the one expansion shared by every worker kind, so
     * the test JVM's seed and the compiler's cannot disagree about an unset {@code ${VAR}}.
     */
    public static Map<String, String> declared(EnvConfig config, Path moduleDir, @Nullable Path target) {
        if (config.vars().isEmpty()) return Map.of();
        Function<String, @Nullable String> env = BuildEnv.forModule(moduleDir);
        return TestEnvValues.resolve(
                "[env].vars", config.vars(), moduleDir, target, new TestEnvValues.Mode.Launch(env::apply));
    }

    /** This policy with {@code more} laid on top of what the fork already adds; later wins. */
    public WorkerEnv with(Map<String, String> more) {
        if (more.isEmpty()) return this;
        Map<String, String> merged = new LinkedHashMap<>(extras);
        merged.putAll(more);
        return new WorkerEnv(inherit, Collections.unmodifiableMap(merged));
    }

    /** Entries the fork supplies unless {@link #extras()} already carries them. */
    public WorkerEnv withDefaults(Map<String, String> defaults) {
        Map<String, String> merged = new LinkedHashMap<>(defaults);
        merged.putAll(extras);
        return new WorkerEnv(inherit, Collections.unmodifiableMap(merged));
    }

    /** True when the module opted into the engine's whole environment. */
    public boolean inherit() {
        return inherit;
    }

    /** What the fork adds beyond the inherited names, in layering order. */
    public Map<String, String> extras() {
        return extras;
    }

    /**
     * The child's complete environment: inherited names first, the request's proxy and display
     * variables over the engine's, then {@link #extras()}.
     */
    public Map<String, String> environment() {
        return compose(ENGINE.orElse(System.getenv()), BuildEnv.fromRequest(), inherit, extras, Os.isWindows());
    }

    /**
     * A token that differs when two workers would see different environments — for a pool that
     * shares one resident worker across the module's compiles.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(inherit ? "inherit" : "strict");
        for (Map.Entry<String, String> e : new TreeMap<>(extras).entrySet()) {
            sb.append('\n').append(e.getKey()).append('=').append(e.getValue());
        }
        return Hashing.sha256Hex(sb.toString()).substring(0, 16);
    }

    /**
     * The pure rule: {@code engine} whole when {@code inherit}, else only its {@link #allowed} names;
     * {@code request} — the proxy and display variables of the shell running {@code jk} — over
     * those, so the worker goes where the request goes and draws where it draws rather than where
     * the engine's spawning shell did; {@code extras} on top in their own order. Windows variable
     * names are case-insensitive, so there {@code Path} passes the {@code PATH} rule and lands under
     * its own spelling.
     */
    static Map<String, String> compose(
            Map<String, String> engine,
            Map<String, String> request,
            boolean inherit,
            Map<String, String> extras,
            boolean caseInsensitive) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : engine.entrySet()) {
            if (inherit || allowed(e.getKey(), caseInsensitive)) out.put(e.getKey(), e.getValue());
        }
        out.putAll(request);
        out.putAll(extras);
        return out;
    }

    /** Whether a variable of the engine's may reach a worker that has not asked for it. */
    static boolean allowed(String name, boolean caseInsensitive) {
        String key = caseInsensitive ? name.toUpperCase(Locale.ROOT) : name;
        if (key.startsWith("LC_")) return true;
        if (JK.contains(key)) return true;
        if (caseInsensitive ? SHELL_UPPER.contains(key) : SHELL.contains(key)) return true;
        return caseInsensitive ? MACHINE_UPPER.contains(key) : MACHINE.contains(key);
    }

    private static final Set<String> MACHINE_UPPER =
            MACHINE.stream().map(n -> n.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());

    /** The names that ride each request by exact spelling: the proxy and the display. */
    private static final Set<String> SHELL =
            Stream.concat(BuildEnv.PROXY.stream(), BuildEnv.DISPLAY.stream()).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> SHELL_UPPER =
            SHELL.stream().map(n -> n.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());

    /**
     * Test seam: run {@code body} with {@code engine} standing in for this process's environment, so
     * a test can plant a secret in "the engine" without exporting one.
     */
    public static <T> T withEngineEnvironment(Map<String, String> engine, Callable<T> body) throws Exception {
        Objects.requireNonNull(engine, "engine");
        return ScopedValue.where(ENGINE, engine).<T, Exception>call(body::call);
    }

    @Override
    public String toString() {
        return (inherit ? "inherit" : "strict") + extras.keySet();
    }
}
