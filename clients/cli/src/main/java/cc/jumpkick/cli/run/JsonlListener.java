// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import java.io.PrintStream;

/**
 * Emit one JSON object per event to stdout. Triggered by {@code --output json} or {@code jsonl}
 * (identical). Consumed by agents, CI, and tooling. Wire format: {@link JsonlShape} — see {@code
 * docs/machine-output.md}. Each line carries the aggregate {@code progress} rider and is
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
        super(aggregateRider);
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
