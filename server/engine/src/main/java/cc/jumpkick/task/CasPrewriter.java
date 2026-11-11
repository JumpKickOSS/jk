// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.TreeContent;
import cc.jumpkick.host.time.Clock;
import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Background CAS ingest of an output dir while a compiler still writes it. The directories of the
 * tree are watched for file events; a file an event names is hashed and copied into the CAS once
 * its {@code (size, mtime)} holds still across one {@value #SETTLE_MILLIS} ms tick. {@link #finish}
 * walks the tree once, re-checks drift and is the authoritative pass, so an event the watch service
 * drops or delivers late costs only the head start.
 *
 * <p>One process-wide thread serves every compile in flight, and between events it stats only the
 * files still settling: the tree is walked twice per compile, at start to watch its directories and
 * at {@code finish}, however long the compile runs.
 */
public final class CasPrewriter implements AutoCloseable {

    /** How long a file's size and mtime must hold still before it is hashed. */
    static final long SETTLE_MILLIS = 100;

    /** The name of the one thread that serves every prewriter in the process. */
    static final String THREAD_NAME = "jk-cas-prewriter";

    private final Cas cas;
    private final Path root;
    private final ConcurrentMap<Path, String> processed = new ConcurrentHashMap<>();
    /** Files an event named, with what the last tick saw of them; the watcher thread alone touches it. */
    private final Map<Path, @Nullable Snapshot> pending = new LinkedHashMap<>();

    private final ConcurrentLinkedQueue<WatchKey> keys = new ConcurrentLinkedQueue<>();
    private final AtomicInteger treeWalks = new AtomicInteger();
    /** Guarded by {@code this}: once false, the watcher thread neither hashes nor watches for it. */
    private boolean running = true;

    private CasPrewriter(Cas cas, Path outputDir) {
        this.cas = cas;
        this.root = outputDir.toAbsolutePath().normalize();
    }

    /**
     * Start a prewriter watching {@code outputDir}. The caller's responsibility to call {@link
     * #finish} before reading the result, or {@link #close} on a failed compile; either stops the
     * watch.
     */
    public static CasPrewriter watching(Cas cas, Path outputDir) {
        CasPrewriter p = new CasPrewriter(cas, outputDir);
        if (Watcher.SERVICE != null && Files.isDirectory(p.root)) {
            Watcher.ACTIVE.add(p);
            p.treeWalks.incrementAndGet();
            try {
                // Directories only: a file already here is answered by finish, mostly from the memo.
                PathUtil.forEachEntry(p.root, dir -> false, (path, attrs) -> {
                    if (attrs.isDirectory()) p.watch(path);
                    return true;
                });
            } catch (IOException ignored) {
                // Best-effort: what the watch misses, finish ingests.
            }
        }
        return p;
    }

    /**
     * Stop watching and run the final correctness pass. Returns the {@code (relPath → hex)} map ready
     * for {@link ActionCache#storeWithOutputs}.
     */
    public Map<String, String> finish() throws IOException {
        stop();
        Map<String, String> outputs = new TreeMap<>();
        if (!Files.exists(root)) return outputs;
        treeWalks.incrementAndGet();
        PathUtil.forEachRegularFile(root, (file, attrs) -> {
            String relPath = root.relativize(file).toString().replace(File.separatorChar, '/');
            // What ActionCache.store would keep of the same tree.
            if (!TreeContent.isContent(relPath)) return;
            // Through the memo: a file whose stat identity (size, mtime, ctime, file id) still
            // matches what an earlier build recorded is not read again. An equal-length rewrite
            // that restores the old mtime still moves the change time and so the identity, and a
            // self-hash is recorded only once the file is settled, so the memo cannot hand back
            // the bytes a file held before.
            String hex = FileHashMemo.contentHash(file, attrs);
            if (!hex.equals(processed.get(file))) cas.putFile(file, hex);
            // This hash is the authoritative one: seeding it lets downstream ClasspathFingerprint
            // passes over the same tree answer from the memo.
            FileHashMemo.rememberContent(file, hex);
            outputs.put(relPath, hex);
        });
        return outputs;
    }

    @Override
    public void close() {
        stop();
    }

    /** Whole-tree walks this prewriter made: one to watch the directories, one in {@link #finish}. */
    int treeWalks() {
        return treeWalks.get();
    }

    private void stop() {
        synchronized (this) {
            if (!running) return;
            running = false;
        }
        Watcher.ACTIVE.remove(this);
        for (WatchKey key : keys) Watcher.release(key, this);
        keys.clear();
    }

    private void watch(Path dir) {
        WatchKey key = Watcher.register(dir, this);
        if (key != null) keys.add(key);
    }

    // --- on the watcher thread --------------------------------------------

    /** A file event under {@code dir}: queue the file, or watch and scan a directory that appeared. */
    private synchronized void onEvent(Path dir, WatchEvent<?> event) {
        if (!running || event.kind() == StandardWatchEventKinds.OVERFLOW) return;
        if (!(event.context() instanceof Path name)) return;
        Path child = dir.resolve(name);
        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
            pending.remove(child);
            processed.remove(child);
            return;
        }
        Optional<BasicFileAttributes> attrs = PathUtil.stat(child);
        if (attrs.isEmpty()) return;
        if (attrs.get().isDirectory()) {
            // Its files may have landed before the watch did, so the new subtree is read once.
            try {
                PathUtil.forEachEntry(child, d -> false, (path, a) -> {
                    if (a.isDirectory()) watch(path);
                    else offer(path, a);
                    return true;
                });
            } catch (IOException ignored) {
                // finish ingests what this scan could not read.
            }
            return;
        }
        offer(child, attrs.get());
    }

    private void offer(Path file, BasicFileAttributes attrs) {
        if (!attrs.isRegularFile() || BuildStamps.isStampFile(file.getFileName().toString())) return;
        // A rewrite of an ingested file is ingested again.
        processed.remove(file);
        if (!pending.containsKey(file)) pending.put(file, null);
    }

    /**
     * One tick over the files still settling: a file whose {@code (size, mtime)} matches what the
     * last tick saw has been quiet for a whole tick and is hashed; any other records what it saw.
     */
    private synchronized void settle() {
        if (!running) return;
        for (Iterator<Map.Entry<Path, @Nullable Snapshot>> it =
                        pending.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<Path, @Nullable Snapshot> entry = it.next();
            Path file = entry.getKey();
            Optional<BasicFileAttributes> attrs = PathUtil.stat(file);
            if (attrs.isEmpty() || !attrs.get().isRegularFile()) {
                it.remove();
                continue;
            }
            Snapshot now = new Snapshot(
                    attrs.get().size(), attrs.get().lastModifiedTime().toMillis());
            if (!now.equals(entry.getValue())) {
                entry.setValue(now);
                continue;
            }
            it.remove();
            try {
                processed.put(file, hashAndCopy(file));
            } catch (IOException ignored) {
                // The file vanished or could not be read; finish picks it up.
            }
        }
    }

    private synchronized boolean settling() {
        return running && !pending.isEmpty();
    }

    /**
     * Streamed SHA-256 then {@link Cas#putFile} <em>copy</em> into CAS — never a hard link, because
     * compile trees rewrite class files in place and a link would poison the blob.
     */
    private String hashAndCopy(Path file) throws IOException {
        String hex = Hashing.sha256Hex(file);
        cas.putFile(file, hex);
        return hex;
    }

    private record Snapshot(long size, long mtime) {}

    /**
     * The process's one watch service and the one daemon thread that drains it for every prewriter.
     * Without a watch service (a file system that offers none) no prewriting happens and {@link
     * #finish} ingests the whole tree.
     */
    private static final class Watcher {

        static final @Nullable WatchService SERVICE = open();

        static final Set<CasPrewriter> ACTIVE = ConcurrentHashMap.newKeySet();

        /** Which prewriters a key serves: two output trees may share a directory. */
        private static final Map<WatchKey, List<CasPrewriter>> OWNERS = new ConcurrentHashMap<>();

        private static final Object LOCK = new Object();

        private static @Nullable WatchService open() {
            WatchService service;
            try {
                service = FileSystems.getDefault().newWatchService();
            } catch (IOException | UnsupportedOperationException e) {
                return null;
            }
            // Engine-lifetime: drains file events and hashes settled files; reads no session.
            Thread.ofPlatform().daemon().name(THREAD_NAME).start(() -> drain(service));
            return service;
        }

        static @Nullable WatchKey register(Path dir, CasPrewriter owner) {
            WatchService service = SERVICE;
            if (service == null) return null;
            synchronized (LOCK) {
                try {
                    WatchKey key = dir.register(
                            service,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                    OWNERS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                            .add(owner);
                    return key;
                } catch (IOException | ClosedWatchServiceException e) {
                    // Out of watches, or the directory went away: finish covers it.
                    return null;
                }
            }
        }

        static void release(WatchKey key, CasPrewriter owner) {
            synchronized (LOCK) {
                List<CasPrewriter> owners = OWNERS.get(key);
                if (owners == null) return;
                owners.remove(owner);
                if (owners.isEmpty()) {
                    OWNERS.remove(key);
                    key.cancel();
                }
            }
        }

        private static void drain(WatchService service) {
            long lastTick = Clock.SYSTEM.nanos();
            long tick = TimeUnit.MILLISECONDS.toNanos(SETTLE_MILLIS);
            while (true) {
                try {
                    boolean settling = ACTIVE.stream().anyMatch(CasPrewriter::settling);
                    long wait = Math.max(0, lastTick + tick - Clock.SYSTEM.nanos());
                    WatchKey key = settling ? service.poll(wait, TimeUnit.NANOSECONDS) : service.take();
                    for (; key != null; key = service.poll()) dispatch(key);
                    if (Clock.SYSTEM.nanos() - lastTick >= tick) {
                        for (CasPrewriter p : ACTIVE) p.settle();
                        lastTick = Clock.SYSTEM.nanos();
                    }
                } catch (InterruptedException | ClosedWatchServiceException e) {
                    return;
                } catch (RuntimeException e) {
                    // One prewriter's failure must not stop the others' ingest; finish is authoritative.
                }
            }
        }

        private static void dispatch(WatchKey key) {
            List<CasPrewriter> owners;
            // Taken so a key's first events wait until register has recorded who it serves.
            synchronized (LOCK) {
                owners = OWNERS.getOrDefault(key, List.of());
            }
            List<WatchEvent<?>> events = key.pollEvents();
            key.reset();
            if (!(key.watchable() instanceof Path dir)) return;
            for (CasPrewriter owner : owners) {
                for (WatchEvent<?> event : events) owner.onEvent(dir, event);
            }
        }
    }
}
