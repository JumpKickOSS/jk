// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The environment a spawned engine starts with: an allow-list of the spawning shell's, never the
 * whole of it.
 *
 * <p>A resident engine outlives the shell that started it and serves every later terminal, so
 * whatever that first shell happened to export would become the daemon's truth for days: {@code
 * JAVA_TOOL_OPTIONS} or {@code _JAVA_OPTIONS} silently altering the engine JVM (and defeating its
 * AOT cache, whose recorded flags must match), a cloud key or repository token reaching a process
 * no manifest asked to trust with it, a proxy that was right for one network. The engine gets the
 * variables it reads — the {@code JK_*} namespace, and the ones that say where the machine is and
 * how it talks — and nothing else. Its workers are narrowed again by {@code WorkerEnv}.
 */
final class EngineEnvironment {

    private EngineEnvironment() {}

    /**
     * Names inherited by exact spelling. Each is read by the engine or by something it launches
     * without a manifest naming it: the search path, home and user; the temp roots, time zone,
     * locale and terminal; the host JDK and GraalVM fallbacks; the SSH agent the git backend authenticates
     * through; the SDK and JDK discovery roots; the display preferences shared config reads; and
     * on Windows the system roots a process needs to run anything at all. Both platforms'
     * spellings, so the rule reads the same everywhere. {@code LC_*} and {@code JK_*} are prefixes,
     * matched in {@link #inherited}. No per-user application-data variable is carried: nothing the
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
            "PROCESSOR_ARCHITECTURE");

    private static final Set<String> MACHINE_UPPER =
            MACHINE.stream().map(n -> n.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());

    /**
     * Replace {@code env} (a {@link ProcessBuilder#environment()}, pre-filled with this process's
     * whole environment) with the inherited subset of {@code shell}.
     */
    static void seed(Map<String, String> env, Map<String, String> shell) {
        env.clear();
        boolean caseInsensitive = Os.isWindows();
        for (Map.Entry<String, String> e : shell.entrySet()) {
            if (inherited(e.getKey(), caseInsensitive)) env.put(e.getKey(), e.getValue());
        }
    }

    /**
     * Whether a variable of the spawning shell's reaches the engine. Windows variable names are
     * case-insensitive, so there {@code Path} passes the {@code PATH} rule and lands under its own
     * spelling.
     */
    static boolean inherited(String name, boolean caseInsensitive) {
        String key = caseInsensitive ? name.toUpperCase(Locale.ROOT) : name;
        if (key.startsWith("JK_") || key.startsWith("LC_")) return true;
        return caseInsensitive ? MACHINE_UPPER.contains(key) : MACHINE.contains(key);
    }
}
