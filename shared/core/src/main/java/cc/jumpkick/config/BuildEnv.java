// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.ProcessEnv;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The one way a build-path caller obtains its environment.
 *
 * <p>Resolution, lowest wins to highest:
 *
 * <ol>
 * <li>{@code .env} at the workspace root, then the module — see {@link EnvLookup}
 * <li>the request's {@code clientEnv}, i.e. the shell that ran {@code jk}
 * <li>the engine's own environment, as a last resort
 * </ol>
 *
 * <p>The request's environment is already on the ambient {@link Session} and every call site
 * already knows its module directory, so a helper keyed on the directory is enough.
 * {@code System.getenv} inside the engine answers from whichever shell started the daemon, not
 * from {@code FOO=x jk build}.
 */
public final class BuildEnv {

    /**
     * Where the machine is and how it talks — shipped from the caller's shell into every forked
     * test JVM without a manifest asking, and deliberately <em>not</em> hashed into any action key.
     *
     * <p>A module cannot name the {@code PATH} it wants; that is the user's toolchain and differs
     * per developer and CI runner. Keying on it would mean a laptop and a CI runner never share a
     * cached result. The cost of leaving it out of the key is bounded: a suite whose outcome depends
     * on a tool being on {@code PATH} must fail loudly when the tool is missing rather than skip.
     * A suite that genuinely needs the value keyed declares the name in {@code [test] env}.
     *
     * <p>Owned here so the client forward list and the test-JVM seed cannot disagree. Platform
     * spellings differ; both lists stay short on purpose — every entry reaches a test JVM through a
     * channel no action key can.
     */
    public static final List<String> MACHINE = Os.isWindows()
            ? List.of(
                    "PATH",
                    "USERPROFILE",
                    "SystemRoot",
                    "SystemDrive",
                    "windir",
                    "PATHEXT",
                    "COMSPEC",
                    "NUMBER_OF_PROCESSORS")
            : List.of("PATH", "HOME", "LANG", "LC_ALL");

