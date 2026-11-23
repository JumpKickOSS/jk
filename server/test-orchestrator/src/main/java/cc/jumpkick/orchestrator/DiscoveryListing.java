// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The parent side of the list-only test-discovery fork's protocol, as it fills: each {@code
 * discovered} event names one class a suite runs, and {@code discovery_total} closes the list. A
 * fork that ends before the total (a JVM killed, or exited, after naming some classes) left a list
 * the suite cannot be read from ({@link #runnable}).
 */
public final class DiscoveryListing {

    private final List<String> classes = new ArrayList<>();
    private boolean complete;

    /** The classes named so far, in the order the fork named them. */
    public List<String> classes() {
        return List.copyOf(classes);
    }

    /** Whether {@code discovery_total} closed the list. */
    public boolean complete() {
        return complete;
    }

    /**
     * The handler the fork's protocol lines go through: {@code discovery_total} reaches {@code
     * listener} too, and so does a {@code warning}. A throw from here ends the conversation, and the
     * list with it ({@link #runnable}).
     */
    public Consumer<String> handler(TestProgressListener listener) {
        return json -> {
            String event = Jsonl.str(json, "event");
            if ("discovered".equals(event)) {
                classes.add(Jsonl.str(json, "class"));
            } else if ("discovery_total".equals(event)) {
                complete = true;
                listener.onDiscoveryTotal(Jsonl.intValue(json, "classes", 0), Jsonl.intValue(json, "tests", 0));
            } else if ("warning".equals(event)) {
                // What the runner learned while listing (a named class its tag filter dropped, an
                // empty plan) reaches the run the same way it does from a suite JVM.
                listener.onWarning(
                        Objects.requireNonNullElse(Jsonl.str(json, "code"), "warning"),
                        Objects.requireNonNullElse(Jsonl.str(json, "message"), ""));
            }
        };
    }

    /**
     * Whether a suite may run off a fork's list: {@code discovery_total} closed it ({@code
     * complete}) and the parent's own decoder did not end the conversation part-way ({@code
     * decoderEnded}). A non-zero exit after the total is a shutdown blemish the list survives, so the
     * exit takes no part; a list that fails this says nothing about the suite, and nothing is not an
     * empty green run.
     */
    public static boolean runnable(boolean complete, boolean decoderEnded) {
        return complete && !decoderEnded;
    }
}
