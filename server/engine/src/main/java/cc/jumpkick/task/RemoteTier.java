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
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
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

    /** True when this build uploads what it stores. */
    public boolean writes() {
        return writes && !writesOff.get();
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
            for (String sha : backend.missing(blobs.keySet())) {
                Path blob = blobs.get(sha);
                if (blob == null) continue;
                backend.putBlob(sha, blob);
                io.cacheUp(IoLedger.sizeOf(blob));
            }
            backend.putRecord(key, sharedText);
            io.cacheUp(sharedText.length());
            uploaded.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            if (writesOff.compareAndSet(false, true)) {
                Log.warn("jk: remote cache " + backend.name() + " refused an upload (" + e.getMessage()
                        + "); uploading nothing more this build");
            }
        }
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
        List<String> ask = new ArrayList<>();
        for (String key : keys) {
            if (key != null && !key.isBlank() && !found.containsKey(key) && !absent.contains(key)) ask.add(key);
        }
        if (ask.isEmpty() || off.get()) return;
        Map<String, String> answer;
        try {
            answer = backend.records(new CacheBackend.Lookup(ask, platform));
        } catch (IOException | RuntimeException e) {
            failed("lookup", e);
            return;
        }
        for (String key : ask) {
            String text = answer.get(key);
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
        String known = found.get(key);
        if (known != null || absent.contains(key) || off.get()) return known;
        Batch batch;
        boolean leads;
        synchronized (batches) {
            leads = open == null || open.keys.size() >= MAX_BATCH;
            if (leads) open = new Batch();
            batch = Objects.requireNonNull(open);
            batch.keys.add(key);
        }
        if (leads) {
            LockSupport.parkNanos(BATCH_WINDOW_NANOS);
            List<String> ask;
            synchronized (batches) {
                if (open == batch) open = null;
                ask = List.copyOf(batch.keys);
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
        private final Set<String> keys = new LinkedHashSet<>();
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
     * Bring every blob of {@code digests} into {@code into}, each verified against its digest as it
     * lands. False when any could not be fetched, which is a miss for the record that named it.
     */
    boolean fetch(Collection<String> digests, Cas into) {
        if (off.get()) return false;
        for (String sha : digests) {
            try (InputStream in = backend.open(sha)) {
                Cas.Stored stored = into.putVerified(in, sha);
                bytesDown.addAndGet(stored.size());
                SessionContext.current().io().cacheDown(stored.size());
            } catch (Cas.DigestMismatch tampered) {
                Log.warn("jk: remote cache blob refused", "digest", sha, "cause", tampered.getMessage());
                return false;
            } catch (IOException | RuntimeException e) {
                failed("fetch", e);
                return false;
            }
        }
        return true;
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
