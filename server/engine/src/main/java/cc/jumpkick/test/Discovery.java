// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What one list-only test-discovery fork produced: the classes it named, whether {@code
 * discovery_total} closed the list, its exit code, what it printed outside the protocol (the crash
 * text when it died), the command it was started with, and the parent-side exception when the
 * parent's own decoder ended the conversation.
 */
record Discovery(
        List<String> classes,
        boolean complete,
        int exit,
        String output,
        List<String> command,
        @Nullable RuntimeException handler) {

    Discovery(List<String> classes, boolean complete, int exit, String output, List<String> command) {
        this(classes, complete, exit, output, command, null);
    }

    /**
     * The discovery protocol's parent side, as it fills: each {@code discovered} event names one
     * class, and {@code discovery_total} closes the list. A fork that ends before the total — a
     * JVM killed, or exited, after naming some classes — left a list the suite cannot be read from.
     */
    static final class Listing {
        private final List<String> classes = new ArrayList<>();
        private boolean complete;

        List<String> classes() {
            return List.copyOf(classes);
        }

        boolean complete() {
            return complete;
        }

        /**
         * The handler the fork's protocol lines go through: {@code discovery_total} reaches {@code
         * listener} too. A throw from here ends the conversation as a {@link
         * cc.jumpkick.engine.plugin.PluginProcess.HandlerFailure}, which the launcher turns into
         * {@link Discovery#handlerFailed}.
         */
        Consumer<String> handler(TestProgressListener listener) {
            return json -> {
                String event = Jsonl.str(json, "event");
                if ("discovered".equals(event)) {
                    classes.add(Jsonl.str(json, "class"));
                } else if ("discovery_total".equals(event)) {
                    complete = true;
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

        /** The fork's verdict once it exited {@code exit} with {@code output} printed outside the protocol. */
        Discovery ended(int exit, String output, List<String> command) {
            return new Discovery(classes(), complete, exit, output, command);
        }

        /** The fork's verdict when the parent's own decoder ended the conversation: see {@link Discovery#handlerFailed}. */
        Discovery handlerFailed(String output, RuntimeException handler) {
            return Discovery.handlerFailed(classes(), output, handler);
        }
    }

    /**
     * A fork whose conversation the parent's own protocol handler ended. The exit code is the kill
     * the parent asked for and says nothing; the classes named before the throw are a list the
     * decoder could not finish reading, so none of it is trusted.
     */
    static Discovery handlerFailed(List<String> classes, String output, RuntimeException handler) {
        return new Discovery(classes, false, -1, output, List.of(), handler);
    }

    /**
     * The fork said nothing usable about the suite. A non-zero exit after the total closed the
     * list is a shutdown blemish the list survives; a fork that ended before the total — whatever
     * its exit — or a conversation the parent's decoder ended part-way says nothing about the
     * suite, and nothing is not an empty green run.
     */
    boolean crashed() {
        return handler != null || !complete;
    }

    /**
     * The verdict for a fork that {@link #crashed()}. A conversation the parent's own decoder ended
     * is the parent's bug and stays a {@code (test run)} row; any other fork that ended before its
     * total is a {@link TestLauncherFailure} — the exit code and what the JVM printed fail the
     * step, so zero tests are never counted as passed.
     */
    TestSummary verdict(String moduleLabel) {
        if (handler != null) {
            return new TestSummary(1, 0, 1, 0, List.of(WorkerFailureRow.discovery(moduleLabel, handler)));
        }
        if (classes.isEmpty()) throw TestLauncherFailure.discovery(moduleLabel, exit, output, command);
        throw TestLauncherFailure.discoveryCutShort(moduleLabel, exit, output, command, classes.size());
    }
}
