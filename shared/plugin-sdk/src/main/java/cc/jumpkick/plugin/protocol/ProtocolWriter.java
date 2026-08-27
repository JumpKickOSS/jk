// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import java.io.PrintStream;

/**
 * Plugin-side emitter for the host&lt;-&gt;plugin protocol: writes one JSON object per line, each
 * prefixed with the plugin's marker so the host can tell structured protocol lines apart from
 * passthrough chatter (anything the underlying tool writes to stdout). Pair with {@link
 * cc.jumpkick.jsonl.Jsonl#quote} to encode string values.
 *
 * <p><strong>Every line is flushed, and that is not negotiable.</strong> It looks like an obvious
 * batching target — a 2,471-test run emits ~8,000 events — and it is not one.
 *
 * <p>The pull protocol has the plugin <em>emit {@code ready} and then block reading stdin</em>, which
 * {@code PluginProcess.converse} documents from the host side: reading stdout and writing stdin both
 * happen on one host thread, so the plugin must alternate rather than flood. A buffered {@code ready}
 * therefore never reaches the host, the host never sends the next command, and both sides wait
 * forever. That is a deadlock, not a slow build.
 *
 * <p>A per-type rule cannot rescue it either: {@link #emit} receives already-encoded JSON and would
 * have to parse it to tell a liveness-critical {@code ready} from bulk output, and the engine paints
 * progress from the bulk types too, so a latency bound would need a timer thread in every worker.
 *
 * <p>What the buffer underneath <em>does</em> buy: one {@code write(2)} per line instead of however
 * many the encoder emits, since the flush below now has something to flush. This is a pipe, not the
 * filesystem — nowhere near the 160–305&nbsp;µs NTFS costs that motivate the rest of JK-1027 — which
 * is the other half of why batching it is not worth a deadlock (JK-1045).
 */
public final class ProtocolWriter {

    private final PrintStream out;
    private final String prefix;

    public ProtocolWriter(PrintStream out, String prefix) {
        this.out = out;
        this.prefix = prefix;
    }

    /** The marker every emitted line carries (e.g. {@code ##JKGIT:}). */
    public String prefix() {
        return prefix;
    }

    /**
     * Emit one already-encoded JSON object as a prefixed protocol line. Synchronised so plugins that
     * emit from multiple threads (e.g. a test runner firing listener events from parallel test
     * threads) can't interleave a half-written line.
     */
    public synchronized void emit(String json) {
        out.println(prefix + json);
        out.flush();
    }
}
