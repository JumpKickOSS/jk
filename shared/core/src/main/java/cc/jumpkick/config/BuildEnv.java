// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.ProcessEnv;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
    public static final List<String> DISPLAY = List.of("DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR");

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
            "TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED",
            "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX",
            "CONTAINER_HOST");

    /**
     * Every variable that rides each request by exact name to the workers it forks, the request's
     * value over the engine's: {@link #PROXY}, {@link #DISPLAY} and {@link #CONTAINER}.
     */
    public static final List<String> SHELL =
            Stream.of(PROXY, DISPLAY, CONTAINER).flatMap(List::stream).toList();

    /**
     * {@code names} as a set that matches a variable name the way the platform does: whatever its
     * case when {@code caseInsensitive} (Windows, where {@code My_Token} and {@code MY_TOKEN} are one
     * variable), exactly otherwise. Every comparison of a variable against declared or forwarded
     * names goes through one of these.
     */
    public static Set<String> nameSet(Collection<String> names, boolean caseInsensitive) {
        if (!caseInsensitive) return Set.copyOf(names);
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        out.addAll(names);
        return Collections.unmodifiableSet(out);
    }

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
     * {@link #SHELL} values and the {@code PATH} the request carried — the shell running {@code
     * jk}, and nothing of the engine's own — omitting names it did not. What every forked worker is
     * handed over the engine's inherited values, so a developer who moves networks and re-exports
     * {@code https_proxy} has the next build, and its compilers, plugins and test JVMs, go through
     * the new one, a suite opens the display of the terminal running {@code jk}, and a tool
     * installed into a new {@code PATH} entry is found by a guard suite as by a test JVM, without an
     * engine restart. Empty off a request.
     */
    public static Map<String, String> fromRequest() {
        Map<String, String> out = new LinkedHashMap<>(resolve(SHELL, clientEnv()::get));
        String path = clientEnv().get("PATH");
        if (path != null) out.put("PATH", path);
        return Collections.unmodifiableMap(out);
    }

    /**
     * The locale variables a test JVM gets, by name: {@code LANG}, {@code LANGUAGE} and every
     * {@code LC_*}. They change what a test computes (a date's format, a message's language), so
     * the suite is keyed on them, and they are the caller's: the session's forwarded values, then
     * the shell running {@code jk}, and the engine's own only for a name neither carries. What
     * {@code TestEnv} seeds every test JVM with and {@code SuiteEnvKeys} keys, from this one
     * resolution, so the key is what the JVM got. Sorted by name. The terminal is not among them:
     * a test JVM gets a fixed {@code TERM} ({@code TestEnv}), so a suite does not rerun because it
     * was started from another terminal.
     */
    public static Map<String, String> locale() {
        Map<String, String> client = clientEnv();
        RequestShell shell = RequestShell.current();
        Set<String> names = new TreeSet<>();
        for (String name : client.keySet()) if (localeName(name)) names.add(name);
        if (shell != null) for (String name : shell.vars().keySet()) if (localeName(name)) names.add(name);
        for (String name : ProcessEnv.all().keySet()) if (localeName(name)) names.add(name);
        Map<String, String> out = new TreeMap<>();
        for (String name : names) {
            String value = client.get(name);
            if (value == null && shell != null) value = shell.get(name);
            if (value == null) value = ProcessEnv.get(name);
            if (value != null) out.put(name, value);
        }
        return Collections.unmodifiableMap(out);
    }

    /** Whether {@code name} is one of {@link #locale()}'s. */
    static boolean localeName(String name) {
        return name.equals("LANG") || name.equals("LANGUAGE") || name.startsWith("LC_");
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
     * The caller's whole environment, or this process's own when the request carried none (MCP,
     * HTTP, a test). For a step that hands the caller's environment on: an uncached tail, a module
     * that says {@code [env] inherit}.
     */
    public static Map<String, String> callerEnv() {
        RequestShell shell = RequestShell.current();
        Map<String, String> all = shell != null ? shell.vars() : ProcessEnv.all();
        Map<String, String> out = new LinkedHashMap<>();
        all.forEach((name, value) -> {
            if (!perInvocation(name)) out.put(name, value);
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * The variables a shell changes from one command to the next: the last command, the working
     * directory, the nesting depth, the terminal session, Windows' per-drive directories. Handed on,
     * they would fork a fresh worker and rerun an inheriting suite on every build while meaning
     * nothing to either.
     */
    static boolean perInvocation(String name) {
        return name.isEmpty() || name.charAt(0) == '=' || PER_INVOCATION.contains(name);
    }

    private static final Set<String> PER_INVOCATION =
            Set.of("_", "PWD", "OLDPWD", "SHLVL", "TERM_SESSION_ID", "WT_SESSION", "WINDOWID");

    /**
     * {@link #callerEnv()} as a suite that says {@code [env] inherit} is keyed on: less what differs
     * between two terminals, two multiplexer panes or two logins on one machine — the session and
     * window ids, the window size, the SSH connection's ports and terminal. Handed on, a test may
     * still read them; keyed, they would rerun an inheriting suite on every new tab.
     */
    public static Map<String, String> inheritedFingerprintEnv() {
        Map<String, String> out = new TreeMap<>(callerEnv());
        out.putAll(fromRequest());
        out.keySet().removeIf(BuildEnv::sessionChurn);
        return Collections.unmodifiableMap(out);
    }

    /** True for a variable a terminal, multiplexer or remote login sets per session or window. */
    static boolean sessionChurn(String name) {
        if (SESSION_CHURN.contains(name)) return true;
        for (String prefix : SESSION_CHURN_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static final Set<String> SESSION_CHURN = Set.of(
            "COLUMNS",
            "LINES",
            "TERM",
            "COLORTERM",
            "NO_COLOR",
            "FORCE_COLOR",
            "CLICOLOR",
            "CLICOLOR_FORCE",
            "ITERM_SESSION_ID",
            "TMUX",
            "TMUX_PANE",
            "STY",
            "WINDOW",
            "KITTY_WINDOW_ID",
            "KITTY_PID",
            "ALACRITTY_WINDOW_ID",
            "WEZTERM_PANE",
            "SECURITYSESSIONID",
            "SSH_CLIENT",
            "SSH_CONNECTION",
            "SSH_TTY",
            "GPG_TTY",
            "TERM_PROGRAM_VERSION");

    private static final List<String> SESSION_CHURN_PREFIXES = List.of("VSCODE_GIT_", "Apple_PubSub_");

    /**
     * True when a variable a manifest declares reads as a credential by its name: one that says
     * token, secret, key, password or credential. A declared variable is filed with the redactor
     * on that evidence alone; nothing undeclared is guessed at.
     */
    public static boolean looksSecret(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        for (String word : SECRET_WORDS) {
            if (upper.contains(word)) return true;
        }
        return false;
    }

    private static final List<String> SECRET_WORDS =
            List.of("TOKEN", "SECRET", "KEY", "PASSWORD", "PASSWD", "PASSPHRASE", "CREDENTIAL");

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
     * The one redactor for work at {@code moduleDir}: the effective value of every name a {@code
     * .env} file declares there, plus every value filed for its workspace ({@link ResolvedSecrets}:
     * resolved repository credentials, credential-named declared variables). Every sink text leaves
     * the engine through (the wire, the journal, the test reports, the action cache) builds its
     * redactor here, so no sink masks less than another; the {@code redactor-owner} guard holds
     * the line. Build one per batch of lines, not per line: it parses {@code .env} (memoized by
     * stamp) and merges the filed values. A null {@code moduleDir} is the session's working
     * directory, for work that carries no directory of its own; off a session, nothing is masked.
     */
    public static SecretRedactor secretsFor(@Nullable Path moduleDir) {
        Path dir = moduleDir;
        if (dir == null) {
            try {
                dir = SessionContext.current().workingDir();
            } catch (RuntimeException noSession) {
                return SecretRedactor.none();
            }
        }
        if (dir == null) return SecretRedactor.none();
        return ResolvedSecrets.plus(dir, SecretRedactor.from(lookupFor(dir)));
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
