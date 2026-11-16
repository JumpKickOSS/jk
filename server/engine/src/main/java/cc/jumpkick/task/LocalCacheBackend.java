// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The action cache on this machine's disk: records under {@code keys/<actionKey>} beside the cache
 * CAS that holds their blobs, with the artifact store's CAS as a second place a promoted blob may
 * be found. The one {@link CacheBackend} jk has today, and the one every other is layered behind.
 *
 * <p>Beyond the interface it answers what only a local store can: where a blob is on disk (a
 * restore clones from there), and {@link #discard}, the one way a record leaves, for a record this
 * machine refused or found torn.
 */
public final class LocalCacheBackend implements CacheBackend {

    private final Cas cas;
    private final @Nullable Cas storeCas;
    private final Path root;

    public LocalCacheBackend(Cas cas, Path root, @Nullable Cas storeCas) {
        this.cas = Objects.requireNonNull(cas, "cas");
        this.root = Objects.requireNonNull(root, "root");
        this.storeCas = storeCas;
    }

    @Override
    public String name() {
        return "local";
    }

    Cas cas() {
        return cas;
    }

    @Nullable
    Cas storeCas() {
        return storeCas;
    }

    Path root() {
        return root;
    }

    /** Where record {@code key} lives, present or not. */
    Path recordPath(String key) {
        return ActionTree.KEYS.under(root).resolve(key);
    }

    /**
     * Record {@code key}'s text, or empty when there is none. Reads without a prior existence
     * check: another engine's prune can unlink the key between the two, and a vanished record is a
     * miss, not a failure.
     */
    Optional<String> record(String key) throws IOException {
        // A blank key (a torn tasks/ pointer) names the keys dir itself, not a record.
        if (key.isBlank()) return Optional.empty();
        try {
            return Optional.of(AtomicWrites.readString(recordPath(key)));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException denied) {
            if (!deniedByAConcurrentReplace(denied)) throw denied;
            return Optional.empty();
        }
    }

    /**
     * Whether {@code e} is Windows refusing a read of a name a concurrent store is replacing,
     * after {@link AtomicWrites#readString} has already spent its retry budget on it.
     *
     * <p>True makes the read a miss. That is always safe and never a wrong hit — a miss costs the
     * caller a re-run of an action whose record it could not read — where throwing costs the build
     * a step for a race it did not cause. Warned rather than debugged: with the budget {@link
     * AtomicWrites} uses, reaching here means the contention outran a measurement that saw none.
     */
    static boolean deniedByAConcurrentReplace(IOException e) {
        if (!Os.isWindows() || !AtomicWrites.isTransientWindowsLock(e)) return false;
        Log.warn("jk: action-cache entry not read — a writer held the name", "path", String.valueOf(e.getMessage()));
        return true;
    }

    /**
     * Records for the keys whose record and every blob are on this disk. A record that does not
     * parse is left out here and judged by the caller's own read.
     */
    @Override
    public Map<String, String> records(Lookup lookup) throws IOException {
        Map<String, String> found = new LinkedHashMap<>();
        for (String key : lookup.keys()) {
            Optional<String> text = record(key);
            if (text.isEmpty()) continue;
            try {
                var parsed = ActionRecordText.parse(text.get(), key);
                if (missing(parsed.outputs().values()).isEmpty()) found.put(key, text.get());
            } catch (RuntimeException unreadable) {
                // Not this answer's to judge: the caller's own read refuses or discards it.
            }
        }
        return found;
    }

    @Override
    public Set<String> missing(Collection<String> digests) {
        Set<String> missing = new TreeSet<>();
        for (String sha : digests) {
            if (!ActionRecordText.isSha256(sha)) continue; // a marker scalar, no blob
            if (!Files.isRegularFile(blobPath(sha))) missing.add(sha);
        }
        return missing;
    }

    /** Blob {@code sha}'s path: the cache CAS's when it is there, else the store CAS's when it is there, else the cache CAS's. */
    Path blobPath(String sha) {
        Path p = cas.pathFor(sha);
        if (Files.isRegularFile(p)) return p;
        if (storeCas != null) {
            Path s = storeCas.pathFor(sha);
            if (Files.isRegularFile(s)) return s;
        }
        return p;
    }

    @Override
    public InputStream open(String digest) throws IOException {
        return Files.newInputStream(blobPath(digest));
    }

    /** Copies, never links: compilers rewrite outputs in place and must not mutate a blob ({@link Cas#putFile}). */
    @Override
    public void putBlob(String digest, Path source) throws IOException {
        cas.putFile(source, digest);
    }

    /** Atomic temp and move: a concurrent reader never sees a truncated record. */
    @Override
    public void putRecord(String key, String recordText) throws IOException {
        Files.createDirectories(ActionTree.KEYS.under(root));
        AtomicWrites.replace(recordPath(key), recordText);
    }

    /**
     * Where the record of {@code key} that {@code toolchain} made is kept beside the latest one:
     * {@code keys/<key>~<sha256(toolchain)[0:16]>}. One key holds a record per JDK once keys name
     * the Java level and not the JDK ({@link ToolchainPolicy}); each variant is a record of its own to
     * the prune and the sweep.
     */
    Path variantPath(String key, String toolchain) {
        return ActionTree.KEYS
                .under(root)
                .resolve(key + VARIANT + Hashing.sha256Hex(toolchain).substring(0, 16));
    }

    /** {@code keys/<key>~index}: one {@code <toolchain>} per line, the JDKs {@code key} has variants of. */
    Path variantIndex(String key) {
        return ActionTree.KEYS.under(root).resolve(key + VARIANT + "index");
    }

    /** The separator between a key and its variant's suffix. */
    static final String VARIANT = "~";

    /** Keep {@code recordText} as {@code key}'s variant made by {@code toolchain}, and list it in the index. */
    void putVariant(String key, String toolchain, String recordText) throws IOException {
        Files.createDirectories(ActionTree.KEYS.under(root));
        AtomicWrites.replace(variantPath(key, toolchain), recordText);
        List<String> known = variants(key);
        if (!known.contains(toolchain)) {
            List<String> all = new ArrayList<>(known);
            all.add(toolchain);
            AtomicWrites.replace(variantIndex(key), String.join("\n", all) + "\n");
        }
    }

    /** The toolchains {@code key} has variants of, as its index lists them; empty without one. */
    List<String> variants(String key) throws IOException {
        Optional<String> text;
        try {
            text = Optional.of(AtomicWrites.readString(variantIndex(key)));
        } catch (NoSuchFileException absent) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : text.get().split("\n")) if (!line.isBlank()) out.add(line.strip());
        return out;
    }

    /** {@code key}'s variant made by {@code toolchain}, or empty. */
    Optional<String> variant(String key, String toolchain) throws IOException {
        try {
            return Optional.of(AtomicWrites.readString(variantPath(key, toolchain)));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
    }

    /** Take record {@code key} off this disk: one this machine refused to trust, or found cut short. */
    void discard(String key) throws IOException {
        Files.deleteIfExists(recordPath(key));
    }
}
