// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.CacheBackend;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * A {@link CacheBackend} held in memory, standing in for a store on another machine: it counts the
 * lookups it answers, and can be told to fail every call.
 */
final class MemoryCacheBackend implements CacheBackend {

    final Map<String, String> records = new ConcurrentHashMap<>();
    final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    final List<Lookup> lookups = new ArrayList<>();
    volatile boolean down;
    /** What {@link #writable} answers: false for a caller the store lets write nothing. */
    volatile boolean writable = true;
    /** Keys whose put is refused as a record the store does not take. */
    final Set<String> skip = ConcurrentHashMap.newKeySet();
    /** True to refuse every record put as a store that takes no write from this caller. */
    volatile boolean readOnly;
    /** Keys whose next puts answer 503 unavailable, how many times each. */
    final Map<String, Integer> unavailable = new ConcurrentHashMap<>();
    /** Drop every blob on the first record put, as a sweep between the question and the put would. */
    volatile boolean loseBlobsBeforeFirstPut;
    /** Record puts asked, taken or not. */
    volatile int puts;
    /** The read list each record was put with. */
    final Map<String, Reads> reads = new ConcurrentHashMap<>();

    /** The key seed this store issues, null for none; {@link #seedFailure} when set answers instead. */
    volatile @Nullable IssuedSeed issued;

    volatile @Nullable IOException seedFailure;

    /** How many times the seed was asked for. */
    volatile int seedAsks;

    /** What names this store as a seed's scope. */
    volatile String scope = "memory";

    @Override
    public String seedScope() {
        return scope;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public synchronized @Nullable IssuedSeed keySeed() throws IOException {
        seedAsks++;
        IOException failure = seedFailure;
        if (failure != null) throw failure;
        return issued;
    }

    @Override
    public synchronized Map<String, String> records(Lookup lookup) throws IOException {
        if (down) throw new IOException("connection refused");
        lookups.add(lookup);
        Map<String, String> found = new LinkedHashMap<>();
        for (String key : lookup.keys()) {
            String text = records.get(key);
            if (text == null) continue;
            String recorded = text.lines()
                    .filter(l -> l.startsWith("KEY "))
                    .findFirst()
                    .map(l -> l.substring(4))
                    .orElse(key);
            Collection<String> outputs = recorded.equals(key)
                    ? ActionRecordText.parse(text, key).outputs().values()
                    : List.of();
            if (missing(outputs).isEmpty()) found.put(key, text);
        }
        return found;
    }

    @Override
    public Set<String> missing(Map<String, Long> sized) throws IOException {
        return missing(sized.keySet());
    }

    private Set<String> missing(Collection<String> digests) throws IOException {
        if (down) throw new IOException("connection refused");
        Set<String> missing = new TreeSet<>();
        for (String d : digests) {
            if (ActionRecordText.isSha256(d) && !blobs.containsKey(d)) missing.add(d);
        }
        return missing;
    }

    @Override
    public void fetch(Map<String, Long> sized, BlobSink sink) throws IOException {
        for (String digest : sized.keySet()) {
            if (down) throw new IOException("connection refused");
            byte[] bytes = blobs.get(digest);
            if (bytes == null) throw new NoSuchFileException(digest);
            sink.accept(digest, new ByteArrayInputStream(bytes));
        }
    }

    @Override
    public void putBlobs(Map<String, Path> files) throws IOException {
        if (down) throw new IOException("connection refused");
        for (Map.Entry<String, Path> e : files.entrySet()) blobs.put(e.getKey(), Files.readAllBytes(e.getValue()));
    }

    @Override
    public synchronized Answer lookup(Lookup lookup) throws IOException {
        Map<String, String> found = records(lookup);
        Map<String, List<Candidate>> byReads = new LinkedHashMap<>();
        for (String base : lookup.keys()) {
            if (found.containsKey(base)) continue;
            reads.forEach((key, list) -> {
                String text = records.get(key);
                if (list.base().equals(base) && text != null)
                    byReads.computeIfAbsent(base, b -> new ArrayList<>()).add(new Candidate(key, text, list));
            });
        }
        return new Answer(found, byReads);
    }

    @Override
    public synchronized void putRecord(String key, String recordText, @Nullable Reads list) throws IOException {
        putRecord(key, recordText);
        if (list != null) reads.put(key, list);
    }

    @Override
    public synchronized void putRecord(String key, String recordText) throws IOException {
        if (down) throw new IOException("connection refused");
        puts++;
        if (skip.contains(key)) throw new Skipped("refused_kind: " + key);
        if (readOnly) throw new WritesRefused("record " + key + ": 403 read_only");
        Integer left = unavailable.get(key);
        if (left != null && left > 0) {
            unavailable.put(key, left - 1);
            throw new Unavailable("record " + key + ": 503 unavailable");
        }
        if (loseBlobsBeforeFirstPut) {
            loseBlobsBeforeFirstPut = false;
            blobs.clear();
        }
        List<String> lacking = new ArrayList<>();
        for (String sha : ActionRecordText.parse(recordText, key).outputs().values()) {
            if (ActionRecordText.isSha256(sha) && !blobs.containsKey(sha)) lacking.add(sha);
        }
        if (!lacking.isEmpty()) throw new BlobsMissing("blobs_missing", lacking);
        records.put(key, recordText);
    }

    @Override
    public boolean writable() {
        return writable;
    }

    /** Copy what {@code cache} holds under {@code key}, record and blobs, as another machine's upload would. */
    void seedFrom(ActionCache cache, String key) throws IOException {
        ActionCache.ActionRecord record = cache.lookup(key).orElseThrow();
        for (String sha : record.outputs().values()) {
            if (ActionRecordText.isSha256(sha))
                blobs.put(sha, Files.readAllBytes(cache.cas().pathFor(sha)));
        }
        records.put(key, Files.readString(cache.root().resolve("keys").resolve(key)));
    }
}
