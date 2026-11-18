// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.Nullable;

/**
 * A second {@link CacheBackend} behind the local action cache, for one build. It remembers what
 * each key's lookup answered, so a key costs at most one question per build however many steps
 * and forecasts ask it, and a batch ({@link #prefetch}) answers every later single ask from memory.
 *
 * <p>A backend that fails is off for the rest of the build: the first failure warns once, on the
 * step that met it, and every later ask is a miss without a call. The build goes on locally;
 * nothing the backend does fails it.
 */
public final class RemoteTier {

    /** The warning a build gets, once, when its remote cache stops answering. */
    public static final String UNREACHABLE = "remote-cache-unreachable";

    private final CacheBackend backend;
    private final String platform;
    private final Map<String, String> found = new ConcurrentHashMap<>();
    private final Set<String> absent = ConcurrentHashMap.newKeySet();
    private final Map<String, List<CacheBackend.Candidate>> byReads = new ConcurrentHashMap<>();
    private final Map<String, CacheBackend.Reads> readLists = new ConcurrentHashMap<>();
    private final AtomicBoolean off = new AtomicBoolean();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong bytesDown = new AtomicLong();
    private final AtomicLong uploaded = new AtomicLong();
    private final boolean writes;
    private final AtomicBoolean writesOff = new AtomicBoolean();
    private final Queue<Future<?>> uploads = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drained = new AtomicBoolean();

    /** A tier this build only reads from. */
    public RemoteTier(CacheBackend backend) {
        this(backend, CachePlatform.current(), false);
    }

    /**
     * A tier this build reads from and, when {@code writes}, uploads its shareable records to,
     * behind the build ({@link #offer}, {@link #drain}).
     */
    public RemoteTier(CacheBackend backend, boolean writes) {
        this(backend, CachePlatform.current(), writes);
    }

    RemoteTier(CacheBackend backend, String platform) {
        this(backend, platform, false);
    }

