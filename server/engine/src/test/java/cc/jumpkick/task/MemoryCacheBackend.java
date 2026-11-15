// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.CacheBackend;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

/**
 * A {@link CacheBackend} held in memory, standing in for a store on another machine: it counts the
 * lookups it answers, and can be told to fail every call.
 */
final class MemoryCacheBackend implements CacheBackend {

    final Map<String, String> records = new ConcurrentHashMap<>();
    final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    final List<Lookup> lookups = new ArrayList<>();
    volatile boolean down;

    @Override
    public String name() {
        return "memory";
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
    public Set<String> missing(Collection<String> digests) throws IOException {
        if (down) throw new IOException("connection refused");
        Set<String> missing = new TreeSet<>();
        for (String d : digests) {
            if (ActionRecordText.isSha256(d) && !blobs.containsKey(d)) missing.add(d);
        }
        return missing;
    }

    @Override
    public InputStream open(String digest) throws IOException {
        if (down) throw new IOException("connection refused");
        byte[] bytes = blobs.get(digest);
        if (bytes == null) throw new NoSuchFileException(digest);
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void putBlob(String digest, Path source) throws IOException {
        if (down) throw new IOException("connection refused");
        blobs.put(digest, Files.readAllBytes(source));
    }

    @Override
    public void putRecord(String key, String recordText) throws IOException {
        if (down) throw new IOException("connection refused");
        records.put(key, recordText);
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
