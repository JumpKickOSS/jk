// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The engine process that owns an in-flight row: its pid and, when the OS says, the instant it
 * started, so a pid the OS has handed to a later process does not read as the engine.
 */
public record EngineOwner(long pid, long startedAtMillis) {

    /** Start-instant slack between the sidecar and the OS: both read the same clock. */
    private static final long START_SLACK_MS = 5_000;

    public static EngineOwner current() {
        ProcessHandle self = ProcessHandle.current();
        return new EngineOwner(
                self.pid(),
                self.info().startInstant().map(Instant::toEpochMilli).orElse(0L));
    }

    static @Nullable EngineOwner parse(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        try {
            long pid = Long.parseLong(parts[0]);
            long started = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
            return new EngineOwner(pid, started);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String line() {
        return pid + " " + startedAtMillis + System.lineSeparator();
    }

    /** {@code true} while a process with this pid runs and started when the sidecar says it did. */
    public boolean alive() {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) return false;
        if (startedAtMillis <= 0) return true;
        Optional<Instant> started = handle.get().info().startInstant();
        return started.isEmpty() || Math.abs(started.get().toEpochMilli() - startedAtMillis) <= START_SLACK_MS;
    }
}
