// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The kernel's count of processes it killed for memory, so an end-to-end test that fails while the
 * host ran out says so instead of failing.
 *
 * <p>A slow-tier test builds through forked workers and {@code native-image}; on a box whose memory
 * cgroup is smaller than the suite's peak, the kernel kills one of them and the build fails with an
 * error that names neither memory nor the kernel. That failure is the host's, and a red test then
 * reads as a regression in the code. {@link #mark} reads the cgroup v2 {@code oom_kill} counter
 * before the work; {@link #abortIfAny} aborts the test, naming the count and the cgroup, when the
 * counter moved while the work ran. Call it only once the work has failed: a kill elsewhere on the
 * host must never turn a passing test into a skip.
 *
 * <p>The counter read is the outermost one this process can see: its own cgroup's ancestors, up to
 * the cgroup root, which in a container is the container's memory limit. {@code memory.events} is
 * hierarchical, so it counts a kill of any process under it, a sibling worker's included. Where
 * there is no cgroup v2 (macOS, Windows, cgroup v1) nothing is read and nothing aborts.
 */
public final class MemoryKills {

    private final Path sysFsCgroup;
    private final @Nullable Path events;
    private final long before;

    private MemoryKills(Path sysFsCgroup, @Nullable Path events) {
        this.sysFsCgroup = sysFsCgroup;
        this.events = events;
        this.before = events == null ? -1 : oomKills(events);
    }

    /** The {@code oom_kill} count now, for {@link #abortIfAny} after the work. */
    public static MemoryKills mark() {
        return mark(Path.of("/proc/self/cgroup"), Path.of("/sys/fs/cgroup"));
    }

    /** As {@link #mark()}, with this process's {@code cgroup} file and the cgroup v2 mount given. */
    static MemoryKills mark(Path procSelfCgroup, Path sysFsCgroup) {
        return new MemoryKills(sysFsCgroup, outermostEvents(procSelfCgroup, sysFsCgroup));
    }

    /**
     * Abort the calling test when the kernel killed a process for memory since {@link #mark}: {@code
     * what} failed, and the host running out is the cause this names.
     */
    public void abortIfAny(String what) {
        String reason = reason(what);
        if (reason != null) abort(reason);
    }

    /** The abort reason {@link #abortIfAny} gives, or {@code null} when nothing was killed since the mark. */
    @Nullable
    String reason(String what) {
        if (events == null || before < 0) return null;
        long after = oomKills(events);
        if (after <= before) return null;
        String cgroup =
                "/" + sysFsCgroup.relativize(events.getParent()).toString().replace('\\', '/');
        long killed = after - before;
        return what + " failed while the kernel killed " + killed + (killed == 1 ? " process" : " processes")
                + " for memory (oom_kill in cgroup " + cgroup + " went from " + before + " to " + after
                + "): the host ran out of memory, which says nothing about the code under test";
    }

    /**
     * The {@code memory.events} of the outermost cgroup above this process that has one, or {@code
     * null}: the path a cgroup v2 {@code 0::<path>} line names, walked up to the root.
     */
    private static @Nullable Path outermostEvents(Path procSelfCgroup, Path sysFsCgroup) {
        String own = unifiedPath(procSelfCgroup);
        if (own == null) return null;
        Path dir = sysFsCgroup;
        Path candidate = dir.resolve("memory.events");
        if (Files.isReadable(candidate)) return candidate;
        for (String part : own.split("/")) {
            if (part.isEmpty()) continue;
            dir = dir.resolve(part);
            candidate = dir.resolve("memory.events");
            if (Files.isReadable(candidate)) return candidate;
        }
        return null;
    }

    /** The path of the cgroup v2 {@code 0::} line, or {@code null} when there is none. */
    private static @Nullable String unifiedPath(Path procSelfCgroup) {
        try {
            for (String line : Files.readAllLines(procSelfCgroup, StandardCharsets.UTF_8)) {
                if (line.startsWith("0::")) return line.substring(3);
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /** The {@code oom_kill} value of a {@code memory.events}, or {@code -1} when unreadable. */
    private static long oomKills(Path events) {
        try {
            for (String line : Files.readAllLines(events, StandardCharsets.UTF_8)) {
                if (line.startsWith("oom_kill "))
                    return Long.parseLong(line.substring("oom_kill ".length()).strip());
            }
        } catch (IOException | RuntimeException unreadable) {
            return -1;
        }
        return -1;
    }
}
