// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A bare end-of-central-directory record: 22 bytes, a valid zip with no entries. No published
 * artifact is one, but a test fixture serves it as the body of every stub it hosts, under real
 * coordinates too. Such a file must never be promoted into a Maven local repository or seeded into
 * another store as the coordinate's bytes, or a suite that needs the real jar finds a hollow one.
 */
public final class EmptyArchive {

    /** The record's size and signature, {@code PK\u0005\u0006}. */
    static final int SIZE = 22;

    private EmptyArchive() {}

    public static boolean is(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) != SIZE) return false;
            try (InputStream in = Files.newInputStream(file)) {
                byte[] head = in.readNBytes(4);
                return head.length == 4 && head[0] == 0x50 && head[1] == 0x4b && head[2] == 0x05 && head[3] == 0x06;
            }
        } catch (IOException unreadable) {
            return false;
        }
    }
}
