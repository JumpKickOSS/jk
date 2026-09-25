// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * Linux prefix that starts the engine alone in a delegated systemd user scope.
 *
 * <p>{@code systemd-run --scope} execs the command in place, so the pid {@link ProcessBuilder}
 * returns is the engine's and {@code setsid} still detaches it. A container and {@code
 * JK_ENGINE_EXE} take this path only when {@code systemd-run} is on {@code PATH} and a user
 * manager is reachable; {@code JK_ENGINE_SCOPE=0} skips it. The note is what a plain spawn tells
 * the engine to repeat on a score-only containment line.
 */
final class EngineScope {

    /** {@code 0} disables the scope. Any other value, including unset, leaves the checks in charge. */
    static final String ENV = "JK_ENGINE_SCOPE";

    /**
     * Set on a plain spawn when the scope was not used. The engine reads the same name; it is not a
     * user setting.
     */
    static final String NOTE = "JK_SCOPE_REASON";

    private EngineScope() {}

    /** What one spawn should do: a prefix (empty when not scoping) and the note for a plain spawn. */
    record Decision(List<String> prefix, String note) {
        boolean scoped() {
            return !prefix.isEmpty();
        }

        static Decision plain(String note) {
            return new Decision(List.of(), note == null ? "" : note);
        }

        static Decision scoped(List<String> prefix) {
            return new Decision(List.copyOf(prefix), "");
        }
    }

    /** The decision for this process: Linux, {@link #ENV}, {@code systemd-run}, and the user bus. */
    static Decision current() {
        if (!Os.isLinux()) return Decision.plain("");
        Map<String, String> env = System.getenv();
        return decide(true, env, systemdRun(env.get("PATH")), userManagerReachable(env));
    }

    /**
     * {@code systemdRun} is the executable to prefix, or null when it is not on {@code PATH}.
     * {@code userManager} is true when {@code $XDG_RUNTIME_DIR/bus} exists or {@code
     * DBUS_SESSION_BUS_ADDRESS} is set.
     */
    static Decision decide(boolean linux, Map<String, String> env, @Nullable Path systemdRun, boolean userManager) {
        if (!linux) return Decision.plain("");
        if (disabled(env)) return Decision.plain("JK_ENGINE_SCOPE=0");
        if (systemdRun == null) return Decision.plain("systemd-run is not on PATH");
        if (!userManager) return Decision.plain("systemd user manager is not reachable");
        return Decision.scoped(prefix(systemdRun, unitId()));
    }

    /** True when {@link #ENV} is off in the jk truth set ({@code 0}, {@code false}, {@code no}, {@code off}). */
    static boolean disabled(Map<String, String> env) {
        return !EnvValues.bool(env::get, ENV).orElse(true);
    }

    /** First executable {@code systemd-run} on {@code pathEnv}, or null. Blank entries are skipped. */
    static @Nullable Path systemdRun(@Nullable String pathEnv) {
        for (String entry : SearchPath.entries(pathEnv)) {
            Path dir = SearchPath.path(entry);
            if (dir == null) continue;
            Path candidate = dir.resolve("systemd-run");
            if (PathUtil.isRunnable(candidate)) return candidate;
        }
        return null;
    }

    /** True when the user manager's bus socket exists or a session bus address is set. */
    static boolean userManagerReachable(Map<String, String> env) {
        String xdg = env.get("XDG_RUNTIME_DIR");
        if (xdg != null && !xdg.isBlank()) {
            try {
                if (Files.exists(Path.of(xdg).resolve("bus"))) return true;
            } catch (InvalidPathException ignored) {
                // Fall through to the session address.
            }
        }
        String bus = env.get("DBUS_SESSION_BUS_ADDRESS");
        return bus != null && !bus.isBlank();
    }

    /** {@code systemd-run --user --scope … --}. The unit name is {@code jk-engine-} plus {@code unitId}. */
    static List<String> prefix(Path systemdRun, String unitId) {
        return List.of(
                systemdRun.toString(),
                "--user",
                "--scope",
                "--quiet",
                "--collect",
                "-p",
                "Delegate=yes",
                "--unit=jk-engine-" + unitId,
                "--");
    }

    /** Eight hex chars. Short enough for a unit name, wide enough that a live scope will not collide. */
    static String unitId() {
        return String.format("%08x", ThreadLocalRandom.current().nextInt());
    }

    /** {@code prefix} then {@code engine}. An empty prefix returns {@code engine} unchanged. */
    static List<String> command(List<String> prefix, List<String> engine) {
        if (prefix.isEmpty()) return engine;
        List<String> out = new ArrayList<>(prefix.size() + engine.size());
        out.addAll(prefix);
        out.addAll(engine);
        return out;
    }

    /** Copy the bus variables systemd-run needs. The allow-list drops them; the scope launch puts them back. */
    static void keepBus(Map<String, String> child, Map<String, String> shell) {
        copy(child, shell, "XDG_RUNTIME_DIR");
        copy(child, shell, "DBUS_SESSION_BUS_ADDRESS");
    }

    /** Install {@code note} for the engine, or remove a stale one a shell exported. */
    static void applyNote(Map<String, String> child, String note) {
        if (note == null || note.isBlank()) child.remove(NOTE);
        else child.put(NOTE, note);
    }

    /**
     * Why a scoped spawn did not stay up. {@code exitCode} is {@code -1} when the process never
     * started. {@code output} is what it wrote; the first real line is kept, capped so a status row
     * stays one line.
     */
    static String failureNote(int exitCode, @Nullable String output) {
        String detail = firstLine(output);
        String head = exitCode >= 0 ? "systemd-run exited " + exitCode : "systemd-run failed to start";
        String note = detail.isEmpty() ? head : head + ": " + detail;
        return note.length() <= 160 ? note : note.substring(0, 157) + "...";
    }

    private static void copy(Map<String, String> child, Map<String, String> shell, String key) {
        String value = shell.get(key);
        if (value != null && !value.isBlank()) child.put(key, value);
    }

    private static String firstLine(@Nullable String output) {
        if (output == null || output.isEmpty()) return "";
        for (String line : output.split("\n")) {
            String trimmed = line.replace('\r', ' ').trim().replaceAll(" +", " ");
            if (trimmed.isEmpty() || trimmed.startsWith("jk engine:")) continue;
            return trimmed;
        }
        return "";
    }
}
