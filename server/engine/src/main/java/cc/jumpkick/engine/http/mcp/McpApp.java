// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.host.Processes;
import cc.jumpkick.host.time.Clock;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * One application the {@code run} tool started: its process, the file its stdout and stderr go to,
 * the address it answers on once known, and the MCP session that started it.
 */
public final class McpApp {

    /** How long a stop waits for the app to exit on its own before its whole tree is killed. */
    static final long STOP_GRACE_MILLIS = 5_000;

    private final String dir;
    private final String name;
    private final Process process;
    private final Path log;
    private final long startedAt;
    private final @Nullable String owner;
    private final String startedBy;
    private volatile String url = "";
    private volatile boolean stopped;

    /**
     * @param dir the project directory, as {@link McpHistoryViews#dirKey} spells it
     * @param owner the {@code Mcp-Session-Id} of the session that started it; null for a call with none
     * @param startedBy who started it, as the dashboard names the session ({@code claude-code 91be})
     */
    public McpApp(
            String dir,
            String name,
            Process process,
            Path log,
            long startedAt,
            @Nullable String owner,
            String startedBy) {
        this.dir = dir;
        this.name = name;
        this.process = process;
        this.log = log;
        this.startedAt = startedAt;
        this.owner = owner;
        this.startedBy = startedBy;
    }

    public String dir() {
        return dir;
    }

    public String name() {
        return name;
    }

    Process process() {
        return process;
    }

    public Path log() {
        return log;
    }

    public long startedAt() {
        return startedAt;
    }

    public @Nullable String owner() {
        return owner;
    }

    public String startedBy() {
        return startedBy;
    }

    /** The address it answered on, {@code http://localhost:8080}; empty until a probe found it. */
    public String url() {
        return url;
    }

    void url(String url) {
        this.url = url;
    }

    public boolean alive() {
        return process.isAlive();
    }

    /** {@code running}, {@code stopped}, or {@code exited with N}. */
    public String state() {
        if (process.isAlive()) return "running";
        if (stopped) return "stopped";
        return "exited with " + process.exitValue();
    }

    /**
     * Ask the app to exit, then kill whatever of its tree is left after {@link #STOP_GRACE_MILLIS}.
     * Returns false when it had already exited.
     */
    boolean stop() {
        if (!process.isAlive()) return false;
        stopped = true;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) Processes.killTree(process);
        } catch (InterruptedException e) {
            Processes.killTree(process);
            Thread.currentThread().interrupt();
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        return true;
    }

    /**
     * The dashboard's row: {@code dir}, {@code name}, {@code pid}, {@code url} (empty until known),
     * {@code startedAt} (epoch ms), {@code startedBy}.
     */
    public Map<String, Object> row() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", dir);
        m.put("name", name);
        m.put("pid", process.pid());
        m.put("url", url);
        m.put("startedAt", startedAt);
        m.put("startedBy", startedBy);
        return m;
    }

    /** Milliseconds since it started. */
    long ranMillis() {
        return Math.max(0, Clock.SYSTEM.millis() - startedAt);
    }
}
