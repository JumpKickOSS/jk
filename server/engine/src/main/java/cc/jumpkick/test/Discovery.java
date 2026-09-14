// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What one list-only test-discovery fork produced: the classes it named, its exit code, what it
 * printed outside the protocol (the crash text when it died), and the parent-side exception when
 * the parent's own decoder ended the conversation.
 */
record Discovery(
        List<String> classes,
        int exit,
        String output,
        @Nullable RuntimeException handler) {

    Discovery(List<String> classes, int exit, String output) {
        this(classes, exit, output, null);
    }

    /**
     * The discovery protocol's parent side: each {@code discovered} event names one class into
     * {@code classes}; {@code discovery_total} reaches the listener. A throw from here ends the
     * conversation as a {@link cc.jumpkick.engine.plugin.PluginProcess.HandlerFailure}, which the
     * launcher turns into {@link #handlerFailed}.
     */
    static Consumer<String> handler(List<String> classes, TestProgressListener listener) {
        return json -> {
            String event = Jsonl.str(json, "event");
            if ("discovered".equals(event)) {
                classes.add(Jsonl.str(json, "class"));
            } else if ("discovery_total".equals(event)) {
                listener.onDiscoveryTotal(Jsonl.intValue(json, "classes", 0), Jsonl.intValue(json, "tests", 0));
            } else if ("warning".equals(event)) {
                // What the runner learned while listing — a named class its tag filter dropped, an
                // empty plan — reaches the run the same way it does from a suite JVM.
                listener.onWarning(
                        Objects.requireNonNullElse(Jsonl.str(json, "code"), "warning"),
                        Objects.requireNonNullElse(Jsonl.str(json, "message"), ""));
            }
        };
    }

    /**
     * A fork whose conversation the parent's own protocol handler ended. The exit code is the kill
     * the parent asked for and says nothing; the classes named before the throw are a list the
     * decoder could not finish reading, so none of it is trusted.
     */
    static Discovery handlerFailed(List<String> classes, String output, RuntimeException handler) {
        return new Discovery(classes, -1, output, handler);
    }

    /**
     * The fork said nothing usable about the suite. A non-zero exit after a full class list is a
     * shutdown blemish the list survives; a non-zero exit with nothing named, or a conversation the
     * parent's decoder ended part-way, says nothing about the suite, and nothing is not an empty
     * green run.
     */
    boolean crashed() {
        return handler != null || (exit != 0 && classes.isEmpty());
    }

    /**
     * The verdict for a fork that {@link #crashed()}: the same {@code (test run)} failure a crashed
     * pool worker gets — the exit code and what the JVM printed, or the handler's exception when the
     * parent ended the conversation — so the summary explains the crash instead of counting zero
     * tests as passed.
     */
    TestSummary failure(String moduleLabel) {
        TestFailureInfo row = handler != null
                ? WorkerFailureRow.discovery(moduleLabel, handler)
                : new TestFailureInfo(moduleLabel, "", "", "(test run)", "", "test discovery exited " + exit, output);
        return new TestSummary(1, 0, 1, 0, List.of(row));
    }
}
