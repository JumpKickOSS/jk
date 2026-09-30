// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.mcp;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * One outstanding {@code tools/call}'s keep-alive: follows the engine's MCP event stream for the
 * call's progress token and writes MCP {@code notifications/progress} lines until closed. A change
 * is written at most once per {@code gap}; a quiet engine still gets a line every {@code
 * heartbeat}, carrying the last known state. The stream opens only once the call has been
 * outstanding for {@code gap}, so a quick call costs nothing.
 *
 * <p>{@code progress} is the engine's percent of {@code total} 100, nudged up by a hundredth when
 * it has not moved: the spec requires every notification to increase it.
 */
final class McpProgress implements AutoCloseable {

    /** Opens the engine's event stream filtered to one progress token. */
    interface Events {
        InputStream open(String token) throws IOException, InterruptedException;
    }

    private final Object token;
    private final Consumer<String> out;
    private final long heartbeatNanos;
    private final long gapNanos;
    private final Thread ticker;
    private final Thread reader;

    private @Nullable Double percent;
    private @Nullable String step;
    private @Nullable String label;
    private boolean changed;
    private double sent = -1;
    private long sentAt = Clock.SYSTEM.nanos();
    private boolean closed;
    private @Nullable InputStream stream;

    private McpProgress(Object token, Events events, Consumer<String> out, Duration heartbeat, Duration gap) {
        this.token = token;
        this.out = out;
        this.heartbeatNanos = heartbeat.toNanos();
        this.gapNanos = gap.toNanos();
        long tickMs = Math.max(10, Math.min(gap.toMillis(), heartbeat.toMillis()) / 2);
        this.ticker = Thread.ofVirtual().name("jk-mcp-progress").unstarted(() -> tick(tickMs));
        this.reader = Thread.ofVirtual().name("jk-mcp-events").unstarted(() -> read(events, gap));
    }

    /** Start following {@code token}; {@code out} writes one line and must be safe to call from any thread. */
    static McpProgress start(Object token, Events events, Consumer<String> out, Duration heartbeat, Duration gap) {
        McpProgress p = new McpProgress(token, events, out, heartbeat, gap);
        p.ticker.start();
        p.reader.start();
        return p;
    }

    /** Stop following. No line is written once this returns. */
    @Override
    public void close() {
        InputStream open;
        synchronized (this) {
            closed = true;
            open = stream;
        }
        ticker.interrupt();
        reader.interrupt();
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // The stream is being abandoned either way.
            }
        }
    }

    private void tick(long tickMs) {
        try {
            while (true) {
                Thread.sleep(tickMs);
                synchronized (this) {
                    if (closed) return;
                    long quiet = Clock.SYSTEM.nanos() - sentAt;
                    if ((changed && quiet >= gapNanos) || quiet >= heartbeatNanos) emit();
                }
            }
        } catch (InterruptedException e) {
            // closed
        }
    }

    private void read(Events events, Duration gap) {
        try {
            Thread.sleep(gap);
            if (isClosed()) return;
            InputStream in = events.open(tokenText(token));
            synchronized (this) {
                if (closed) {
                    in.close();
                    return;
                }
                stream = in;
            }
            BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("data:")) apply(line.substring(5).trim());
            }
        } catch (IOException | RuntimeException e) {
            // No stream: the heartbeat still keeps the call alive.
        } catch (InterruptedException e) {
            // closed
        }
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    /** Fold one {@code notifications/jk/event} frame into the state the next line reports. */
    private synchronized void apply(String json) {
        if (!(MiniJson.parse(json) instanceof Map<?, ?> rpc)) return;
        if (!(rpc.get("params") instanceof Map<?, ?> params)) return;
        if (params.get("progress") instanceof Number n) changed |= set(n.doubleValue());
        String event = String.valueOf(params.get("event"));
        if (EngineProtocol.TASK_START.equals(event) && params.get("task") instanceof String task) {
            changed |= !task.equals(step) || label != null;
            step = task;
            label = null;
        } else if (EngineProtocol.LABEL.equals(event) && params.get("label") instanceof String text) {
            changed |= !text.equals(label);
            label = text;
        }
    }

    private boolean set(double p) {
        boolean moved = percent == null || percent != p;
        percent = p;
        return moved;
    }

    /** Write one notification from the current state. Caller holds the lock. */
    private void emit() {
        double base = percent == null ? 0 : percent;
        double value = Math.round(Math.max(base, sent + 0.01) * 100) / 100.0;
        if (value <= sent) value = Math.round((sent + 0.01) * 100) / 100.0;
        sent = value;
        sentAt = Clock.SYSTEM.nanos();
        changed = false;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("progressToken", token);
        params.put("progress", value);
        if (value <= 100) params.put("total", 100);
        params.put("message", message());
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/progress");
        note.put("params", params);
        out.accept(MiniJson.write(note));
    }

    private String message() {
        String what = step == null ? "running" : step;
        if (label != null && !label.isBlank()) what += " · " + label;
        return percent == null ? what : what + " · " + Math.round(percent) + "%";
    }

    /** The token as the engine's {@code ?progressToken=} query spells it: integral numbers lose the {@code .0}. */
    static String tokenText(Object token) {
        return token instanceof String s ? s : MiniJson.write(token);
    }

    /** {@code params._meta.progressToken} of a {@code tools/call}, or null for any other message. */
    static @Nullable Object tokenOf(String message) {
        try {
            if (!(MiniJson.parse(message) instanceof Map<?, ?> m)) return null;
            if (!"tools/call".equals(m.get("method")) || !m.containsKey("id")) return null;
            if (!(m.get("params") instanceof Map<?, ?> params)) return null;
            if (!(params.get("_meta") instanceof Map<?, ?> meta)) return null;
            Object token = meta.get("progressToken");
            return token instanceof String || token instanceof Number ? token : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
