// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Downloads a plugin makes itself, reported to the run's download board: the rows, bytes and
 * pricing every engine-side download already has. A step that fetches outside the lock (it
 * declared {@link In#repositories()}) opens one {@link Transfer} per body it streams.
 *
 * <p>In the worker the reports are {@code transfer} replies on the plugin protocol. In a JVM the
 * worker forks ({@link TaskExec#java()}) there is no protocol: the same calls print {@link
 * #FORKED_PREFIX} lines, which the worker's {@link TaskExec.ToolRun} lifts into replies and keeps
 * out of the tool's output. Either way, a body's progress is sent at most every {@link
 * #REPORT_EVERY_NANOS} or {@link #REPORT_EVERY_BYTES}, whichever comes first, and its last
 * count always before it finishes.
 */
public final class Transfers {

    /**
     * The marker on a forked JVM's transfer line, {@code ##JKXFER:{json}}: this class writes it and
     * {@link TaskExec.ToolRun} reads it.
     */
    public static final String FORKED_PREFIX = "##JKXFER:";

    /** The reply type every report carries; the engine relays it by this name. */
    public static final String REPLY = "transfer";

    /** A body's progress is reported no more often than this… */
    static final long REPORT_EVERY_NANOS = 100_000_000L;

    /** …unless this many bytes arrived since the last report. */
    static final long REPORT_EVERY_BYTES = 256L * 1024;

    private static final AtomicReference<@Nullable Consumer<String>> REPLIES = new AtomicReference<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final String PID = String.valueOf(ProcessHandle.current().pid());

    private Transfers() {}

    /** One body streaming to disk. Close it however the stream ends; closing twice is harmless. */
    public interface Transfer extends AutoCloseable {

        /** The body began to arrive; {@code totalBytes} is {@code -1} when no length was declared. */
        void started(long totalBytes);

        /** Cumulative bytes of the body read so far. */
        void advanced(long readBytes);

        /** The whole body arrived. */
        void finished();

        @Override
        void close();
    }

    /**
     * Route reports onto the plugin protocol: the worker's harness installs its reply writer.
     * Null uninstalls, and reports print {@link #FORKED_PREFIX} lines again.
     */
    public static void install(@Nullable Consumer<String> replies) {
        REPLIES.set(replies);
    }

    /** A transfer of the body {@code label} names ({@code g:a:v}, a file name). */
    public static Transfer open(String label) {
        return open(label, false);
    }

    /** As {@link #open(String)}, for a dependency's own file (its jar), which the run counts as an artifact. */
    public static Transfer open(String label, boolean artifact) {
        return new Reported(PID + ":" + IDS.incrementAndGet(), Objects.requireNonNull(label, "label"), artifact);
    }

    /**
     * Stream {@code body} into {@code target} (replacing it) as the transfer {@code label} of
     * {@code totalBytes} ({@code -1} when undeclared), returning the bytes written. The transfer
     * ends however the copy does.
     */
    public static long copy(InputStream body, Path target, String label, long totalBytes) throws IOException {
        try (Transfer t = open(label);
                OutputStream out = Files.newOutputStream(target)) {
            t.started(totalBytes);
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            while ((n = body.read(buf)) > 0) {
                out.write(buf, 0, n);
                read += n;
                t.advanced(read);
            }
            t.finished();
            return read;
        }
    }

    /**
     * Pass on a forked JVM's report, the line {@link TaskExec.ToolRun} read after its {@link
     * #FORKED_PREFIX}, as this process's own: a reply in the worker.
     */
    static void forward(String json) {
        send(json);
    }

    private static void send(String json) {
        Consumer<String> replies = REPLIES.get();
        if (replies != null) {
            replies.accept(json);
        } else {
            // A forked JVM's stdout, as PluginMain writes its protocol: straight to the descriptor,
            // each line one write, so no other writer splits it.
            byte[] line = (FORKED_PREFIX + json + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (FORKED_OUT) {
                try {
                    FORKED_OUT.write(line);
                    FORKED_OUT.flush();
                } catch (IOException e) {
                    // a report that cannot be written is a row not drawn, never a failed fetch
                }
            }
        }
    }

    private static final FileOutputStream FORKED_OUT = new FileOutputStream(FileDescriptor.out);

    private static final class Reported implements Transfer {
        private final String id;
        private final String label;
        private final boolean artifact;
        private boolean open;
        private boolean ended;
        private long read;
        private long reported;
        private long reportedAt;

        /** A reply about this body: {@code {"t":"transfer","id":…,"e":event,…}}. */
        private JsonFields reply(String event) {
            return JsonFields.object().string("t", REPLY).string("id", id).string("e", event);
        }

        Reported(String id, String label, boolean artifact) {
            this.id = id;
            this.label = label;
            this.artifact = artifact;
        }

        @Override
        public synchronized void started(long totalBytes) {
            if (ended) return;
            open = true;
            read = 0;
            reported = 0;
            reportedAt = Clock.SYSTEM.nanos();
            send(reply("start")
                    .string("label", label)
                    .number("total", totalBytes)
                    .optionalTrue("artifact", artifact)
                    .finish());
        }

        @Override
        public synchronized void advanced(long readBytes) {
            if (!open || ended) return;
            read = readBytes;
            long now = Clock.SYSTEM.nanos();
            if (read - reported >= REPORT_EVERY_BYTES || now - reportedAt >= REPORT_EVERY_NANOS) report(now);
        }

        private void report(long now) {
            reported = read;
            reportedAt = now;
            send(reply("read").number("read", read).finish());
        }

        @Override
        public synchronized void finished() {
            if (!open || ended) return;
            if (read != reported) report(Clock.SYSTEM.nanos());
            ended = true;
            send(reply("done").finish());
        }

        @Override
        public synchronized void close() {
            if (!open || ended) return;
            ended = true;
            send(reply("close").finish());
        }
    }
}
