// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The HTTP port a guest engine binds when no port is configured: a sticky port in 8911 to 8999,
 * first chosen from the engine's identity and then kept in its state directory, so a restart keeps
 * the origin a bookmark, a tab or an MCP config uses. The host engine binds 8910; a guest never
 * takes it just because it is free.
 */
public final class BandPort {

    public static final int FIRST = 8911;
    public static final int LAST = 8999;

    /** The file under the engine's state directory that keeps the port. */
    public static final String FILE = "http-port";

    private BandPort() {}

    /** The port to try first: the kept one when it is in the band, else the identity's. */
    public static int preferred(Path file, String id) {
        try {
            if (Files.isRegularFile(file)) {
                int kept = Integer.parseInt(Files.readString(file).strip());
                if (kept >= FIRST && kept <= LAST) return kept;
            }
        } catch (IOException | NumberFormatException unreadable) {
            Log.debug("band port: " + file + " unreadable; choosing again", unreadable);
        }
        return FIRST + Integer.remainderUnsigned(Integer.parseUnsignedInt(id.substring(0, 7), 16), LAST - FIRST + 1);
    }

    /** The port after {@code port} in the band, wrapping. */
    public static int next(int port) {
        return port >= LAST ? FIRST : port + 1;
    }

    /** Keep {@code port} for the next start. */
    public static void remember(Path file, int port) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            AtomicWrites.replace(file, Integer.toString(port));
        } catch (IOException e) {
            Log.debug("band port: could not keep " + port, e);
        }
    }
}
