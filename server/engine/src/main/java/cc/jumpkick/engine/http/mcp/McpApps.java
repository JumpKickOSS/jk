// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The applications the {@code run} tool started, one per project directory. An app is stopped by
 * {@code run(action=stop)}, by the next start in its directory, when the session that started it
 * ends, and when the engine stops serving.
 */
public final class McpApps {

    private final Map<String, McpApp> byDir = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile boolean hooked;

    /** The lock a start in {@code dir} holds from its build to its probe, so two starts never race. */
    Object lockFor(String dir) {
        return locks.computeIfAbsent(dir, d -> new Object());
    }

    /** The app last started in {@code dir}, running or not; null for none. */
    public @Nullable McpApp in(String dir) {
        return byDir.get(dir);
    }

    /** Keep {@code app} as its directory's, the engine's exit stopping it. */
    public void add(McpApp app) {
        hookExit();
        byDir.put(app.dir(), app);
    }

    /** Stop the app in {@code dir}; null when none was running there. */
    @Nullable
    McpApp stop(String dir) {
        McpApp app = byDir.get(dir);
        return app != null && app.stop() ? app : null;
    }

    /** The session {@code owner} ended: stop every app it started. Returns how many were running. */
    public int stopOwnedBy(@Nullable String owner) {
        if (owner == null) return 0;
        int n = 0;
        for (McpApp app : List.copyOf(byDir.values())) {
            if (owner.equals(app.owner()) && app.stop()) n++;
        }
        return n;
    }

    /** Stop every app; the engine is going away. */
    public void stopAll() {
        for (McpApp app : List.copyOf(byDir.values())) app.stop();
    }

    /** {@link McpApp#row()} of each app still running, oldest first. */
    public List<Map<String, Object>> rows() {
        List<McpApp> running = new ArrayList<>();
        for (McpApp app : byDir.values()) if (app.alive()) running.add(app);
        running.sort((a, b) -> Long.compare(a.startedAt(), b.startedAt()));
        return running.stream().map(McpApp::row).toList();
    }

    /** An engine that exits without stopping its server (a signal, a crash in startup) still stops its apps. */
    private void hookExit() {
        if (hooked) return;
        synchronized (this) {
            if (hooked) return;
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(this::stopAll, "jk-engine-apps-stop"));
            } catch (IllegalStateException shuttingDown) {
                // The JVM is already exiting; its stop of the server stops the apps.
            }
            hooked = true;
        }
    }
}
