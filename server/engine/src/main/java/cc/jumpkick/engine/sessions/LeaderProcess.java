// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import java.util.Optional;

/** The process whose exit ends a session: a CLI request's session leader, its terminal's shell or its agent. */
final class LeaderProcess {

    private LeaderProcess() {}

    /**
     * Whether {@code leader} ({@code <pid>@<start millis>}) names a running process. A start of 0, or
     * one the process table will not tell, matches any process on the pid; one unreadable is alive.
     */
    static boolean alive(String leader) {
        int at = leader.indexOf('@');
        long pid;
        long started;
        try {
            pid = Long.parseLong(at < 0 ? leader : leader.substring(0, at));
            started = at < 0 ? 0L : Long.parseLong(leader.substring(at + 1));
        } catch (NumberFormatException e) {
            return true;
        }
        Optional<ProcessHandle> p = ProcessHandle.of(pid);
        if (p.isEmpty() || !p.get().isAlive()) return false;
        if (started <= 0) return true;
        return p.get()
                .info()
                .startInstant()
                .map(i -> Math.abs(i.toEpochMilli() - started) < 1_000)
                .orElse(true);
    }
}
