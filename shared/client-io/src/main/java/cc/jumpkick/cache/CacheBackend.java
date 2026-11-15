// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A place action records and the blobs they name are kept: the local disk cache, or a second
 * store behind it. The action cache reads and writes its records and blobs through this, so a
 * remote store attaches as another implementation, consulted after the local one.
 *
 * <p>Records travel as their text ({@link ActionRecordText}); the reader parses and validates
 * every one as untrusted input, so a backend never vouches for a record. What a reader refuses is
 * a miss for that reader alone: there is no delete here, so no client can take a record from a
 * store it shares with others.
 *
 * <p>Every method may throw {@link IOException} for a store it cannot reach. The caller treats
 * that as a miss, never as a failed build.
 */
public interface CacheBackend {

    /** A short name for messages: {@code local}, or the store's host. */
    String name();

    /**
     * The record text this backend holds for each key of {@code lookup} it can serve whole: the
     * record and every blob it names. A key it lacks, or whose blobs are not all held, is absent
     * from the answer. One call answers many keys, so a store across a network costs one round
     * trip per batch rather than per key.
     */
    Map<String, String> records(Lookup lookup) throws IOException;

    /** Of {@code digests} (sha256 hex), those this backend does not hold. */
    Set<String> missing(Collection<String> digests) throws IOException;

    /**
     * The bytes of blob {@code digest}. The caller verifies them against the digest before it keeps
     * them ({@link Cas#putVerified}).
     *
     * @throws java.nio.file.NoSuchFileException when the backend does not hold it
     */
    InputStream open(String digest) throws IOException;

    /** Keep {@code source}'s bytes as blob {@code digest}, which the caller has computed. */
    void putBlob(String digest, Path source) throws IOException;

    /**
     * Keep {@code recordText} under {@code key}. Every blob the record names is put first, so a
     * store never holds a record whose payload it lacks.
     */
    void putRecord(String key, String recordText) throws IOException;

    /**
     * One batched lookup: the action keys, and the platform asking. The platform is a dimension of
     * the lookup, never of the key: a step whose outputs do not depend on the platform has one key
     * everywhere, and a store holding records from several platforms may prefer the asker's.
     */
    record Lookup(List<String> keys, String platform) {
        public Lookup {
            keys = List.copyOf(keys);
            Objects.requireNonNull(platform, "platform");
        }
    }
}
