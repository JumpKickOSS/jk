// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Os;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
     * The environment variables that name a <em>toolchain</em>, and which therefore must not be read
     * on a build path with {@link System#getenv}.
     *
     * <p>The engine is a daemon, so a direct read answers from whichever shell started it — days
     * earlier, with a different JDK. Eight sites did that, and the symptom was not a missing override
     * but a build whose JDK depended on how the daemon had been launched, so {@code jk engine stop}
     * changed what got compiled.
     *
     * <p>Guard G38 reads this list and bans these spellings in {@code server/} and {@code shared/}
     * main sources, so a ninth site fails the build. {@code clients/} is exempt: there the process
     * really is the caller's shell.
     *
     * <p>They are not all handled the same way, and the list is the ban rather than a forward list:
     *
     * <ul>
     *   <li>{@code JK_JDK} — read once client-side and folded into the typed selection that rides
     *       the session envelope (alongside {@code JK_GRAAL}, which needs no ban because nothing on
     *       a build path reads it directly).
     *   <li>{@code GRAALVM_HOME} — a home path, not a spec, so it does not fit that selection. Its
     *       reads go through {@link #ambient}, which prefers the request and falls back to this
     * process; carrying it as a typed field is.
     *   <li>{@code JAVA_HOME} — banned, never forwarded. It names the machine's default JVM and
     *       feeds a late fallback tier, and forwarding it through {@link #MACHINE} would also inject
     *       it into every spawned test JVM through a channel no action key can see — the hole
     *       {@code ClientEnvForwardTest} exists to keep shut.
     * </ul>
     */
    public static final List<String> TOOLCHAIN = List.of("JK_JDK", "JAVA_HOME", "GRAALVM_HOME");

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
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : MACHINE) {
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
        Map<String, String> clientEnv = clientEnv();
        Function<String, @Nullable String> real = name -> {
            String fromClient = clientEnv.get(name);
            return fromClient != null ? fromClient : System.getenv(name);
        };
        return EnvLookup.forModule(moduleDir, real).asFunction();
    }

    /**
     * The environment for work with no module context — the request's shell, then the engine's own.
     *
     * <p>Toolchain resolution needs this: "which JDK did the caller ask for" is answered before any
     * module directory is in hand, and {@code .env} has no say in it (a project cannot put its own
     * {@code JK_JDK} in a file and expect the machine to obey). What it must not do is fall through
     * to {@link System#getenv} alone, which is the daemon's environment and therefore whichever
     * shell started the engine.
     *
     * <p>Prefer {@link #forModule} whenever a directory is available; this is the narrower answer,
     * not the convenient one.
     */
    public static Function<String, @Nullable String> ambient() {
        Map<String, String> clientEnv = clientEnv();
        return name -> {
            String fromClient = clientEnv.get(name);
            return fromClient != null ? fromClient : System.getenv(name);
        };
    }

    /** The full lookup, for callers that also need {@link EnvLookup#isFromFile} to redact secrets. */
    public static EnvLookup lookupFor(Path moduleDir) {
        Map<String, String> clientEnv = clientEnv();
        return EnvLookup.forModule(moduleDir, name -> {
            String fromClient = clientEnv.get(name);
            return fromClient != null ? fromClient : System.getenv(name);
        });
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
