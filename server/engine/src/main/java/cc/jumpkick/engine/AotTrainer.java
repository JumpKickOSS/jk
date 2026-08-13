// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Sidecar AOT-trainer lifecycle. Best-effort: a trainer that fails to start never takes
 * down the engine.
 */
public final class AotTrainer {

    private final Consumer<String> log;
    private volatile @Nullable Supplier<Process> spawner;
    private volatile @Nullable Process process;

    public AotTrainer(Consumer<String> log) {
        this.log = log;
    }

    public void spawner(Supplier<Process> spawner) {
        this.spawner = spawner;
    }

    public long pid() {
        Process p = process;
        return (p != null && p.isAlive()) ? p.pid() : -1;
    }

    public void startIfConfigured() {
        Supplier<Process> s = spawner;
        if (s == null) return;
        try {
            Process p = s.get();
            if (p == null) return;
            process = p;
            log.accept("jk engine: AOT training sidecar started (pid " + p.pid() + ")");
            p.onExit().orTimeout(5, TimeUnit.MINUTES).whenComplete((proc, err) -> {
                if (err != null) {
                    p.destroyForcibly();
                    log.accept("jk engine: AOT training sidecar overran; killed (pid " + p.pid() + ")");
                } else {
                    log.accept("jk engine: AOT training sidecar finished (pid " + p.pid() + ", exit " + proc.exitValue()
                            + ")");
                }
                process = null;
            });
        } catch (RuntimeException e) {
            log.accept("jk engine: AOT training sidecar failed to start: " + e.getMessage());
        }
    }

    /**
     * Kill a live sidecar, clear the spawner, and suppress all AOT training so a lame-duck
     * process cannot refill {@code state/aot} (JK-1452). Idempotent.
     */
    public void stopQuietly() {
        cc.jumpkick.util.AotSettings.suppressTraining();
        spawner = null;
        Process p = process;
        process = null;
        if (p == null || !p.isAlive()) return;
        try {
            p.destroyForcibly();
            log.accept("jk engine: killed AOT training sidecar (no longer primary, pid " + p.pid() + ")");
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }
}
