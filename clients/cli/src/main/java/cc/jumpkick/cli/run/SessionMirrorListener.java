// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

/**
 * Mirrors pipeline events into the active {@link CliSessionTranscript} as {@link JsonlShape} lines
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
}
