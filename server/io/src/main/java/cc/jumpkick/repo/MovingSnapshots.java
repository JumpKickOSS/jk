// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.KeyedLocks;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The store's side of a non-unique snapshot ({@link Versions#isNonUniqueSnapshot}): a file its
 * source replaces in place, so a stored copy answers only while the source still holds its bytes.
 * {@link MavenRepo} asks a file repository's file through {@link #sourceDigest} and a remote's
 * published checksum, and holds {@link #hold} from its look in the store to the download that
 * replaces the copy.
 */
final class MovingSnapshots {

    private MovingSnapshots() {}

    /**
     * One lock per stored file of a non-unique snapshot. A file replaced in place is otherwise read
     * by one caller under the digest another caller's look recorded for the copy before it, and
     * that pair is what a lock pins and what the effective-POM memo is keyed by.
     */
    private static final KeyedLocks<List<@Nullable Object>> LOCKS = new KeyedLocks<>();

    /** A held lock that holds nothing: every other file is immutable once stored. */
    static final KeyedLocks.Held NONE = () -> {};

    /**
     * The digests of non-unique snapshot files in file repositories, by the file's stat identity: a
     * re-install moves the stamp and the file is hashed again.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, String> SOURCE_DIGESTS = StampedMemo.bounded(4_096);

    /** Whether {@code coord}'s files are a non-unique snapshot's. */
    static boolean moving(Coordinate coord) {
        return Versions.isNonUniqueSnapshot(coord.version());
    }

    /** The lock on {@code relativePath} in the store at {@code root} when {@code coord} is moving; else nothing. */
    static KeyedLocks.Held hold(Coordinate coord, @Nullable Path root, String relativePath)
            throws InterruptedException {
        return moving(coord) ? LOCKS.holdInterruptibly(Arrays.asList(root, relativePath)) : NONE;
    }

    /**
     * The SHA-256 of the file at {@code source}, a {@code file:} URI; empty when it is not a path on
     * this machine, unreadable or absent.
     */
    static Optional<String> sourceDigest(URI source) {
        Path file;
        try {
            file = Path.of(source);
        } catch (IllegalArgumentException | FileSystemNotFoundException notAPath) {
            return Optional.empty();
        }
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(file);
        if (stamp == null) return Optional.empty();
        String sha = SOURCE_DIGESTS.get(file, stamp, () -> {
            try {
                return Hashing.sha256Hex(file);
            } catch (IOException unreadable) {
                return "";
            }
        });
        return sha.isBlank() ? Optional.empty() : Optional.of(sha);
    }
}
