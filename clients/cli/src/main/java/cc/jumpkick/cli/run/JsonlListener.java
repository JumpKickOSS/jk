// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import java.io.PrintStream;
import org.jspecify.annotations.Nullable;

/**
 * Emit one JSON object per event to stdout. Triggered by {@code -O jsonl}.
 * Consumed by agents, CI, and tooling. Wire format: {@link JsonlShape} — see {@code
 * docs/user/machine-output.md}. Each line carries the aggregate {@code progress} rider and is
 * dual-written to the active {@link CliSessionTranscript} when present.
 */
public final class JsonlListener extends JsonlEmittingListener {

    private final PrintStream out;

    public JsonlListener(PrintStream out) {
        this(out, true);
    }

    /**
     * {@code aggregateRider} is false for one member of a multi-module workspace run: the engine's
     * {@code workspace-progress} snapshot is the only aggregate truth there.
     */
    public JsonlListener(PrintStream out, boolean aggregateRider) {
        this(out, aggregateRider, null);
    }

    /** As above for one workspace member: every line names {@code module} ({@code group:name}). */
    public JsonlListener(PrintStream out, boolean aggregateRider, @Nullable String module) {
        super(aggregateRider, module);
        this.out = out;
    }

    @Override
    protected void emit(String line, boolean immediate) {
        synchronized (out) {
            out.println(line);
            out.flush();
        }
        CliSessionTranscript session = CliSessionTranscript.active();
        if (session != null) session.appendRaw(line, immediate);
    }
}
