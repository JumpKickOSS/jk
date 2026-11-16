// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Which WSL interop server a Windows program the engine starts goes through. A Linux process starts
 * a Windows one through the interop socket {@code WSL_INTEROP} names, else the one its ancestry
 * leads to, and each {@code /run/WSL/<pid>_interop} belongs to the {@code wsl.exe} session that made
 * it. A resident engine outlives the terminal or ssh session that started it; once that session has
 * ended, its socket still accepts and its relay still runs, but the Windows side is gone: a Windows
 * program started through it never begins, and the Linux side waits past any timeout's signal
 * (observed on bocaminipc for {@code cmd.exe}, {@code jk.exe} and {@code powershell.exe} from a WSL
 * engine started over ssh). The distro's own server, {@code /run/WSL/1_interop}, lives as long as
 * the distro, so every engine-side start of a Windows program goes through it.
 */
public final class WslInterop {

    /** The distro's own interop server, alive while the distro runs. */
    static final String DISTRO = "/run/WSL/1_interop";

    private WslInterop() {}

    /**
     * {@code pb} with {@code WSL_INTEROP} naming the distro's interop server, on a WSL guest with
     * interop where it exists; unchanged anywhere else.
     */
    public static ProcessBuilder route(ProcessBuilder pb) {
        Guest guest = Guest.detect();
        if (guest.kind() != Guest.Kind.WSL || !guest.interop()) return pb;
        @Nullable String server = server(p -> Files.exists(Path.of(p)));
        return server == null ? pb : ChildEnv.put(pb, "WSL_INTEROP", server);
    }

    /** {@code argv} as a builder routed through the distro's interop server ({@link #route}). */
    public static ProcessBuilder builder(List<String> argv) {
        return route(new ProcessBuilder(argv));
    }

    /** The server to name, or null when the distro has none (the start goes ahead as it would). */
    static @Nullable String server(Predicate<String> exists) {
        return exists.test(DISTRO) ? DISTRO : null;
    }

    /** Test seam: {@code env} after {@link #route}'s change, for a distro where {@code exists} holds. */
    static Map<String, String> routed(Map<String, String> env, Predicate<String> exists) {
        String s = server(exists);
        if (s != null) ChildEnv.put(env, "WSL_INTEROP", s);
        return env;
    }
}
