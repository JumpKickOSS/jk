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
import org.jspecify.annotations.Nullable;

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
 * <p>A blob is named by its sha256 and its byte count: a store across a network keys blobs by both,
 * so a blob held at another size is one it does not hold.
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

    /**
     * As {@link #records}, with the test results this backend holds under a read list whose base is
     * a key it holds no record of ({@link Candidate}). A backend that keeps no read lists answers
     * none.
     */
    default Answer lookup(Lookup lookup) throws IOException {
        return new Answer(records(lookup), Map.of());
    }

    /** Of {@code blobs} (sha256 hex to byte count), those this backend does not hold at that size. */
    Set<String> missing(Map<String, Long> blobs) throws IOException;

    /**
     * Hand {@code sink} the bytes of every blob of {@code blobs} (sha256 hex to byte count). The
     * sink verifies them against the digest before it keeps them ({@link Cas#putVerified}).
     *
     * @throws java.nio.file.NoSuchFileException when the backend does not hold one of them
     */
    void fetch(Map<String, Long> blobs, BlobSink sink) throws IOException;

    /** Keep the bytes of each file of {@code blobs} as the blob its key names, which the caller has computed. */
    void putBlobs(Map<String, Path> blobs) throws IOException;

    /**
     * Keep {@code recordText} under {@code key}. Every blob the record names is put first, so a
     * store never holds a record whose payload it lacks.
     */
    void putRecord(String key, String recordText) throws IOException;

    /**
     * As {@link #putRecord(String, String)}, for a suite's green result that folded in the files the
     * suite was found to read ({@code reads}): a backend that keeps read lists files it under the
     * list's base, so a host that has not run the suite can find it. A backend that keeps none
     * stores the record alone.
     */
    default void putRecord(String key, String recordText, @Nullable Reads reads) throws IOException {
        putRecord(key, recordText);
    }

    /**
     * Whether this build may write here at all. A backend that learns from the store that the
     * caller writes nothing (a read-only token) says so, and nothing is offered to it.
     */
    default boolean writable() {
        return true;
    }

    /** Where {@link #fetch} hands each blob's bytes. */
    @FunctionalInterface
    interface BlobSink {
        void accept(String digest, InputStream bytes) throws IOException;
    }

    /**
     * One batched lookup: the action keys, the platform asking and, for some keys, which toolchains'
     * records the reader takes ({@link Accept}). The platform and the toolchain are dimensions of the
     * lookup, never of the key: a step whose outputs do not depend on the platform has one key
     * everywhere, and a store holding records from several platforms or JDKs may prefer the asker's.
     */
    record Lookup(List<String> keys, String platform, Map<String, Accept> accept) {
        public Lookup {
            keys = List.copyOf(keys);
            Objects.requireNonNull(platform, "platform");
            accept = Map.copyOf(accept);
        }

        public Lookup(List<String> keys, String platform) {
            this(keys, platform, Map.of());
        }
    }

    /**
     * Which toolchains' records a reader takes for a key: the token of the toolchain it runs
     * ({@code toolchain:jdk:<vendor>/<version>/…}), the module's level, the match ({@code major},
     * {@code level} or {@code build}) and an organisation's requirement: a vendor word the record's
     * vendor must contain ({@code eclipse} for Temurin) and a version (a major, an exact version or a
     * {@code >=} floor), each null when the requirement names none. The reader checks every record it
     * gets against its own policy regardless; a store that applies this only saves it the bytes.
     */
    record Accept(
            String toolchain,
            int level,
            String match,
            @Nullable String requireVendor,
            @Nullable String requireVersion) {
        public Accept {
            Objects.requireNonNull(toolchain, "toolchain");
            Objects.requireNonNull(match, "match");
        }
    }

    /**
     * A suite's read list: the stamp a host computes before it knows what the suite reads ({@code
     * base}) and the portable names of the files the stored stamp folded in ({@code
     * ws:src/test/resources/x.json}, {@code store:…}).
     */
    record Reads(String base, List<String> files) {
        public Reads {
            Objects.requireNonNull(base, "base");
            files = List.copyOf(files);
        }
    }

    /** A test result stored under {@code key} with read list {@code reads}, found by its base. */
    record Candidate(String key, String recordText, Reads reads) {
        public Candidate {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(recordText, "recordText");
            Objects.requireNonNull(reads, "reads");
        }
    }

    /**
     * What a lookup found: record text by key, and for keys with none, the candidates stored under a
     * read list on that key, best first, and the toolchain of the closest record held under the key
     * that the reader's policy refused ({@code declined}), so the reader can say why it missed.
     */
    record Answer(Map<String, String> records, Map<String, List<Candidate>> byReads, Map<String, String> declined) {
        public Answer {
            records = Map.copyOf(records);
            byReads = Map.copyOf(byReads);
            declined = Map.copyOf(declined);
        }

        public Answer(Map<String, String> records, Map<String, List<Candidate>> byReads) {
            this(records, byReads, Map.of());
        }
    }

    /**
     * A store's refusal of one record or blob batch that leaves the rest of the build's uploads
     * standing: the record is not one it shares ({@code refused_kind}, {@code red_result}), not one it
     * reads ({@code invalid}), or too large. The upload goes on with the next record.
     */
    final class Skipped extends IOException {
        public Skipped(String message) {
            super(message);
        }
    }

    /**
     * A put the store could not take now ({@code unavailable}, {@code rate_limited}, a 5xx): the
     * protocol marks it retryable for uploads, so the writer tries it again behind the build.
     */
    final class Unavailable extends IOException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /**
     * The store takes no write from this caller for the rest of the build ({@code forbidden},
     * {@code read_only}, {@code unauthenticated}, {@code unsupported_protocol}): every later put would
     * be refused alike, so the writer stops; reads go on.
     */
    final class WritesRefused extends IOException {
        public WritesRefused(String message) {
            super(message);
        }
    }

    /** A record put refused because the store lacks blobs it names: upload {@link #missing} and put it again. */
    final class BlobsMissing extends IOException {
        private final Collection<String> missing;

        public BlobsMissing(String message, Collection<String> missing) {
            super(message);
            this.missing = List.copyOf(missing);
        }

        /** The sha256 of every blob the store said it lacks. */
        public Collection<String> missing() {
            return missing;
        }
    }
}
