// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpConnection;
import cc.jumpkick.engine.http.mcp.McpElicitResult;
import cc.jumpkick.engine.http.mcp.McpElicitation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A downloaded update, put to the person behind an MCP client: once per session and release, on
 * the first {@code tools/call} while it is on offer, before the tool runs. A client that declared
 * form elicitation gets an accept/decline question its host shows the person; accepting installs
 * the update now (the call still finishes on this engine, which drains, and the next one reaches
 * the new engine), declining is the shared "not now". A client without elicitation gets one line
 * appended to that call's result, saying how the person can install it. The agent is never given a
 * way to install it on its own.
 */
final class McpUpdateOffer {

    /** How long the question waits: well inside a client's tool-call timeout. */
    static final Duration WAIT = Duration.ofSeconds(45);

    private volatile Supplier<SelfUpdateControl> control = () -> SelfUpdateControl.NONE;

    /** The release each session was last asked about. */
    private final Map<String, String> asked = new ConcurrentHashMap<>();

    void control(Supplier<SelfUpdateControl> control) {
        this.control = control;
    }

    /** The question the host shows. */
    static String question(String version) {
        return "A new version of JumpKick (" + version + ") is downloaded. Update now? Running builds finish first.";
    }

    /** The line a client without elicitation gets, for the agent to pass on. */
    static String line(String version) {
        return "JumpKick " + version + " is downloaded and installs itself when the engine is idle. To install it"
                + " now, the user can run `jk self update` or click Update Now in the web console.";
    }

    /**
     * Before {@code call}'s tool runs: ask about the release on offer if this session has not been
     * asked about it. Returns the line to append to the result for a client that cannot be asked,
     * or null.
     */
    @Nullable
    String before(McpCall call) {
        McpConnection connection = call.connection();
        if (connection == null) return null;
        SelfUpdateControl c = control.get();
        Optional<String> offer = c.offer();
        if (offer.isEmpty()) return null;
        String version = offer.get();
        if (version.equals(asked.put(connection.id(), version))) return null;
        McpElicitResult answer = call.elicit(new McpElicitation.Form(question(version), emptyForm()), WAIT);
        return switch (answer) {
            case McpElicitResult.Accepted a -> {
                c.installNow();
                yield null;
            }
            case McpElicitResult.Declined d -> {
                c.snooze();
                yield null;
            }
            case McpElicitResult.Cancelled x -> {
                c.snooze();
                yield null;
            }
            case McpElicitResult.Unsupported u -> line(version);
            default -> null;
        };
    }

    /** {@code result} with {@code line} appended as one more text block; unchanged when there is none. */
    @SuppressWarnings("unchecked")
    static @Nullable Map<String, Object> append(@Nullable Map<String, Object> result, @Nullable String line) {
        if (result == null || line == null) return result;
        Map<String, Object> out = new LinkedHashMap<>(result);
        List<Object> content = new ArrayList<>();
        if (result.get("content") instanceof List<?> existing) content.addAll(existing);
        content.add(Map.of("type", "text", "text", line));
        out.put("content", content);
        return out;
    }

    /** A form with no fields: the host shows the message with accept and decline. */
    private static Map<String, Object> emptyForm() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }
}
