// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.run.BuildPlanListener;

/**
 * Mirrors plan events into the active {@link CliSessionTranscript} as {@link JsonlShape} lines
 * . Used when stdout is not already JSONL (TTY/verbose) so {@code details.jsonl} still
 * carries the live stream. {@link JsonlListener} dual-writes itself; this listener is skipped in
 * JSON mode to avoid duplicate lines. Never stamps module-local fractions into {@link LiveProgress}
 * engine {@code workspace-progress} owns the aggregate rider.
 */
public final class SessionMirrorListener extends JsonlEmittingListener {

    private final CliSessionTranscript session;

    public SessionMirrorListener(CliSessionTranscript session) {
        super(false);
        this.session = session;
    }

    @Override
    protected void emit(String line, boolean immediate) {
        session.appendRaw(line, immediate);
    }

    /**
     * {@code listener} with the active transcript listening beside it, so the plan events a hosted
     * verb's wire stream drives land in its {@code details.jsonl}; {@code listener} alone when no
     * transcript is open or stdout is already JSONL.
     */
    public static BuildPlanListener mirrored(BuildPlanListener listener, BuildPlanConsole.Mode mode) {
        CliSessionTranscript active = CliSessionTranscript.active();
        if (active == null || mode == BuildPlanConsole.Mode.JSON) return listener;
        return CompositeBuildPlanListener.of(listener, new SessionMirrorListener(active));
    }
}
