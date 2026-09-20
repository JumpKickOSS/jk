// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.PluginTunings;
import cc.jumpkick.host.Os;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The environment a spawned engine starts with: an allow-list of the spawning shell's, never the
 * whole of it.
 *
 * <p>A resident engine outlives the shell that started it and serves every later terminal, so
 * whatever that first shell happened to export would become the daemon's truth for days: {@code
 * JAVA_TOOL_OPTIONS} or {@code _JAVA_OPTIONS} silently altering the engine JVM, a cloud key or repository token reaching a process
 * no manifest asked to trust with it. The engine gets the variables it reads — the {@code JK_*}
 * namespace, and the ones that say where the machine is and how it talks — and nothing else. Its
 * workers are narrowed again by {@code WorkerEnv}.
 */
final class EngineEnvironment {

    private EngineEnvironment() {}

    /**
     * Names inherited by exact spelling. Each is read by the engine or by something it launches
     * without a manifest naming it: the search path, home and user; the temp roots, time zone,
     * locale and terminal; the host JDK and GraalVM fallbacks; the SSH agent the git backend authenticates
     * through; the SDK and JDK discovery roots; the display preferences shared config reads; and on
     * Windows the system roots a process needs to run anything at all. Both platforms' spellings,
     * so the rule reads the same everywhere. {@code LC_*} and {@code JK_*} are prefixes, the
     * proxy variables are {@link BuildEnv#PROXY} and the display's {@link BuildEnv#DISPLAY}, all
     * matched in {@link #inherited}, less the
     * {@link #PER_REQUEST} names that travel on each request. No per-user application-data
     * variable is carried: nothing the
     * engine runs needs one to start, and what reads one to discover another program's layout
     * falls back to that program's default location.
     */
    static final Set<String> MACHINE = Set.of(
            "PATH",
            "HOME",
            "USER",
            "LOGNAME",
            "SHELL",
            "TERM",
            "TMPDIR",
            "TMP",
            "TEMP",
            "TZ",
            "LANG",
            "LANGUAGE",
            "JAVA_HOME",
            "GRAALVM_HOME",
            "SSH_AUTH_SOCK",
            "ANDROID_HOME",
            "ANDROID_SDK_ROOT",
            "MISE_DATA_DIR",
            "NO_COLOR",
            "NERD_FONT",
            // Windows
            "SystemRoot",
            "SystemDrive",
            "windir",
            "ComSpec",
            "PATHEXT",
            "USERPROFILE",
            "USERNAME",
            "HOMEDRIVE",
            "HOMEPATH",
            "ProgramData",
            "ProgramFiles",
            "ProgramFiles(x86)",
            "NUMBER_OF_PROCESSORS",
            "PROCESSOR_ARCHITECTURE",
            // The Visual Studio developer prompt's compiler variables: native-image compiles C
            // with MSVC and, for a Visual Studio it cannot locate itself, reads these.
            "INCLUDE",
            "LIB",
            "LIBPATH");

    private static final Set<String> MACHINE_UPPER =
            MACHINE.stream().map(n -> n.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());

    /**
     * {@code JK_*} names that ride each request instead: the shell's spellings of {@code --jvm-arg}
     * and {@code --ram-percent}, which the client folds into the request's tuning. Seeding them here
     * would make the first shell's worker-JVM flags the daemon's truth for every later terminal, and
     * the engine reads worker tuning from the request only, so they have no reader on that side.
     */
    static final Set<String> PER_REQUEST = Set.of(
            PluginTunings.ENV_ARGS, PluginTunings.ENV_MAX_RAM, PluginTunings.ENV_GC, PluginTunings.ENV_STRING_DEDUP);

    /**
     * Replace {@code env} (a {@link ProcessBuilder#environment()}, pre-filled with this process's
     * whole environment) with the inherited subset of {@code shell}.
     */
    /**
     * glibc's per-thread malloc arenas, capped for the engine. Each arena keeps what its threads
     * freed; a coordinator with a hundred threads and the default cap (eight per core) held over a
     * gigabyte of freed native memory across dozens of arenas. Four keeps allocation off one lock
     * while bounding the retention. A shell that sets its own value wins; other allocators ignore
     * the variable.
     */
    static final String MALLOC_ARENA_MAX = "MALLOC_ARENA_MAX";

    static final String DEFAULT_MALLOC_ARENA_MAX = "4";

    static void seed(Map<String, String> env, Map<String, String> shell) {
        env.clear();
        boolean caseInsensitive = Os.isWindows();
        for (Map.Entry<String, String> e : shell.entrySet()) {
            if (inherited(e.getKey(), caseInsensitive)) env.put(e.getKey(), e.getValue());
        }
        env.putIfAbsent(MALLOC_ARENA_MAX, DEFAULT_MALLOC_ARENA_MAX);
    }

    /**
     * Whether a variable of the spawning shell's reaches the engine. Windows variable names are
     * case-insensitive, so there {@code Path} passes the {@code PATH} rule and lands under its own
     * spelling.
     */
    static boolean inherited(String name, boolean caseInsensitive) {
        String key = caseInsensitive ? name.toUpperCase(Locale.ROOT) : name;
        if (PER_REQUEST.contains(key)) return false;
        if (key.startsWith("JK_") || key.startsWith("LC_") || key.equals(MALLOC_ARENA_MAX)) return true;
        if (caseInsensitive ? SHELL_UPPER.contains(key) : SHELL.contains(key)) return true;
        return caseInsensitive ? MACHINE_UPPER.contains(key) : MACHINE.contains(key);
    }

    /**
     * The proxy and display variables ({@link BuildEnv#PROXY}, {@link BuildEnv#DISPLAY}) ride each
     * request too, and the request's values win; the engine inherits them so a request that
     * carries none — a shell that unset them — still has the spawning shell's to fall back on.
     */
    private static final Set<String> SHELL =
            Stream.concat(BuildEnv.PROXY.stream(), BuildEnv.DISPLAY.stream()).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> SHELL_UPPER =
            SHELL.stream().map(n -> n.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
}
