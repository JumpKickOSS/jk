// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A sleep hold kept by a helper process the engine owns: {@code systemd-inhibit} on Linux, {@code
 * caffeinate} on macOS. The hold lasts as long as the process; {@link #close} ends it and reaps it.
 * The helper also ends with the engine, so a killed engine leaves no hold behind: {@code
 * systemd-inhibit} runs {@code cat} on the engine's pipe, which reads end-of-input when the engine
 * goes, and {@code caffeinate -w} watches the engine's pid.
 */
final class ChildHold implements PowerHost.Hold {

    /** Starts a helper process from its argv: a fake in tests. */
    interface Starter extends Function<List<String>, @Nullable Process> {}

    /** The real starter: output discarded, stdin a pipe the engine never writes. */
    static final Starter PROCESSES = argv -> {
        try {
            return new ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            return null;
        }
    };

    private final Process process;

    private ChildHold(Process process) {
        this.process = process;
    }

    /** The hold {@code argv} keeps, or null when it did not start or exited at once. */
    static @Nullable ChildHold start(Starter starter, List<String> argv) {
        Process p = starter.apply(argv);
        if (p == null) return null;
        try {
            // A helper that cannot take the hold (no logind, no session bus) exits straight away.
            if (p.waitFor(200, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Processes.killTree(p);
            return null;
        }
        return new ChildHold(p);
    }

    @Override
    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Already closed: the helper is going.
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                Processes.killTree(process);
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Processes.killTree(process);
        }
    }
}
