// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * One {@code tools/call}: the engine context, the client's {@code arguments}, the MCP progress
 * token bound from {@code params._meta}, and the {@link McpConnection connection} the call rode in
 * on (null when the client sent no {@code Mcp-Session-Id}), and the {@link McpOutbound way back}
 * to that client while the call is outstanding (null when the transport has none). The argument
 * readers live here so every tool decodes {@code dir} / {@code limit} / {@code apply} the same way
 * — a tool that hand-rolls one is inventing private semantics for a shared wire.
 */
public record McpCall(
        McpContext ctx,
        Map<String, Object> args,
        @Nullable String progressToken,
        @Nullable McpConnection connection,
        @Nullable McpOutbound outbound) {

    /** A call from no identified connection. */
    public McpCall(McpContext ctx, Map<String, Object> args, @Nullable String progressToken) {
        this(ctx, args, progressToken, null, null);
    }

    /** A call on {@code connection} with no way back to its client. */
    public McpCall(
            McpContext ctx,
            Map<String, Object> args,
            @Nullable String progressToken,
            @Nullable McpConnection connection) {
        this(ctx, args, progressToken, connection, null);
    }

    /** This call with other arguments: same connection, token and way back. */
    public McpCall withArgs(Map<String, Object> other) {
        return new McpCall(ctx, other, progressToken, connection, outbound);
    }

    /**
     * Ask the human behind this call's client, and wait up to {@code timeout} for the answer. Sends
     * {@code elicitation/create} only when the session declared the mode and the call has a way back;
     * otherwise answers {@link McpElicitResult.Unsupported} and sends nothing. A wait that runs out
     * withdraws the question with {@code notifications/cancelled}. The caller picks {@code timeout}
     * under the client's own tool-call timeout, since the call stays outstanding while it waits.
     */
    public McpElicitResult elicit(McpElicitation question, Duration timeout) {
        McpConnection c = connection;
        if (c == null) return new McpElicitResult.Unsupported("the call rode on no session");
        McpElicitModes modes = c.elicitation();
        boolean declared = question instanceof McpElicitation.Url ? modes.url() : modes.form();
        if (!declared) {
            String mode = question instanceof McpElicitation.Url ? "url" : "form";
            return new McpElicitResult.Unsupported("the client declared no " + mode + " elicitation under "
                    + c.revision().wire());
        }
        McpOutbound out = outbound;
        if (out == null) return new McpElicitResult.Unsupported("the transport carries no request to the client");
        McpConnection.Expected expected = c.expect();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", expected.requestId());
        request.put("method", "elicitation/create");
        request.put("params", question.params(c.revision()));
        try {
            out.send(request);
        } catch (IOException | RuntimeException e) {
            c.forget(expected.requestId());
            return new McpElicitResult.Failed("could not reach the client: " + e.getMessage());
        }
        return ctx.admissionYield().yielding(() -> awaitAnswer(c, out, expected, timeout));
    }

    private static McpElicitResult awaitAnswer(
            McpConnection c, McpOutbound out, McpConnection.Expected expected, Duration timeout) {
        try {
            return McpElicitResult.of(expected.answer().get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            withdraw(c, out, expected.requestId(), "timed out");
            return new McpElicitResult.TimedOut();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            withdraw(c, out, expected.requestId(), "the call was cancelled");
            return new McpElicitResult.Failed("interrupted while waiting for the client");
        } catch (ExecutionException e) {
            c.forget(expected.requestId());
            return new McpElicitResult.Failed(String.valueOf(e.getCause()));
        }
    }

    /** Tell the client the engine no longer wants an answer to {@code requestId}. */
    private static void withdraw(McpConnection c, McpOutbound out, String requestId, String reason) {
        c.forget(requestId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", requestId);
        params.put("reason", reason);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/cancelled");
        note.put("params", params);
        try {
            out.send(note);
        } catch (IOException | RuntimeException ignored) {
            // The client is gone; there is no question left to withdraw.
        }
    }

    /** The journal's session label for a job this call starts, or null when the connection is anonymous. */
    public @Nullable String sessionLabel() {
        return connection == null ? null : connection.label();
    }

    /** A string argument, or {@code null} when absent. */
    public @Nullable String str(String key) {
        Object raw = args.get(key);
        return raw == null ? null : String.valueOf(raw);
    }

    /** A numeric argument as a long, or {@code null} when absent or not a number. */
    public @Nullable Long num(String key) {
        return args.get(key) instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    /** A numeric argument as an int, or {@code null} when absent or not a number. */
    public @Nullable Integer intOrNull(String key) {
        return args.get(key) instanceof Number n ? Integer.valueOf(n.intValue()) : null;
    }

    /** A bounded integer argument: absent, unparseable or out of range all clamp to the band. */
    public int count(String key, int fallback, int min, int max) {
        Object raw = args.get(key);
        int n = fallback;
        if (raw instanceof Number num) n = num.intValue();
        else if (raw != null) {
            try {
                n = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                n = fallback;
            }
        }
        if (n < min) n = min;
        if (n > max) n = max;
        return n;
    }

    /** A string-array argument; anything else is empty. */
    public List<String> strings(String key) {
        if (!(args.get(key) instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    /** Three-state: {@code null} when the client said nothing. */
    public @Nullable Boolean tri(String key) {
        return McpHistoryViews.parseBool(args.get(key));
    }

    /** An opt-in flag: absent means false. */
    public boolean flag(String key) {
        return flagOr(key, false);
    }

    /** A flag with a declared default — {@code wait} and {@code unique} default true. */
    public boolean flagOr(String key, boolean fallback) {
        Boolean b = tri(key);
        return b == null ? fallback : b.booleanValue();
    }

    /** {@code arguments.action}, or {@code fallback} when absent or blank. Case is not touched. */
    public String action(String fallback) {
        String action = str("action");
        return action == null || action.isBlank() ? fallback : action;
    }

    /**
     * The target checkout: the explicit argument, else this connection's bind, else {@code null}.
     * An anonymous call has no bind to fall back on: another connection's bind is never its own.
     */
    public @Nullable String dir() {
        String dir = str("dir");
        if (dir != null && !dir.isBlank()) return dir;
        return boundDir();
    }

    /** The dir this call's connection is bound to, or {@code null} when it is anonymous or unbound. */
    public @Nullable String boundDir() {
        return connection == null ? null : connection.dir();
    }

    /** {@link #dir()} for a tool that cannot run without one. */
    public String requiredDir() {
        String dir = dir();
        if (dir == null || dir.isBlank()) {
            throw new McpError(-32602, "requires arguments.dir (the first call that carries it binds the connection)");
        }
        return dir;
    }

    /** A text-only {@code tools/call} result — what every loop tool answers. */
    public Map<String, Object> text(String text) {
        return McpEnvelope.text(text, false);
    }

    /** A text-only {@code tools/call} result flagged {@code isError}. */
    public Map<String, Object> error(String text) {
        return McpEnvelope.text(text, true);
    }

    /** Wrap an {@link McpEnvelope} as the {@code tools/call} result. */
    public Map<String, Object> ok(Map<String, Object> envelope, String summary) {
        return McpEnvelope.toolResult(envelope, summary);
    }
}
