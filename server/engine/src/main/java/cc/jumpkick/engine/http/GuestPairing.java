// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Processes;
import cc.jumpkick.host.WslInterop;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * How a WSL guest pairs with the Windows front door with no setup: it writes its port and token to
 * {@code <state>/engine/guests/<id>.toml} under the Windows user's JumpKick home, which it reaches
 * through {@code /mnt}, and removes the file when it stops. The front door reads the directory to
 * proxy each guest with its token.
 *
 * <p>Who can read or plant a pairing: the directory lies in the Windows user's profile, which
 * Windows lets only that user (and administrators) open, and every process of a WSL distro reaches
 * {@code /mnt/c} as that same Windows user, who can already read the Windows engine's own token
 * beside it. The file is written owner-only where the filesystem keeps POSIX modes. A planted file
 * leaks nothing: the front door sends a pairing's token only to {@code 127.0.0.1} on a band port,
 * and only after the engine there answers with the pairing's id.
 */
public final class GuestPairing {

    /** The directory under an engine's state directory that holds the guests' pairing files. */
    public static final String DIR = "guests";

    private static final Pattern ID = Pattern.compile("[0-9a-f]{12}");

    private static final Duration INTEROP_WAIT = Duration.ofSeconds(10);

    /** One guest's pairing: its identity id, the port it serves on, and its bearer token. */
    public record Pair(String id, int port, String token) {}

    private GuestPairing() {}

    /** The pairing file of guest {@code id} under {@code dir}. */
    static Path file(Path dir, String id) {
        return dir.resolve(id + ".toml");
    }

    /** Writes {@code pair} under {@code dir}, replacing an earlier one, readable by its owner only. */
    public static void write(Path dir, Pair pair) throws IOException {
        if (!ID.matcher(pair.id()).matches()) throw new IllegalArgumentException("not an engine id: " + pair.id());
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", pair.id());
        m.put("port", Integer.toString(pair.port()));
        m.put("token", pair.token());
        OwnerOnlyFiles.write(dir, file(dir, pair.id()), MinimalToml.writeFlat(m));
    }

    /** Removes guest {@code id}'s pairing; nothing when there is none. */
    public static void remove(Path dir, String id) {
        if (!ID.matcher(id).matches()) return;
        try {
            Files.deleteIfExists(file(dir, id));
        } catch (IOException e) {
            Log.debug("guest pairing: could not remove " + id, e);
        }
    }

    /**
     * Every pairing under {@code dir}, by id; a missing directory or an unreadable file holds none, and
     * so does a file not named for its id. The front door proxies only a pairing whose port is on the
     * band ({@link FrontDoor}).
     */
    public static Map<String, Pair> read(Path dir) {
        Map<String, Pair> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try {
            PathUtil.forEachChild(dir, (f, attrs) -> {
                if (!attrs.isRegularFile() || !String.valueOf(f.getFileName()).endsWith(".toml")) return true;
                try {
                    Map<String, String> m = MinimalToml.readFlat(Files.readString(f));
                    String id = m.get("id");
                    String token = m.get("token");
                    int port = Integer.parseInt(String.valueOf(m.get("port")).strip());
                    if (id != null
                            && ID.matcher(id).matches()
                            && f.getFileName().toString().equals(id + ".toml")
                            && token != null
                            && !token.isBlank()
                            && port > 0
                            && port <= 0xFFFF) {
                        out.put(id, new Pair(id, port, token));
                    }
                } catch (IOException | RuntimeException unreadable) {
                    Log.debug("guest pairing: skipping " + f, unreadable);
                }
                return true;
            });
        } catch (IOException e) {
            Log.debug("guest pairing: could not list " + dir, e);
        }
        return out;
    }

    /** Runs a Windows command from the guest and answers its standard output; null when it could not. */
    public interface Interop {
        @Nullable
        String output(List<String> argv);
    }

    /**
     * Where this WSL guest writes its pairing: the Windows engine's state directory, {@code
     * %JK_STATE_DIR%}, else {@code %JK_HOME%\state}, else {@code %USERPROFILE%\.jk\state}, then
     * {@code engine\guests}, found through interop ({@code cmd.exe} for the variables, {@code wslpath}
     * to reach the folder); null when interop cannot answer.
     */
    public static @Nullable Path windowsDir() {
        return windowsDir(GuestPairing::run);
    }

    static @Nullable Path windowsDir(Interop interop) {
        String state = windowsVar(interop, "JK_STATE_DIR");
        if (state == null) {
            String home = windowsHome(interop);
            if (home == null) return null;
            state = home + "\\state";
        }
        String mapped = interop.output(List.of("wslpath", "-u", state));
        if (mapped == null || mapped.isBlank()) return null;
        return Path.of(mapped.strip(), "engine", DIR);
    }

    /**
     * The Windows user's JumpKick home as a Windows path: {@code %JK_HOME%}, else {@code
     * %USERPROFILE%\.jk}; null when interop cannot say.
     */
    public static @Nullable String windowsHome(Interop interop) {
        String home = windowsVar(interop, "JK_HOME");
        if (home != null) return home;
        String profile = windowsVar(interop, "USERPROFILE");
        return profile == null ? null : profile + "\\.jk";
    }

    /** Windows' value of {@code name}, as {@code cmd.exe} expands it; null when unset. */
    private static @Nullable String windowsVar(Interop interop, String name) {
        String out = interop.output(List.of("cmd.exe", "/d", "/c", "echo %" + name + "%"));
        if (out == null) return null;
        String v = out.strip();
        return v.isEmpty() || v.contains("%") || v.contains("\n") ? null : v;
    }

    /**
     * Standard output alone, from a Windows folder: {@code cmd.exe} started in a Linux directory
     * prints a warning about UNC paths on standard error.
     */
    private static @Nullable String run(List<String> argv) {
        try {
            ProcessBuilder pb = WslInterop.builder(argv);
            Path windowsRoot = Path.of("/mnt/c");
            if (Files.isDirectory(windowsRoot)) pb.directory(windowsRoot.toFile());
            Processes.Captured c = Processes.capture(pb, INTEROP_WAIT);
            return c.timedOut() || c.exit() != 0 ? null : c.output();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