    /**
     * The six variables that name the proxy this machine's HTTP goes through, in both spellings —
     * how the machine talks, like {@link #MACHINE}, and read the same way: the request's shell
     * first, the engine's own environment second. Not on {@link #MACHINE}, whose length is a
     * reproducibility budget; these enter no action key either, but they change where a request
     * goes rather than what a build computes, and a suite that fetches from the network is not
     * hermetic with or without them.
     *
     * <p>Owned here so the four readers cannot disagree: the client forwards them on every request
     * ({@code ClientEnvForward}), the engine inherits them from its spawning shell as the fallback
     * ({@code EngineEnvironment}), every forked worker is handed the request's values over the
     * engine's ({@code WorkerEnv}), and {@code ProxyEnvironment} decides each request from them.
     * {@link #DISPLAY} travels the first three the same way.
     */
    public static final List<String> PROXY =
            List.of("http_proxy", "https_proxy", "no_proxy", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY");

    /**
     * The variables that name the display this machine draws on — the X or Wayland server and the
     * authority file a toolkit opens it with. Where the machine
     * draws, like {@link #PROXY} is how it talks, and read the same way: the request's shell first,
     * the engine's own environment second, so a JavaFX or Swing suite opens the display of the
     * terminal that ran {@code jk test}, and {@code xvfb-run jk test} gives a headless host one, as
     * it does for Surefire. Off {@link #MACHINE} for the same reason as the proxy: these enter no
     * action key. jk sets no {@code java.awt.headless}; Surefire does not either, and a JavaFX
     * toolkit does not read it.
     */
    public static final List<String> DISPLAY = List.of("DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY");

    /**
     * The variables that name the container runtime this machine talks to — Docker's client
     * settings and the Testcontainers overrides a Podman, Colima or remote daemon needs. Where the
     * machine runs containers, like {@link #DISPLAY} is where it draws, and read the same way: the
     * request's shell first, the engine's own environment second, so a Testcontainers suite and
     * {@code jk image} reach the runtime the terminal running {@code jk} names. They enter no action
     * key; a suite that starts containers is not hermetic with or without them.
     */
    public static final List<String> CONTAINER = List.of(
            "DOCKER_HOST",
            "DOCKER_CONTEXT",
            "DOCKER_CONFIG",
            "DOCKER_CERT_PATH",
            "DOCKER_TLS_VERIFY",
            "TESTCONTAINERS_HOST_OVERRIDE",
            "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
            "TESTCONTAINERS_RYUK_DISABLED",
            "TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED");

    /**
     * Every variable that rides each request by exact name to the workers it forks, the request's
     * value over the engine's: {@link #PROXY}, {@link #DISPLAY} and {@link #CONTAINER}.
     */
    public static final List<String> SHELL =
            Stream.of(PROXY, DISPLAY, CONTAINER).flatMap(List::stream).toList();

    /**
     * The one variable that says where this machine keeps the JDKs jk manages ({@code JK_JDKS_DIR}).
     * Forwarded by exact name like {@link #PROXY}, and for the same reason: the engine is a daemon,
     * so left to its own environment it installs and resolves under whichever root the shell that
     * started it had — while the client that pre-flighted the download used the root of the shell
     * running {@code jk}. Off {@link #MACHINE}: it enters no action key and seeds no test JVM (a
     * forked worker is handed the engine's own value by {@code WorkerEnv}).
     */
    public static final List<String> JDK_ROOT = List.of("JK_JDKS_DIR");

    /**
     * What a front-end build reads from the shell that runs it: {@code NODE_ENV}, and every variable
     * under a framework's public prefix ({@code VITE_API_URL}). Forwarded like {@link #PROXY}, by
     * name and prefix, because the engine is a daemon; they reach a node step's process and enter
     * its key by value, and reach no other worker.
     */
    public static final List<String> NODE = List.of("NODE_ENV");

    /** The public prefixes of the frameworks a node build infers; see {@link #NODE}. */
    public static final List<String> NODE_PREFIXES = List.of("VITE_", "NEXT_PUBLIC_", "NUXT_PUBLIC_", "PUBLIC_");

    private BuildEnv() {}

    /**
     * {@link #MACHINE} values from {@link #ambient()} — the request's shell first, falling back to
     * this process's environment for names the request did not carry (a caller that unset a machine
     * variable therefore sees the daemon's value seeded, not an absence). Seed of every forked test
     * JVM's environment — overlays the daemon's inherited {@code PATH} with the shell that ran
     * {@code jk}.
     */
    public static Map<String, String> machine() {
        return machine(ambient());
    }

    /**
     * {@link #MACHINE} values resolved through {@code env}, in listed order, omitting names it does
     * not have. The one resolution loop behind both the client forward set ({@code
     * ClientEnvForward.resolve()}, which passes {@code System::getenv}) and the engine's test-JVM
     * seed ({@link #machine()}), so the two cannot drift.
     */
    public static Map<String, String> machine(Function<String, @Nullable String> env) {
        return resolve(MACHINE, env);
    }

    /**
     * {@link #SHELL} values the request carried — the shell running {@code jk}, and nothing of the
     * engine's own — omitting names it did not. What a forked worker is handed
     * over the engine's inherited values, so a developer who moves networks and re-exports {@code
     * https_proxy} has the next build, and its compilers, plugins and test JVMs, go through the new
     * one, and a suite opens the display of the terminal running {@code jk}. Empty off a request.
     */
    public static Map<String, String> fromRequest() {
        return Collections.unmodifiableMap(resolve(SHELL, clientEnv()::get));
    }

    /** {@code names} resolved through {@code env}, in listed order, omitting those it does not have. */
    public static Map<String, String> resolve(List<String> names, Function<String, @Nullable String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : names) {
            String value = env.apply(name);
            if (value != null) out.put(name, value);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * The environment for work rooted at {@code moduleDir}.
     *
     * <p>Safe off a request (a unit test, a CLI-side helper): with no session installed the client
     * layer is simply empty and the engine's own environment answers.
     */
    public static Function<String, @Nullable String> forModule(Path moduleDir) {
        return EnvLookup.forModule(moduleDir, over(clientEnv())).asFunction();
    }

    /**
     * The environment for work with no module context — the request's shell, then the engine's own.
     *
     * <p>Toolchain resolution needs this: "which JDK did the caller ask for" is answered before any
     * module directory is in hand, and {@code .env} has no say in it (a project cannot put its own
     * {@code JK_JDK} in a file and expect the machine to obey). What it must not do is fall through
     * to {@link ProcessEnv#get} alone, which is the daemon's environment and therefore whichever
     * shell started the engine.
     *
     * <p>Prefer {@link #forModule} whenever a directory is available; this is the narrower answer,
     * not the convenient one.
     */
    public static Function<String, @Nullable String> ambient() {
        return over(clientEnv());
    }

    /**
     * {@code name} as the request being served sees it: the session's forwarded values, then the
     * caller's whole shell ({@link RequestShell}), where an unset name stays unset. Only a request
     * that carried no shell (MCP, HTTP, a test) falls back to this process's own environment.
     * Resolved on every call, so {@code BuildEnv::get} is safe to hold in a long-lived object.
     */
    public static @Nullable String get(String name) {
        return over(clientEnv()).apply(name);
    }

    /**
     * {@code clientEnv} over the request's shell when one is bound, else over this process's own
     * environment. The shell is captured now, like {@code clientEnv}.
     */
    public static Function<String, @Nullable String> over(@Nullable Map<String, String> clientEnv) {
        RequestShell shell = RequestShell.current();
        Function<String, @Nullable String> below = shell != null ? shell::get : ProcessEnv::get;
        if (clientEnv == null || clientEnv.isEmpty()) return below;
        return name -> {
            String fromClient = clientEnv.get(name);
            return fromClient != null ? fromClient : below.apply(name);
        };
    }

    /** The full lookup, for callers that also need {@link EnvLookup#isFromFile} to redact secrets. */
    public static EnvLookup lookupFor(Path moduleDir) {
        return EnvLookup.forModule(moduleDir, over(clientEnv()));
    }

    /**
     * Redactor for {@code .env}-sourced values at {@code moduleDir}. Use before any
     * free-form text (wire events, journal, errors) leaves the process, and before a value enters
     * a cache key.
     */
    public static SecretRedactor secretsFor(Path moduleDir) {
        if (moduleDir == null) return SecretRedactor.none();
        return SecretRedactor.from(lookupFor(moduleDir));
    }

    /** The request's {@link #NODE} variables and those under {@code prefixes}; empty outside a request. */
    public static Map<String, String> nodeFromRequest(List<String> prefixes) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : clientEnv().entrySet()) {
            String name = e.getKey();
            boolean wanted = NODE.contains(name);
            for (String prefix : prefixes) wanted |= name.startsWith(prefix);
            if (wanted) out.put(name, e.getValue());
        }
        return out;
    }

    private static Map<String, String> clientEnv() {
        try {
            Session session = SessionContext.current();
            Map<String, String> env = session == null ? null : session.clientEnv();
            return env == null ? Map.of() : env;
        } catch (RuntimeException e) {
            return Map.of(); // no session installed — not a request
        }
    }
}
