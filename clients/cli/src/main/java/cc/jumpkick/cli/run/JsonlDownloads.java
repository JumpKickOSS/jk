// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.DownloadsView;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.task.TransferBoard;
import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The run's downloads as JSONL, for a {@code --output json} command: a {@code downloads} record when
 * a burst starts, one every {@link #PERIOD_MILLIS} while it lasts, and one with {@code "done":true}
 * and the final totals when it ends. Sparse on purpose: the engine samples its board several times a
 * second, and a machine reading stdout wants the shape of the transfer, not every frame.
 */
public final class JsonlDownloads implements DownloadsView {

    /** How often a burst under way is summed up. */
    static final long PERIOD_MILLIS = 10_000L;

    private final Supplier<PrintStream> out;
    private final Clock clock;
    private TransferBoard.Snapshot last = TransferBoard.Snapshot.EMPTY;
    private long lastWrittenNanos;
    private boolean inBurst;

    /** Bytes the last {@code done} record reported; a later end with more closes another burst. */
    private long doneBytes;

    /** The command's terminal is written: nothing follows it on stdout. */
    private boolean sealed;

    /** {@code out} is read at each write, so a command that swaps {@code System.out} is followed. */
    public JsonlDownloads(Supplier<PrintStream> out, Clock clock) {
        this.out = Objects.requireNonNull(out, "out");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized void downloads(TransferBoard.Snapshot snapshot) {
        if (sealed) return;
        long now = clock.nanos();
        if (snapshot.downloading()) {
            if (!inBurst || now - lastWrittenNanos >= PERIOD_MILLIS * 1_000_000L) write(snapshot, false, now);
            inBurst = true;
        } else if (inBurst || snapshot.bytes() > doneBytes) {
            // The burst is over: its totals, from the frame that ended it — also for a burst too
            // short to have been seen open.
            write(snapshot.bytes() > 0 ? snapshot : last, true, now);
            inBurst = false;
        }
        last = snapshot;
    }

    /**
     * The command's terminal is next: a burst still open ends now, with the last totals seen, and
     * nothing is written after the terminal — the stream's last line is its terminal.
     */
    public synchronized void seal() {
        finish();
        sealed = true;
    }

    /** Seal whichever JSONL downloads view this command writes to, if it has one. */
    public static void sealCurrent() {
        if (DownloadsView.current() instanceof JsonlDownloads jsonl) jsonl.seal();
    }

    /** The command is over: a burst still open ends with the last totals seen. */
    public synchronized void finish() {
        if (sealed || !inBurst) return;
        write(last, true, clock.nanos());
        inBurst = false;
    }

    private void write(TransferBoard.Snapshot snapshot, boolean done, long now) {
        if (done) doneBytes = snapshot.bytes();
        PrintStream stream = out.get();
        stream.println(JsonlShape.downloads(snapshot, done));
        stream.flush();
        lastWrittenNanos = now;
    }
}
