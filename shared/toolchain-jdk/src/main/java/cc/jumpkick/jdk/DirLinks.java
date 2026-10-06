// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Directory pointer: POSIX symlink, Windows junction (no elevation; {@link Junctions}). Used for the
 * stable {@code <vendor>-<major>} JDK alias and the Android SDK pointer.
 */
public final class DirLinks {

    private DirLinks() {}

    /**
     * Point {@code link} at directory {@code target}, creating {@code link}'s parent and removing
     * any existing pointer of that name first. Only an unpopulated {@code link} is removed here —
     * a caller that may be replacing a real directory must clear it itself.
     */
    public static void replace(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        if (Os.isWindows()) {
            Junctions.create(link, target);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }
}
