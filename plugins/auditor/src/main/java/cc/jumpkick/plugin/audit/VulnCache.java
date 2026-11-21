// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One OSV advisory's body on disk, as OSV served it at {@code modified}: the file holds that time on
 * its first line and the body after it, and answers only while the batch still reports the same
 * time. A read touches the file, so its age is the time since an audit last needed it.
 */
record VulnCache(Path file, String modified) {

    VulnCache {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(modified, "modified");
    }

    /** The body the file holds for {@link #modified}, or null when it holds another or none. */
    @Nullable
    String read() {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException | RuntimeException absent) {
            return null;
        }
        int newline = text.indexOf('\n');
        if (newline < 0 || !text.substring(0, newline).equals(modified)) return null;
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(Clock.SYSTEM.millis()));
        } catch (IOException ignored) {
            // the entry still answers; it only ages as if unused
        }
        return text.substring(newline + 1);
    }

    /** Keep {@code body} as the advisory at {@link #modified}; a cache that cannot be written is skipped. */
    void write(String body) {
        if (modified.indexOf('\n') >= 0) return;
        try {
            Files.createDirectories(Objects.requireNonNull(file.getParent()));
            AtomicWrites.replace(file, modified + "\n" + body);
        } catch (IOException | RuntimeException unwritable) {
            // the audit already has the body; the next one fetches it again
        }
    }
}
