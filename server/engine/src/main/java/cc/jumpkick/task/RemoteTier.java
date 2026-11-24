// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.IoLedger;
import cc.jumpkick.config.RunNotices;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        this(backend, platform, writes, BACKOFF);
    }

    /** As {@link #RemoteTier(CacheBackend, String, boolean)}, waiting {@code backoff} before an upload's first retry. */
    RemoteTier(CacheBackend backend, String platform, boolean writes, Duration backoff) {
        this(backend, platform, writes, backoff, BATCH_WINDOW);
    }

    /** A tier only read from, whose first unknown key waits {@code batchWindow} for others to join its lookup. */
    RemoteTier(CacheBackend backend, String platform, Duration batchWindow) {
        this(backend, platform, false, BACKOFF, batchWindow);
    }

    private RemoteTier(CacheBackend backend, String platform, boolean writes, Duration backoff, Duration batchWindow) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.writes = writes;
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.batchWindowNanos = batchWindow.toNanos();
    }

    /** The wait before an upload's first retry; each later one waits four times the last. */
    static final Duration BACKOFF = Duration.ofMillis(500);

    private final Duration backoff;

    /**
     * True when this build uploads what it stores: it was built to write, no upload has failed, and
     * the backend says this caller may write (a read-only token never sends a record).
     */
    public boolean writes() {
        return writes && !writesOff.get() && backendWrites();
    }

    /**
     * True when this build offers its records to the tier: it was built to write and the backend
     * lets this caller write. Still true after the uploads stopped, so what the build stores after
     * that is counted as not stored.
     */
    public boolean offers() {
        return writes && backendWrites();
    }

    private volatile @Nullable Boolean backendWrites;

    /**
     * True when this build offers {@code key}'s record ({@link #offers()}) and the tier takes it: a
     * tier that keeps shared forms alone ({@link #sharedFormsOnly}) takes only a key noted with one.
     */
    public boolean offers(String key) {
        return writes() && (!sharedFormsOnly() || sharedForm(key) != null);
    }

    /**
     * True when this tier asks and stores shared forms alone ({@link #shareAs}): it is the request's
     * own action cache, under a team's seed with no remote cache ({@link SeedFormStore}), which has
     * just missed every other key and keeps every other record already.
     */
    public boolean sharedFormsOnly() {
        return backend instanceof SeedFormStore;
    }

    /** Keep the shared forms in {@code local} when this tier is the request's own action cache ({@link SeedFormStore}). */
    void bindLocal(LocalCacheBackend local) {
        if (backend instanceof SeedFormStore store) store.bind(local);
    }

    /** Whether the backend lets this caller write, asked once per build. */
    private boolean backendWrites() {
        Boolean known = backendWrites;
        if (known == null) backendWrites = known = backend.writable();
        return known;
    }

    private volatile CacheSeeds.Source seedSource = CacheSeeds.Source.ISSUED;
    private final Object seedLock = new Object();
    private boolean seedKnown;
    private @Nullable KeySeed seed;

    private volatile boolean credentialsLocal;

    /**
     * Take the key seed from {@code source} ({@link CacheSeeds#resolve}) rather than the backend's
     * issued one; {@code credentialsLocal} keeps a result keyed on a credential's value on this host
     * even under it ({@link CacheSeeds#credentialsLocal}).
     */
    public RemoteTier seededBy(CacheSeeds.Source source, boolean credentialsLocal) {
        seedSource = Objects.requireNonNull(source, "source");
        this.credentialsLocal = credentialsLocal;
        return this;
    }

    /** Whether a result keyed on a credential's value stays on this host even under the seed. */
    public boolean credentialsLocal() {
        return credentialsLocal;
    }

    /** The warning a build gets, once, when its remote cache did not hand out its key seed. */
    public static final String SEED_UNAVAILABLE = "remote-cache-key-seed";

    /**
     * The key this build's suite keys take their shared form under ({@link KeySeed}), or null for
     * none: no seed set or issued, the tier off, or the cache did not hand it out (said once, the
     * build goes on and such results stay here). Settled once per build, when a key first needs it.
     */
    public @Nullable KeySeed keySeed() {
        synchronized (seedLock) {
            if (!seedKnown) {
                seedKnown = true;
                try {
                    seed = off.get() ? null : CacheSeeds.keySeed(seedSource, backend);
                } catch (IOException | RuntimeException e) {
                    seed = null;
                    warn(
                            SEED_UNAVAILABLE,
                            "remote cache " + backend.name() + " did not hand out its key seed (" + e.getMessage()
                                    + "); results that read your environment stay on this machine this build");
                }
            }
            return seed;
        }
    }

    private final Map<String, String> sharedForms = new ConcurrentHashMap<>();

    /**
     * Ask and store {@code local}'s record here by {@code shared}, the form of its key every host
     * holding the build's seed takes alike ({@code SuiteEnvReads.sharedTokens}): a lookup asks the
     * backend for {@code shared} and hands the record back as {@code local}'s, and an upload of
     * {@code local}'s record goes under {@code shared} ({@code ActionCache}). The local key, and so
     * this host's own replay, never depends on the seed. {@code shared} equal to {@code local} notes
     * a key on this home's secret with no shared form: its record is uploaded nowhere.
     */
    public void shareAs(String local, String shared) {
        sharedForms.put(local, shared);
        if (!shared.equals(local)) sharedKeys.add(shared);
    }

    /** The shared forms noted ({@link #shareAs}) other than a key's own: all a {@link #sharedFormsOnly} tier asks. */
    private final Set<String> sharedKeys = ConcurrentHashMap.newKeySet();

    private volatile boolean holdsParts;

    /**
     * Note {@code shared} as the shared form of {@code local}, a part of a step key ({@link
     * EnvKeyText}): a sealed environment value, or the digest of a text that held one. {@code shared}
     * equal to {@code local} notes a part with no shared form, which keeps every key holding it here.
     */
    void notePart(String local, String shared) {
        holdsParts = true;
        shareAs(local, shared);
    }

    /** Whether this build noted a part ({@link #notePart}): until it has, no key text needs a scan. */
    boolean holdsParts() {
        return holdsParts;
    }

    /** What {@code local} was noted as ({@link #shareAs}), itself for no shared form, or null for nothing. */
    @Nullable
    String formOf(String local) {
        return sharedForms.get(local);
    }

    /** Whether this build noted what {@code local}'s shared form is ({@link #shareAs}), itself included. */
    public boolean noted(String local) {
        return sharedForms.containsKey(local);
    }

    /** The shared form of {@code local} this build noted ({@link #shareAs}), or null when it noted none other than itself. */
    public @Nullable String sharedForm(String local) {
        String shared = sharedForms.get(local);
        return shared == null || shared.equals(local) ? null : shared;
    }

    /** {@code key} as the backend is asked it and stores it: its shared form when it has one. */
    public String asShared(String key) {
        return sharedForms.getOrDefault(key, key);
    }

    /** Records this build uploaded. */
    public long uploaded() {
        return uploaded.get();
    }

    /**
     * Queue {@code sharedText}, the form of record {@code key} that may leave the machine, for
     * upload with its blobs ({@code blobs}, digest to the local file holding it): the blobs the
     * backend lacks first, then the record, so the backend never holds a record without its
     * payload. Returns at once. An upload that fails is that record's alone: one the store could not
     * take now is tried again behind the build ({@link #ATTEMPTS}, within {@link #RETRY_BUDGET}),
     * the others go on, and the run says at its end what was not stored ({@link #notStoredLine}).
     */
    void offer(String key, String sharedText, Map<String, Path> blobs) {
        if (!writes || drained.get()) return;
        IoLedger io = SessionContext.current().io();
        String stopped = stoppedBecause;
        if (stopped != null) {
            notStored(io, "uploads stopped: " + stopped, 0);
            return;
        }
        if (!offers()) return;
        ledger = io;
        uploads.add(JkThreads.platform().submit(() -> upload(key, sharedText, blobs, io)));
    }

    /**
     * Queue {@code record}, stored here, for upload in the form that may leave the machine, with the
     * blobs its outputs name ({@code blobPath}, digest to the local file), when this tier takes
     * uploads (one it stopped counts what it was not given) and the record is one this jk would
     * replay ({@link ActionRecordText#shareable}). A suite result keyed on this home's secret goes
     * under its key's shared form when the build's seed gives one ({@link #shareAs}), where no host
     * mark holds it back, and nowhere when it was noted as its own. Nothing waits for it here.
     */
    void offer(ActionCache.ActionRecord record, Function<String, Path> blobPath) {
        if (!offers()) return;
        String key = Objects.requireNonNull(record.actionKey());
        // Noted as its own shared form: keyed on this home's secret with no seed to share it under.
        if (key.equals(sharedForms.get(key))) return;
        String shared = sharedForm(key);
        if (shared == null && sharedFormsOnly()) return;
        Map<String, String> inputs = record.inputs();
        if (shared != null) {
            inputs = new TreeMap<>(inputs);
            inputs.remove(ActionRecordText.HOST_KEYED_INPUT);
        }
        ActionRecordText.Parsed parsed = new ActionRecordText.Parsed(
                Objects.requireNonNull(record.taskId()),
                shared != null ? shared : key,
                inputs,
                record.outputs(),
                record.executables(),
                record.said(),
                record.meta());
        if (!ActionRecordText.shareable(parsed)) return;
        Map<String, Path> blobs = new TreeMap<>();
        for (String sha : record.outputs().values()) {
            if (ActionRecordText.isSha256(sha)) blobs.put(sha, blobPath.apply(sha));
        }
        offer(parsed.actionKey(), ActionRecordText.renderShared(parsed), blobs);
    }

    private volatile @Nullable IoLedger ledger;

    /** The ledger of the run that offered this tier its uploads, or null when it offered none. */
    @Nullable
    IoLedger ledger() {
        return ledger;
    }

    /** Tries one upload gets: the first and up to three more for a failure worth retrying. */
    static final int ATTEMPTS = 4;

    /** Retries one build spends at most, across its uploads: a store that keeps failing is not retried for long. */
    static final int RETRY_BUDGET = 64;

    /** Uploads in a row that fail every try before the build stops uploading: the store is down. */
    static final int GIVE_UP_AFTER = 3;

    private final AtomicInteger retriesLeft = new AtomicInteger(RETRY_BUDGET);
    private final AtomicInteger failedInARow = new AtomicInteger();
    private final Map<String, AtomicInteger> notStored = new ConcurrentHashMap<>();
    private final AtomicInteger recordsNotStored = new AtomicInteger();
    private final AtomicInteger blobsNotStored = new AtomicInteger();
    private volatile @Nullable String stoppedBecause;

    private void upload(String key, String sharedText, Map<String, Path> blobs, IoLedger io) {
        String stopped = stoppedBecause;
        if (stopped != null) {
            notStored(io, "uploads stopped: " + stopped, 0);
            return;
        }
        int[] sending = {0};
        for (int attempt = 1; ; attempt++) {
            try {
                uploadOnce(key, sharedText, blobs, io, sending);
                failedInARow.set(0);
                return;
            } catch (CacheBackend.Skipped refused) {
                // This record is not one the store takes; the others still go.
                Log.debug("jk: remote cache " + backend.name() + " did not take record " + key + ": "
                        + refused.getMessage());
                notStored(io, reason(refused), sending[0]);
                return;
            } catch (CacheBackend.WritesRefused refused) {
                stop(reason(refused));
                notStored(io, reason(refused), sending[0]);
                return;
            } catch (IOException e) {
                if (attempt < ATTEMPTS && stoppedBecause == null && retriesLeft.getAndDecrement() > 0) {
                    Log.debug("jk: remote cache " + backend.name() + " upload of " + key + " failed (" + e.getMessage()
                            + "); trying again");
                    pause(attempt);
                    continue;
                }
                Log.debug("jk: remote cache " + backend.name() + " did not store " + key + ": " + e.getMessage());
                notStored(io, reason(e) + (attempt > 1 ? " after " + attempt + " tries" : ""), sending[0]);
                if (failedInARow.incrementAndGet() >= GIVE_UP_AFTER)
                    stop(GIVE_UP_AFTER + " uploads in a row failed (" + reason(e) + ")");
                return;
            } catch (RuntimeException e) {
                Log.debug("jk: remote cache " + backend.name() + " upload of " + key + " threw", e);
                notStored(io, e.getClass().getSimpleName(), sending[0]);
                return;
            }
        }
    }

    private void uploadOnce(String key, String sharedText, Map<String, Path> blobs, IoLedger io, int[] sending)
            throws IOException {
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : blobs.entrySet()) sizes.put(e.getKey(), IoLedger.sizeOf(e.getValue()));
        putBlobs(backend.missing(sizes), blobs, io, sending);
        CacheBackend.Reads reads = readLists.get(key);
        try {
            backend.putRecord(key, sharedText, reads);
        } catch (CacheBackend.BlobsMissing lacking) {
            // Gone from the store between the question and the put (a sweep): send them, once.
            putBlobs(lacking.missing(), blobs, io, sending);
            backend.putRecord(key, sharedText, reads);
        }
        io.cacheUp(sharedText.length());
        uploaded.incrementAndGet();
    }

    /** Wait before try {@code attempt + 1}: {@link #backoff} times 1, 4, 16, each with up to a quarter more. */
    private void pause(int attempt) {
        long nanos = backoff.toNanos() << (2 * (attempt - 1));
        nanos += (long) (nanos * 0.25 * ThreadLocalRandom.current().nextDouble());
        LockSupport.parkNanos(nanos);
    }

    private void stop(String why) {
        if (stoppedBecause != null) return;
        stoppedBecause = why;
        writesOff.set(true);
        Log.warn("jk: remote cache " + backend.name() + ": " + why + "; uploading nothing more this build");
    }

    private void notStored(IoLedger io, String reason, int blobs) {
        recordsNotStored.incrementAndGet();
        blobsNotStored.addAndGet(blobs);
        notStored.computeIfAbsent(reason, r -> new AtomicInteger()).incrementAndGet();
        io.cacheNotStored(1, blobs);
    }

    /**
     * A failure as the run's closing line names it: the store's status and code ({@code 503
     * unavailable}) when it gave them, else the kind of failure ({@code HttpTimeoutException}).
     */
    static String reason(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        Matcher status = STATUS.matcher(m);
        if (status.find()) return status.group(1);
        return e.getClass().getSimpleName();
    }

    private static final Pattern STATUS = Pattern.compile(": (\\d{3}(?: from the (?:store|service)| [a-z_]+))");

    /** Records offered and not stored, by reason; empty when every one landed. */
    public Map<String, Integer> notStored() {
        Map<String, Integer> out = new TreeMap<>();
        notStored.forEach((reason, n) -> out.put(reason, n.get()));
        return out;
    }

    /**
     * The run's one closing line when an upload did not land, else null: {@code remote cache dojo: 2
     * records and 5 blobs not stored (1 503 unavailable after 4 tries, 1 422 invalid)}.
     */
    public @Nullable String notStoredLine() {
        int records = recordsNotStored.get();
        if (records == 0) return null;
        int blobs = blobsNotStored.get();
        StringBuilder why = new StringBuilder();
        notStored().forEach((reason, n) -> why.append(why.isEmpty() ? "" : ", ")
                .append(n)
                .append(' ')
                .append(reason));
        return "remote cache " + backend.name() + ": " + records + (records == 1 ? " record" : " records")
                + (blobs == 0 ? "" : " and " + blobs + (blobs == 1 ? " blob" : " blobs")) + " not stored (" + why
                + ")";
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
        return byReads.getOrDefault(asShared(base), List.of());
    }

    /**
     * Take {@code candidate} as the record of its key for this build: its reader recomputed the
     * key from its own tree and found it equal, so a lookup of that key needs no round trip.
     */
    public void take(CacheBackend.Candidate candidate) {
        absent.remove(candidate.key());
        found.put(candidate.key(), candidate.recordText());
    }

    /**
     * Send the blobs of {@code digests} that {@code blobs} holds a file for; {@code sending[0]} holds
     * how many are in flight, so a failure knows how many it left unstored.
     */
    private void putBlobs(Collection<String> digests, Map<String, Path> blobs, IoLedger io, int[] sending)
            throws IOException {
        Map<String, Path> send = new LinkedHashMap<>();
        for (String sha : digests) {
            Path blob = blobs.get(sha);
            if (blob != null) send.put(sha, blob);
        }
        if (send.isEmpty()) return;
        sending[0] = send.size();
        backend.putBlobs(send);
        sending[0] = 0;
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
            String key = e.getKey() == null ? null : asShared(e.getKey());
            if (key == null || key.isBlank() || found.containsKey(key) || absent.contains(key)) continue;
            if (sharedFormsOnly() && !sharedKeys.contains(key)) continue;
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
        declined.putAll(answer.declined());
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
     * {@link #BATCH_WINDOW} for others, so the forecast's lanes, which price a wave of
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
        String shared = sharedForm(key);
        if (shared != null) {
            // Asked by its shared form, kept here by the key this host took.
            String text = held(shared, accept);
            return text == null ? null : ActionRecordText.rekeyed(text, shared, key);
        }
        return sharedFormsOnly() ? null : held(key, accept);
    }

    /** The record text the backend holds for {@code key} as asked: {@link #record}'s batch. */
    private @Nullable String held(String key, CacheBackend.@Nullable Accept accept) {
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
            LockSupport.parkNanos(batchWindowNanos);
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
    static final Duration BATCH_WINDOW = Duration.ofMillis(2);

    private final long batchWindowNanos;

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
     * The record text the backend holds for {@code key} that a reader expecting {@code expected}
     * may take, or null. A key whose records were all made on a JDK that reader's policy refuses
     * (the backend said so, or this machine refused the one it sent, {@link #declined(String,
     * String)}) is noted as the step's miss reason, in the step that asks, however often it asks.
     */
    @Nullable
    String recordFor(String key, ToolchainPolicy.@Nullable Expectation expected) {
        if (expected == null) return record(key, (CacheBackend.Accept) null);
        String text = record(key, expected.accept());
        String refused = text == null ? declined.get(asShared(key)) : null;
        if (refused != null) ToolchainChoice.mismatch(key, refused, expected);
        return text;
    }

    /** Forget {@code key}'s record for this build: this machine's policy refused the one made on {@code toolchain}. */
    void declined(String key, @Nullable String toolchain) {
        forget(key);
        if (toolchain != null) declined.put(asShared(key), toolchain);
    }

    private final Map<String, String> declined = new ConcurrentHashMap<>();

    /**
     * Forget {@code key}'s record for this build: this machine refused it, or it is now local. The
     * backend keeps it; a refusal is a miss here, never a deletion there.
     */
    void forget(String key) {
        String asked = asShared(key);
        if (found.remove(asked) != null) absent.add(asked);
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

    /**
     * Note that {@code key}'s record, which the backend served, is now kept on this disk: one hit,
     * no further question about it this build, and every step that reads it here this build is a
     * remote hit ({@link #adoptedThisBuild}).
     */
    void adopted(String key) {
        forget(key);
        hits.incrementAndGet();
        adopted.add(key);
    }

    /**
     * Whether {@code key}'s record came from {@code tier}'s backend in this build: kept on this disk
     * by an earlier lookup (the forecast's, see {@link #adopted}), so a step that reads it here is
     * still the remote's hit. False with no tier or no key.
     */
    static boolean adoptedThisBuild(@Nullable RemoteTier tier, @Nullable String key) {
        return tier != null && key != null && tier.adopted.contains(key);
    }

    private final Set<String> adopted = ConcurrentHashMap.newKeySet();

    /** Turn the backend off for the rest of the build, warning once on the step that met the failure. */
    private void failed(String what, Exception e) {
        if (!off.compareAndSet(false, true)) return;
        warn(
                UNREACHABLE,
                "remote cache " + backend.name() + " did not answer (" + what + ": " + e.getMessage()
                        + "); building locally for the rest of this build");
    }

    /** Say {@code message} as {@code code}, on the step that met it, or, met by the forecast before any step ran, on the run. */
    private static void warn(String code, String message) {
        Log.warn("jk: " + message);
        TaskContext ctx = StepScope.current();
        if (ctx != null) ctx.warn(code, message);
        else RunNotices.warnOnce(code, () -> "jk: " + message);
    }
}
