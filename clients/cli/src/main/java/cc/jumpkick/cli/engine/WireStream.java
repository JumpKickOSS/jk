// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.JobQueuedFrame;
import cc.jumpkick.wire.protocol.JobStartFrame;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The client's read loop over one engine wire stream. Every hosted verb reads its events through
 * here, so the beats that are not about any one verb — discriminating the line, registering the
 * cancel handle, announcing {@code job-start}, and waiting out the engine's finish tail — exist
 * once.
 *
 * <p>Two entry points, mirroring the engine's two dispatch arms. {@link #pumpJob} is for a request
 * the engine runs as a job (its {@code VerbShape.AsyncPlan} / {@code CacheMaint} verbs): it carries
 * a {@code job-start} and a {@code job-finish}, and both are handled here. {@link #pumpRead} is for
 * a request the engine answers inline on the connection thread ({@code VerbShape.SyncRead}): no job
 * exists, so there is no handle to register and no finish line that would ever arrive. Choosing the
 * wrong one is not expressible: an inline read has no channel parameter to half-close.
 */
public final class WireStream {

    private WireStream() {}

    /** Receives the facts of an engine {@code job-start} line, already decoded off the wire. */
    @FunctionalInterface
    public interface JobStartListener {
        void jobStarted(long jid, long buildNumber, @Nullable String detailsPath, long etaMs);
    }

    private static volatile @Nullable JobStartListener jobStartListener;

    /**
     * Register the process-wide {@code job-start} observer. The transcript layer registers itself
     * here so this package never names its renderers; a {@code null} listener (or none registered)
     * makes {@code job-start} a plain bookkeeping line.
     */
    public static void onJobStart(@Nullable JobStartListener listener) {
        jobStartListener = listener;
    }

    /**
     * Decodes one wire line. Returning non-{@code null} is the terminal: that value becomes the
     * stream's result and the pump stops. Returning {@code null} asks for the next line, which is
     * also how an unknown type stays a forward-compatible no-op.
     */
    @FunctionalInterface
    interface Decoder<T> {
        @Nullable
        T onLine(String type, String line) throws IOException;
    }

    /**
     * Read a job's stream to its terminal. The job's {@code jid} is registered as this process's
     * cancel handle for as long as the stream is live, so Ctrl-C ({@link
     * EngineClient#cancelBestEffortForInterrupt}) can cancel it by id — the only handle that works
     * for a verb whose journal dir is the cache rather than the user's project, which is every
     * verb that takes no {@code dir} ({@code jk cache prune}, {@code jk tool resolve}, {@code jk
     * tool run <script>}).
     *
     * @param ch the stream's channel, half-closed while waiting for {@code job-finish}; {@code
     *     null} in tests that drive a plain reader
     */
    static <T> T pumpJob(BufferedReader reader, @Nullable SocketChannel ch, Decoder<T> decoder) throws IOException {
        return pump(reader, ch, true, decoder);
    }

    /**
     * Read an inline read-verb's stream to its terminal. The engine serves these on the connection
     * thread and keeps the connection open for the next request, so no job is admitted: nothing to
     * register, and no finish tail to wait for.
     */
    static <T> T pumpRead(BufferedReader reader, Decoder<T> decoder) throws IOException {
        return pump(reader, null, false, decoder);
    }

    private static <T> T pump(BufferedReader reader, @Nullable SocketChannel ch, boolean job, Decoder<T> decoder)
            throws IOException {
        long notedJid = -1;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                if (EngineProtocol.JOB_QUEUED.equals(type)) {
                    // The jid is already the cancel handle: a Ctrl-C while queued dequeues the job.
                    JobQueuedFrame queued = JobQueuedFrame.decode(line);
                    notedJid = queued.jid();
                    ActiveJobs.note(notedJid);
                    CliOutput.err(waitingLine(queued));
                    continue;
                }
                if (EngineProtocol.JOB_START.equals(type)) {
                    JobStartFrame start = JobStartFrame.decode(line);
                    // An absent jid is "none" (-1) here, where the record reads 0.
                    notedJid = Jsonl.has(line, "jid") ? start.jid() : -1;
                    ActiveJobs.note(notedJid);
                    notifyJobStart(start, notedJid);
                    continue;
                }
                T terminal = decoder.onLine(type, line);
                if (terminal != null) {
                    if (job) awaitJobFinish(reader, ch);
                    return terminal;
                }
            }
            throw disconnected();
        } finally {
            // The job is over however the stream ended — a stale jid here would add a 2s cancel
            // RPC to every later Ctrl-C in this process.
            if (notedJid > 0) ActiveJobs.forget(notedJid);
        }
    }

    /**
     * The line a queued job prints. On joining the queue: {@code waiting for engine memory (2 jobs
     * ahead); live: test /home/me/app since 22:36}. On every later report: {@code queued behind 2
     * jobs for 5m, live: test /home/me/app since 22:36}. The live suffix is dropped when the engine
     * named no live job.
     */
    static String waitingLine(JobQueuedFrame queued) {
        int ahead = queued.ahead();
        String live = liveSuffix(queued.live());
        if (queued.waitedMs() <= 0) {
            String position = ahead <= 0
                    ? "waiting for engine memory (next in line)"
                    : "waiting for engine memory (" + ahead + (ahead == 1 ? " job" : " jobs") + " ahead)";
            return live.isEmpty() ? position : position + "; live: " + live;
        }
        String waited =
                "queued behind " + ahead + (ahead == 1 ? " job" : " jobs") + " for " + formatWait(queued.waitedMs());
        return live.isEmpty() ? waited : waited + ", live: " + live;
    }

    /** {@code test /home/me/app since 22:36, build /home/me/lib since 22:40}; {@code ""} when none. */
    private static String liveSuffix(List<JobQueuedFrame.Live> live) {
        List<String> parts = new ArrayList<>();
        for (JobQueuedFrame.Live l : live) parts.add(l.kind() + " " + l.dir() + " since " + wallClock(l.sinceMillis()));
        return String.join(", ", parts);
    }

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static String wallClock(long epochMillis) {
        return WALL_CLOCK.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** {@code 5m} / {@code 1h 02m} / {@code 40s}. */
    static String formatWait(long millis) {
        long s = Math.max(0, millis / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) return h + "h " + String.format("%02d", m) + "m";
        if (m > 0) return m + "m";
        return s + "s";
    }

    /** Hand a decoded {@code job-start} to the registered observer, if any. */
    private static void notifyJobStart(JobStartFrame start, long jid) {
        JobStartListener listener = jobStartListener;
        if (listener == null) return;
        listener.jobStarted(jid, start.buildNumber(), start.detailsPath(), start.etaMs());
    }

    /**
     * Block until the engine says it has stopped writing under the project's {@code target/}
     * ({@link EngineProtocol#JOB_FINISH}), or the stream ends.
     *
     * <p>The verb's terminal is <em>not</em> the end of the engine's work on the tree: a build's
     * preflight memos ({@code target/.jk/preflight/}) and, for every journaled kind, the journal
     * run directory and its {@code target/jk-results.md} copy are written after it. Returning on
     * the terminal hands control back mid-write, so {@code jk build && jk clean} — and any caller
     * that deletes {@code target/} straight after — raced those writers: the delete either tripped
     * over a freshly created temp file ({@code DirectoryNotEmptyException}) or completed and then
     * had {@code target/} recreated under it. Waiting here is the ordering fix; the terminal
     * already carried the outcome, so nothing read past this point can change the result.
     *
     * <p>EOF means the same thing from an engine that died or was killed — the tree is not going
     * to change either way, so it is a normal exit from this wait, not a failure.
     *
     * <p>Half-closing our write side first is what keeps this from being a standoff: the engine's
     * connection thread is parked reading this socket for EOF, and the
     * terminal is our last word on it. The half-close hands it the EOF it needs to move on to the
     * finish tail, while our read side stays open for the line we are waiting for.
     */
    private static void awaitJobFinish(BufferedReader reader, @Nullable SocketChannel ch) {
        if (ch != null) {
            try {
                ch.shutdownOutput();
            } catch (IOException | UnsupportedOperationException ignored) {
                // Not half-closable (or already gone) — the engine still wakes on its own.
            }
        }
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (EngineProtocol.JOB_FINISH.equals(EngineProtocol.typeOf(line))) return;
            }
        } catch (IOException ignored) {
            // Outcome already decided; a broken stream now tells us nothing new.
        }
    }

    /**
     * Bare EOF without a terminal line — unless this process already asked for cancel (Ctrl-C's
     * cooperative token), in which case the disconnect IS the cancel settling.
     *
     * <p>The message states what was observed and nothing else. A stream that ends without its
     * terminal is just as easily an engine that finished in a vocabulary this reader does not end
     * on, and naming a crash nobody checked for sends the reader to look for a corpse that is not
     * there — while the engine is still up and answering {@code jk engine status}.
     */
    private static IOException disconnected() {
        try {
            if (SessionContext.current().cancelled()) return new JobCancelledException();
        } catch (RuntimeException ignored) {
            // no session installed — fall through to the generic message
        }
        return new IOException("jk engine: the engine closed the connection without sending a result; "
                + "run `jk engine status` to see whether it is still running, and check its log for the job");
    }
}
