// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Host-side driver for a forked plugin's JSONL protocol ({@code ##PREFIX:{"t":…}}). Registers
 * handlers by type and runs via {@link PluginProcess}. Use a custom discriminator + {@link
 * #converse} for the test runner's pull protocol.
 */
public final class PluginClient {

    /** The canonical message-type discriminator field name. */
    public static final String TYPE = "t";

    private final String prefix;
    private final String typeKey;
    private final Map<String, Consumer<String>> handlers = new HashMap<>();
    private Consumer<String> onOther;
    private Consumer<String> passthrough;

    /** A client for a plugin using the canonical {@code "t"} discriminator and the given prefix. */
    public PluginClient(String prefix) {
        this(prefix, TYPE);
    }

    /** A client keying messages on {@code typeKey} (e.g. the test runner's {@code "e"}). */
    public PluginClient(String prefix, String typeKey) {
        this.prefix = prefix;
        this.typeKey = typeKey;
    }

    /** Register the handler for protocol messages whose type equals {@code type}. */
    public PluginClient on(String type, Consumer<String> handler) {
        handlers.put(type, handler);
        return this;
    }

    /** Handler for protocol messages with no type-specific handler (default: dropped). */
    public PluginClient onOther(Consumer<String> handler) {
        this.onOther = handler;
        return this;
    }

    /** Handler for non-protocol (passthrough) lines the tool writes to stdout (default: dropped). */
    public PluginClient passthrough(Consumer<String> handler) {
        this.passthrough = handler;
        return this;
    }

    /** Fork {@code command}, dispatch its protocol stream, and return the process exit code. */
    public int run(List<String> command) throws IOException, InterruptedException {
        return PluginProcess.run(command, prefix, this::dispatch, passthrough);
    }

    /** As {@link #run(List)}, adding {@code extraEnv} to the child's environment. */
    public int run(List<String> command, Map<String, String> extraEnv) throws IOException, InterruptedException {
        return PluginProcess.run(command, extraEnv, prefix, this::dispatch, passthrough);
    }

    /**
     * Two-way pull protocol: each protocol message is delivered to {@code onMessage} with a {@link
     * PluginProcess.Conversation} for sending commands back to the plugin's stdin. The registered
     * {@link #on} handlers are not used in this mode (the caller dispatches on the message itself).
     */
    public int converse(List<String> command, BiConsumer<String, PluginProcess.Conversation> onMessage)
            throws IOException, InterruptedException {
        return PluginProcess.converse(command, prefix, onMessage, passthrough);
    }

    private void dispatch(String json) {
        String t = Jsonl.str(json, typeKey);
        Consumer<String> handler = t != null ? handlers.get(t) : null;
        if (handler != null) {
            handler.accept(json);
        } else if (onOther != null) {
            onOther.accept(json);
        }
    }
}