    RemoteTier(CacheBackend backend, String platform, boolean writes) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.writes = writes;
    }

    /**
     * True when this build uploads what it stores: it was built to write, no upload has failed, and
     * the backend says this caller may write (a read-only token never sends a record).
     */
    public boolean writes() {
        return writes && !writesOff.get() && backendWrites();
    }

    private volatile @Nullable Boolean backendWrites;

    /** Whether the backend lets this caller write, asked once per build. */
    private boolean backendWrites() {
        Boolean known = backendWrites;
        if (known == null) backendWrites = known = backend.writable();
        return known;
    }

    /** Records this build uploaded. */
    public long uploaded() {
        return uploaded.get();
    }

    /**
     * Queue {@code sharedText}, the form of record {@code key} that may leave the machine, for
     * upload with its blobs ({@code blobs}, digest to the local file holding it): the blobs the
     * backend lacks first, then the record, so the backend never holds a record without its
     * payload. Returns at once. A failed upload stops this build's uploads with one warning in the
     * engine log; reads go on.
     */
    void offer(String key, String sharedText, Map<String, Path> blobs) {
        if (!writes() || drained.get()) return;
        IoLedger io = SessionContext.current().io();
        uploads.add(JkThreads.platform().submit(() -> upload(key, sharedText, blobs, io)));
    }

    private void upload(String key, String sharedText, Map<String, Path> blobs, IoLedger io) {
        if (!writes()) return;
        try {
            Map<String, Long> sizes = new LinkedHashMap<>();
            for (Map.Entry<String, Path> e : blobs.entrySet()) sizes.put(e.getKey(), IoLedger.sizeOf(e.getValue()));
            putBlobs(backend.missing(sizes), blobs, io);
            CacheBackend.Reads reads = readLists.get(key);
            try {
                backend.putRecord(key, sharedText, reads);
            } catch (CacheBackend.BlobsMissing lacking) {
                // Gone from the store between the question and the put (a sweep): send them, once.
                putBlobs(lacking.missing(), blobs, io);
                backend.putRecord(key, sharedText, reads);
            }
            io.cacheUp(sharedText.length());
            uploaded.incrementAndGet();
        } catch (CacheBackend.Skipped refused) {
            // This record is not one the store takes; the others still go.
            Log.debug(
                    "jk: remote cache " + backend.name() + " did not take record " + key + ": " + refused.getMessage());
        } catch (IOException | RuntimeException e) {
            if (writesOff.compareAndSet(false, true)) {
                Log.warn("jk: remote cache " + backend.name() + " refused an upload (" + e.getMessage()
                        + "); uploading nothing more this build");
            }
        }
    }

    /**
     * Note that the result about to be stored under {@code key} folded in the files a suite was found
     * to read: its upload carries {@code reads}, so a host that has not run the suite can find it
     * from the stamp it computes without them ({@link CacheBackend.Reads#base}).
     */
    public void noteReads(String key, CacheBackend.Reads reads) {
        if (writes()) readLists.put(key, reads);
    }

    /**
     * The running step was offered a result another host stored under its base, whose key its own
     * tree does not give, and {@code inputs} names what differs ({@code x:proc:git}): the step's
     * miss names it when it runs with no record of its own to compare ({@link StepCacheNotes}).
     */
    public static void candidateDiffered(String inputs) {
        StepCacheNotes.differed(inputs);
    }

    /**
     * The results the backend holds under a read list whose base is {@code base}, a key it holds no
     * record of, best first: asked with {@code base}'s lookup (in one round trip with it, or from
     * what an earlier batch learned). Empty when the backend holds a record of {@code base} itself,
     * keeps no read lists, or is off.
     */
    public List<CacheBackend.Candidate> candidates(String base, CacheBackend.@Nullable Accept accept) {
        if (record(base, accept) != null) return List.of();
        return byReads.getOrDefault(base, List.of());
    }

    /**
     * Take {@code candidate} as the record of its key for this build: its reader recomputed the
     * key from its own tree and found it equal, so a lookup of that key needs no round trip.
     */
    public void take(CacheBackend.Candidate candidate) {
        absent.remove(candidate.key());
        found.put(candidate.key(), candidate.recordText());
    }

    /** Send the blobs of {@code digests} that {@code blobs} holds a file for. */
    private void putBlobs(Collection<String> digests, Map<String, Path> blobs, IoLedger io) throws IOException {
        Map<String, Path> send = new LinkedHashMap<>();
        for (String sha : digests) {
            Path blob = blobs.get(sha);
            if (blob != null) send.put(sha, blob);
        }
        if (send.isEmpty()) return;
        backend.putBlobs(send);
        for (Path blob : send.values()) io.cacheUp(IoLedger.sizeOf(blob));
    }

    /**
     * Wait up to {@code timeout} for every queued upload, then take no more. True when they all
     * finished: what a build that must leave its results behind (a CI run) waits on at its end.
     */
    public boolean drain(Duration timeout) throws InterruptedException {
        drained.set(true);
        long deadline = Clock.SYSTEM.nanos() + timeout.toNanos();
        for (Future<?> f; (f = uploads.poll()) != null; ) {
            try {
                f.get(Math.max(0, deadline - Clock.SYSTEM.nanos()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException late) {
                return false;
            } catch (ExecutionException failed) {
                // upload() reports its own failure
            }
        }
        return true;
    }

    /** The backend's name, for messages. */
    public String name() {
        return backend.name();
    }

    /** The platform this build asks as ({@link CachePlatform}), which a record's target must name. */
    public String platform() {
        return platform;
    }

    /** False once the backend has failed in this build. */
    public boolean reachable() {
        return !off.get();
    }

    /** Records this build restored or replayed from the backend. */
    public long hits() {
        return hits.get();
    }

    /** Blob bytes this build fetched from the backend. */
    public long bytesDown() {
        return bytesDown.get();
    }

    /**
     * Ask the backend about every key of {@code keys} not yet asked, in one call. Later single asks
     * for any of them answer from what this learned.
     */
    public void prefetch(Collection<String> keys) {
        Map<String, CacheBackend.@Nullable Accept> asks = new LinkedHashMap<>();
        for (String key : keys) asks.put(key, null);
        prefetch(asks);
    }

    /**
     * As {@link #prefetch(Collection)}, each key with the toolchains its reader takes ({@link
     * CacheBackend.Accept}, null for any).
     */
    void prefetch(Map<String, CacheBackend.@Nullable Accept> keys) {
        List<String> ask = new ArrayList<>();
        Map<String, CacheBackend.Accept> accept = new LinkedHashMap<>();
        for (Map.Entry<String, CacheBackend.@Nullable Accept> e : keys.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank() || found.containsKey(key) || absent.contains(key)) continue;
            ask.add(key);
            if (e.getValue() != null) accept.put(key, e.getValue());
        }
        if (ask.isEmpty() || off.get()) return;
        CacheBackend.Answer answer;
        try {
            answer = backend.lookup(new CacheBackend.Lookup(ask, platform, accept));
        } catch (IOException | RuntimeException e) {
            failed("lookup", e);
            return;
        }
        answer.byReads().forEach((base, candidates) -> byReads.put(base, List.copyOf(candidates)));
        for (String key : ask) {
            String text = answer.records().get(key);
            if (text != null) found.put(key, text);
            else absent.add(key);
        }
    }

    /**
     * The record text the backend holds for {@code key}, or null when it has none or is off.
     *
     * <p>Asks that arrive together share one lookup: the first unknown key opens a batch and waits
     * {@link #BATCH_WINDOW_NANOS} for others, so the forecast's lanes, which price a wave of
     * independent modules at once, cost one round trip per wave rather than one per key.
     */
    @Nullable
    String record(String key) {
        return record(key, null);
    }

    /**
     * As {@link #record(String)}, telling the backend which toolchains' records the reader takes
     * ({@code accept}, null for any), so a store holding one per JDK sends the one this reader would
     * choose. The reader still checks the record it gets against its own policy.
     */
    @Nullable
    String record(String key, CacheBackend.@Nullable Accept accept) {
        String known = found.get(key);
        if (known != null || absent.contains(key) || off.get()) return known;
        Batch batch;
        boolean leads;
        synchronized (batches) {
            leads = open == null || open.keys.size() >= MAX_BATCH;
            if (leads) open = new Batch();
            batch = Objects.requireNonNull(open);
            batch.keys.putIfAbsent(key, accept);
        }
        if (leads) {
            LockSupport.parkNanos(BATCH_WINDOW_NANOS);
            Map<String, CacheBackend.@Nullable Accept> ask;
            synchronized (batches) {
                if (open == batch) open = null;
                ask = new LinkedHashMap<>(batch.keys);
            }
            try {
                prefetch(ask);
            } finally {
                batch.done.complete(null);
            }
        } else {
            try {
                batch.done.get(BATCH_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException | TimeoutException e) {
                return null;
            }
        }
        return found.get(key);
    }

    /** How long the first ask of a batch waits for others: short beside a round trip, long beside a lane's next ask. */
    static final long BATCH_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    /** Keys one lookup carries at most; a fuller batch opens the next. */
    static final int MAX_BATCH = 500;

    /** How long a key waits for the batch it joined before it reads as a miss. */
    private static final long BATCH_WAIT_SECONDS = 30;

    private final Object batches = new Object();
    private @Nullable Batch open;

    /** Keys asked together, and when their lookup has answered. */
    private static final class Batch {
        private final Map<String, CacheBackend.@Nullable Accept> keys = new LinkedHashMap<>();
        private final CompletableFuture<@Nullable Void> done = new CompletableFuture<>();
    }

    /**
     * Forget {@code key}'s record for this build: this machine refused it, or it is now local. The
     * backend keeps it; a refusal is a miss here, never a deletion there.
     */
    void forget(String key) {
        if (found.remove(key) != null) absent.add(key);
    }

    /**
     * Bring the blobs of {@code record} named by {@code digests} into {@code into}, each verified
     * against its digest as it lands. False when any could not be fetched, which is a miss for the
     * record: a blob whose bytes do not match its digest, or that the store no longer holds, is that
     * record's miss alone; a store that does not answer is off for the build.
     */
    boolean fetch(ActionCache.ActionRecord record, Set<String> digests, Cas into) {
        if (off.get()) return false;
        Map<String, Long> blobs = sizes(record, digests);
        IoLedger io = SessionContext.current().io();
        try {
            backend.fetch(blobs, (sha, in) -> {
                Cas.Stored stored = into.putVerified(in, sha);
                bytesDown.addAndGet(stored.size());
                io.cacheDown(stored.size());
            });
        } catch (Cas.DigestMismatch tampered) {
            Log.warn("jk: remote cache blob refused", "cause", tampered.getMessage());
            return false;
        } catch (NoSuchFileException gone) {
            Log.debug("jk: remote cache " + backend.name() + " no longer holds blob " + gone.getFile());
            return false;
        } catch (IOException | RuntimeException e) {
            failed("fetch", e);
            return false;
        }
        return true;
    }

    /** Each of {@code digests} with the byte count {@code record} states for it; {@code -1} where it states none. */
    private static Map<String, Long> sizes(ActionCache.ActionRecord record, Set<String> digests) {
        Map<String, Long> out = new TreeMap<>();
        for (Map.Entry<String, String> e : record.outputs().entrySet()) {
            if (!digests.contains(e.getValue())) continue;
            Long size = record.meta().sizes().get(e.getKey());
            out.merge(e.getValue(), size == null ? -1L : size, Math::max);
        }
        for (String sha : digests) out.putIfAbsent(sha, -1L);
        return out;
    }

    /** Count one record restored or replayed from the backend. */
    void hit() {
        hits.incrementAndGet();
    }

    /** Turn the backend off for the rest of the build, warning once on the step that met the failure. */
    private void failed(String what, Exception e) {
        if (!off.compareAndSet(false, true)) return;
        String message = "remote cache " + backend.name() + " did not answer (" + what + ": " + e.getMessage()
                + "); building locally for the rest of this build";
        Log.warn("jk: " + message);
        // On the step that met it, or, met by the forecast before any step ran, on the run.
        TaskContext ctx = StepScope.current();
        if (ctx != null) ctx.warn(UNREACHABLE, message);
        else RunNotices.warnOnce(UNREACHABLE, () -> "jk: " + message);
    }
}
