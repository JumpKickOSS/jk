// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The front-door token ({@link EnginePaths#FRONT_DOOR_TOKEN}): one bearer token for 8910 whichever
 * engine holds it, so an open tab and a bookmark keep working through a handover. The host engine's
 * own HTTP token is the front-door token: {@link #align} makes the two files agree, the front-door
 * file winning, and a host with only its own token seeds the front-door file from it. A WSL guest
 * holding 8910 reads the same file in the Windows engine's directory (minting it when no host ever
 * ran). Proxied requests never carry it ({@link FrontDoor}).
 */
public final class FrontDoorToken {

    private FrontDoorToken() {}

    /** The front-door token file in the engine directory {@code engineDir}. */
    public static Path file(Path engineDir) {
        return engineDir.resolve(EnginePaths.FRONT_DOOR_TOKEN);
    }

    /**
     * Makes the host's {@code httpToken} file and the front-door file in {@code engineDir} hold one
     * token: the front-door one when it exists, else the host's, copied. An existing front-door file
     * is tightened to owner-only. Nothing when neither exists yet; best effort.
     */
    public static void align(Path engineDir, Path httpToken) {
        Path front = file(engineDir);
        try {
            String shared = read(front);
            String own = read(httpToken);
            if (shared != null) {
                OwnerOnlyFiles.setOwnerOnly(front, "rw-------");
                if (!shared.equals(own)) OwnerOnlyFiles.write(engineDir, httpToken, shared);
            } else if (own != null) {
                OwnerOnlyFiles.write(engineDir, front, own);
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("front-door token: could not align " + front, e);
        }
    }

    /** The token in {@code file}; null when it is missing, blank or unreadable. */
    static @Nullable String read(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            String v = Files.readString(file, StandardCharsets.UTF_8).strip();
            return v.isEmpty() ? null : v;
        } catch (IOException e) {
            return null;
        }
    }
}
