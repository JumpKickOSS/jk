// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.Hashing;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import sbt.internal.inc.Analysis;
import sbt.internal.inc.consistent.ConsistentAnalysisFormat;
import sbt.internal.inc.consistent.SerializerFactory;
import xsbti.compile.analysis.ReadWriteMappers;

/**
 * The decoded producer analyses of every compile and forecast in one worker. A module with many
 * upstream siblings would otherwise gunzip and decode each sibling's analysis, and rebuild its
 * Java-only API view ({@link ClasspathAnalyses#apiOnlyForJava}), on every compile and every plan
 * that reads it, though the file changes only when its producer compiles again.
 *
 * <p>An entry is keyed by the SHA-256 of the analysis file's bytes, and decoded from those same
 * bytes, so a hit is the analysis of exactly the file read now. A key of path, file key, size and
 * mtime would skip the hash, but it would trust every writer of the file to replace it in one step
 * and to move its mtime: Zinc's store does rename into place, the engine's action-cache restore
 * writes the file its own way, and a restore that puts back an earlier analysis may well put back
 * its mtime too. Reading and hashing a typical analysis (tens of KB) is a small fraction of
 * decoding it.
 *
 * <p>What is cached is only the decode. Whether an analysis still describes its producer's classes
 * on disk is asked again on every use, by {@link ClasspathAnalyses}: an action-cache restore
 * rewrites the classes without touching the analysis file. Zinc analyses are immutable, so the
 * compiles running on the worker's lanes share an entry.
 *
 * <p>Bounded, least recently used out first, by an estimate of each entry's heap: a decoded
 * analysis takes roughly {@link #DECODED_PER_COMPRESSED} times its gzip size. The default bound is
 * {@link #HEAP_SHARE} of the worker's maximum heap: the compiles themselves are what the heap is
 * for, and an entry evicted only costs a decode. The worker's idle trim {@linkplain #clear empties}
 * it, as it drops the stamps.
 */
final class ProducerAnalysisCache {

    /** Estimated heap of a decoded analysis per byte of its gzip file. */
    static final int DECODED_PER_COMPRESSED = 10;

    /** The default bound's share of the maximum heap: one eighth. */
    static final int HEAP_SHARE = 8;

    private final long capacity;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicLong decodes = new AtomicLong();
    private long weight;

    private record Entry(Analysis analysis, long weight) {}

    /** @param capacity the most estimated decoded bytes held at once */
    ProducerAnalysisCache(long capacity) {
        this.capacity = capacity;
    }

    /** A cache bounded at {@link #HEAP_SHARE} of this JVM's maximum heap. */
    static ProducerAnalysisCache sizedToHeap() {
        return new ProducerAnalysisCache(Runtime.getRuntime().maxMemory() / HEAP_SHARE);
    }

    /**
     * The analysis in {@code file} with Java classes' bytecode hashes cleared, empty when the file
     * is missing or is not a Zinc analysis. Not checked against the classes it describes.
     */
    Optional<Analysis> load(Path file) {
        byte[] gz;
        try {
            gz = Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        String key = Hashing.sha256Hex(gz);
        synchronized (this) {
            Entry hit = entries.get(key);
            if (hit != null) return Optional.of(hit.analysis());
        }
        Optional<Analysis> decoded = decode(gz).map(ClasspathAnalyses::apiOnlyForJava);
        decoded.ifPresent(a -> put(key, new Entry(a, (long) gz.length * DECODED_PER_COMPRESSED)));
        return decoded;
    }

    /** How many analyses this cache has decoded: every load that missed and read an analysis. */
    long decodes() {
        return decodes.get();
    }

    /** Drop every entry: called by an idle worker's trim. */
    synchronized void clear() {
        entries.clear();
        weight = 0;
    }

    private synchronized void put(String key, Entry entry) {
        if (entry.weight() > capacity) return;
        Entry old = entries.put(key, entry);
        if (old != null) weight -= old.weight();
        weight += entry.weight();
        Iterator<Map.Entry<String, Entry>> eldest = entries.entrySet().iterator();
        while (weight > capacity && eldest.hasNext()) {
            weight -= eldest.next().getValue().weight();
            eldest.remove();
        }
    }

    /**
     * Decode a binary analysis from its gzip bytes, as Zinc's binary file store reads its file. A
     * bad gzip header throws here, where the store would leak its stream ({@link
     * ZincWorkdir#gzipHeaderReadable}); a stream over bytes has nothing to leak.
     */
    private Optional<Analysis> decode(byte[] gz) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            var format = new ConsistentAnalysisFormat(ReadWriteMappers.getEmptyMappers(), true);
            var read = format.read(SerializerFactory.binary().deserializerFor(in));
            decodes.incrementAndGet();
            return read._1() instanceof Analysis analysis ? Optional.of(analysis) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
