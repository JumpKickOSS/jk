// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.tukaani.xz.XZInputStream;

/**
 * XZ inflate for release client binaries. Lives in the engine jar so the native CLI never
 * links tukaani; {@link EngineMain} exposes it as {@code --inflate-xz}.
 */
final class Xz {

    private Xz() {}

    /** Inflate an {@code .xz} file to {@code out} (the raw client binary, not an archive). */
    static void inflate(Path in, Path out) throws IOException {
        try (var xz = new XZInputStream(Files.newInputStream(in));
                var dest = Files.newOutputStream(out)) {
            xz.transferTo(dest);
        }
    }
}
