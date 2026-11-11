// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.RequestEnvNames;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.m2.MavenSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The environment variables jk ships from the caller's shell without being asked.
 *
 * <p>Everything else a build depends on is declared: {@code env:NAME} in a plugin config, a name or
 * a value in {@code [test] env}. Declaration is what lets a variable enter an action key, and a
 * variable that cannot enter a key cannot safely decide what gets built. So this list is short by
 * construction, and every entry has to justify why no manifest could have declared it.
 *
 * <p>They all justify it the same way: they describe the machine, not the build. A module cannot
 * name the {@code PATH} it wants — that is the user's toolchain, and it differs per developer and
 * per CI runner. What a module can do is name a variable whose <em>value</em> it cares about, which
 * puts it in the key. Both routes exist; this one is for the shape of the world.
 *
 * <p>The names live on {@link BuildEnv#MACHINE} so the client forward list and the test-JVM seed
 * cannot disagree. Values ride the request's {@code clientEnv}; the engine merges them into every
 * forked test JVM (still not into any action key — see {@link BuildEnv#MACHINE}).
 *
 * <p><b>{@code JK_REPO_*} rides too, by prefix.</b> {@code JK_REPO_<ID>_TOKEN} and its
 * {@code _USERNAME}/{@code _PASSWORD}/{@code _HOST} siblings are the user's standing with a
 * repository, not the build's: a project cannot declare them (its {@code .env} is refused as a host
 * binding by design), they enter no action key, and no test JVM is seeded with them — only the
 * credential resolver reads them, and it files every value it resolves with the redactor. They have
 * to travel because the engine is a daemon: its own environment is whichever shell started it, so a
 * token exported in a later terminal was invisible to the resolve, and the refusal warning told the
 * user to set a variable that could not arrive.
 *
 * <p><b>The proxy variables ride too</b> — {@link BuildEnv#PROXY}, by exact name. The proxy is a
 * fact about the network the shell running {@code jk} is on, and a daemon that kept the one it
 * inherited would send every later terminal's requests to the last network's proxy until
 * {@code jk engine stop}. They stay off {@link BuildEnv#MACHINE} because that list's length is the
 * reproducibility budget; unlike the repository credentials they do reach every forked worker,
 * over the engine's own values, so a compiler, a plugin or a test JVM that downloads goes the same
 * way the engine does.
 *
 * <p><b>The display variables ride too</b> — {@link BuildEnv#DISPLAY}, by exact name. The display
 * is a fact about the terminal running {@code jk}: a JavaFX or Swing suite opens the one this shell
 * has, and {@code xvfb-run jk test} hands a headless host a virtual one, as it does for Surefire.
 * Like the proxy they reach every forked worker over the engine's own values and enter no key.
 *
 * <p><b>The container runtime's variables ride too</b> — {@link BuildEnv#CONTAINER}, by exact name:
 * {@code DOCKER_HOST} and the Testcontainers overrides a Podman or remote daemon needs. A
 * Testcontainers suite and {@code jk image} talk to the runtime this shell names, the same way.
 *
 * <p><b>The managed JDK root rides too</b> — {@link BuildEnv#JDK_ROOT}, by exact name. The client
 * pre-flights a missing JDK into the root of the shell running {@code jk}; the engine's own
 * ensure-jdk must then look in that same root, not in the one of the shell that started the
 * daemon, or it downloads the JDK a second time somewhere else.
 *
 * <p><b>A front-end build's variables ride too</b> — {@link BuildEnv#NODE} by name and
 * {@link BuildEnv#NODE_PREFIXES} by prefix. A Vite or Next build reads them from the shell that runs
 * it; they reach node steps alone, and enter those steps' keys by value.
 *
 * <p><b>Deliberately not in any action key.</b> Keying on {@code PATH} would mean a laptop and a CI
 * runner never share a cached result, and two terminals on one machine often would not either. The
 * cost of leaving it out is bounded and known: a suite whose outcome depends on a tool being on
 * {@code PATH} can replay a cached pass from a run where the tool was present. A suite that cares
 * should fail loudly when the tool is missing rather than skip — which is what
 * {@code WebClientJsTest} does — and one that genuinely needs the value keyed should name the
 * variable in {@code [test] env}, where it is hashed like any other declared reference.
 */
public final class ClientEnvForward {

    /** Every variable under this prefix is forwarded: repository credentials and host bindings. */
    public static final String REPO_PREFIX = "JK_REPO_";

    /** The {@code jk.env.<NAME>} property that overrides one variable — {@code JkDirs.env}'s seam. */
    private static final String SEAM = "jk.env.";

    private ClientEnvForward() {}

    /** The names forwarded by exact spelling on this platform — {@link BuildEnv#MACHINE}. */
    public static List<String> names() {
        return BuildEnv.MACHINE;
    }

    /**
     * Those of {@link #names()} the caller actually has, in listed order, then the
     * {@link BuildEnv#SHELL} variables it has (proxy, display, container runtime), the
     * {@link BuildEnv#JDK_ROOT} and {@link BuildEnv#NODE} it has, then every {@link #REPO_PREFIX} and
     * {@link BuildEnv#NODE_PREFIXES} variable.
     * {@code System::getenv} is passed explicitly — the client resolves from its own shell, never
     * from a session's {@code clientEnv}. The proxy, display, JDK-root and repository names also
     * honour the {@code jk.env.*} seam, so a test varies one of them per invocation.
     */
    public static Map<String, String> resolve() {
        Map<String, String> out = new LinkedHashMap<>(BuildEnv.machine(System::getenv));
        out.putAll(BuildEnv.resolve(BuildEnv.SHELL, System::getenv));
        out.putAll(BuildEnv.resolve(BuildEnv.JDK_ROOT, System::getenv));
        out.putAll(BuildEnv.resolve(BuildEnv.NODE, System::getenv));
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith(REPO_PREFIX) || nodePrefixed(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        for (String property : System.getProperties().stringPropertyNames()) {
            if (!property.startsWith(SEAM)) continue;
            String name = property.substring(SEAM.length());
            if (!byName(name)) continue;
            String value = System.getProperty(property);
            if (value != null) out.put(name, value);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * This shell's whole environment, with every {@code jk.env.*} override applied. Sent only when
     * something needs all of it: a module that says {@code [env] inherit = true}, a {@code ${cmd:…}}
     * credential helper in {@code ~/.jk/config.toml}, or an import that runs a foreign build.
     */
    public static Map<String, String> wholeShell() {
        Map<String, String> out = new LinkedHashMap<>(System.getenv());
        for (String property : System.getProperties().stringPropertyNames()) {
            if (!property.startsWith(SEAM)) continue;
            String value = System.getProperty(property);
            if (value != null) out.put(property.substring(SEAM.length()), value);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * What the engine is told of this shell on a request: the variables it reads to decide the
     * request itself ({@link RequestEnvNames}), the names {@code declared} (what the project's
     * manifests need, {@code ProjectInfo.envRefs}) and the names this user's own configuration
     * references ({@link #userRefs()}). A name the shell does not set is simply absent, which the
     * engine reads as unset.
     */
    public static Map<String, String> forwarded(Collection<String> declared) {
        Set<String> named = new HashSet<>(declared);
        named.addAll(userRefs().names());
        boolean windows = Os.isWindows();
        Map<String, String> out = new LinkedHashMap<>();
        wholeShell().forEach((name, value) -> {
            if (named.contains(name) || RequestEnvNames.engineReads(name, windows)) out.put(name, value);
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * The environment references in this user's own configuration, which the engine expands when
     * it reaches a repository or a mirror: {@code ${NAME}} and {@code ${env:NAME}} in {@code
     * ~/.jk/config.toml}, {@code ${env.NAME}} in {@code settings.xml}. A {@code ${cmd:…}} helper
     * runs with the caller's whole environment, so its presence asks for all of it. Read once per
     * command.
     */
    public static UserRefs userRefs() {
        return SessionContext.perInvocation(USER_REFS, ClientEnvForward::readUserRefs);
    }

    /** The names {@link #userRefs()} found, and whether a credential helper wants the whole shell. */
    public record UserRefs(Set<String> names, boolean wholeShell) {}

    private static final Object USER_REFS = new Object();

    private static final Pattern CONFIG_REF = Pattern.compile("\\$\\{(?:env:)?([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern SETTINGS_REF = Pattern.compile("\\$\\{env\\.([A-Za-z_][A-Za-z0-9_]*)}");

    private static UserRefs readUserRefs() {
        Set<String> names = new HashSet<>();
        String config = readQuietly(JkDirs.userConfigFile());
        Matcher m = CONFIG_REF.matcher(config);
        while (m.find()) names.add(m.group(1));
        for (Path settings : MavenSettings.settingsFiles(System::getenv)) {
            Matcher s = SETTINGS_REF.matcher(readQuietly(settings));
            while (s.find()) names.add(s.group(1));
        }
        return new UserRefs(Set.copyOf(names), config.contains("${cmd:"));
    }

    private static String readQuietly(@Nullable Path file) {
        if (file == null) return "";
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException | RuntimeException unreadable) {
            return ""; // the engine reports an unreadable file when it parses it
        }
    }

    /** Whether {@code name} is under a front-end framework's public prefix. */
    private static boolean nodePrefixed(String name) {
        for (String prefix : BuildEnv.NODE_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Whether {@code name} rides by exact spelling or by the repository prefix. */
    private static boolean byName(String name) {
        return name.startsWith(REPO_PREFIX)
                || nodePrefixed(name)
                || BuildEnv.NODE.contains(name)
                || BuildEnv.SHELL.contains(name)
                || BuildEnv.JDK_ROOT.contains(name);
    }

    /**
     * {@link #resolve()} beneath {@code declared}: what a command already resolved for its
     * declared references wins over the forward set for the same name.
     */
    public static Map<String, String> layerUnder(@Nullable Map<String, String> declared) {
        Map<String, String> out = new LinkedHashMap<>(resolve());
        if (declared != null) out.putAll(declared);
        return Collections.unmodifiableMap(out);
    }
}
