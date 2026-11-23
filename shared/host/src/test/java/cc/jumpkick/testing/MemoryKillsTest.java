// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * A failure aborts only when the kernel's {@code oom_kill} counter moved after the mark, and the
 * counter read is the outermost one above the process: a box's limit, not the test's own leaf.
 */
class MemoryKillsTest {

    @TempDir
    Path tmp;

    @Test
    void a_kill_after_the_mark_aborts_naming_the_count_and_the_cgroup() throws IOException {
        Path sys = tmp.resolve("sys");
        events(sys.resolve("box"), 2);
        events(sys.resolve("box/session/worker"), 0);
        MemoryKills kills = MemoryKills.mark(procSelfCgroup("0::/box/session/worker\n"), sys);

        events(sys.resolve("box"), 4);

        assertThat(kills.reason("the build"))
                .isEqualTo("the build failed while the kernel killed 2 processes for memory (oom_kill in cgroup"
                        + " /box went from 2 to 4): the host ran out of memory, which says nothing about the code"
                        + " under test");
        assertThatThrownBy(() -> kills.abortIfAny("the build"))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("killed 2 processes");
    }

    @Test
    void no_kill_since_the_mark_leaves_the_failure_standing() throws IOException {
        Path sys = tmp.resolve("sys");
        events(sys.resolve("box"), 7);
        MemoryKills kills = MemoryKills.mark(procSelfCgroup("0::/box\n"), sys);

        assertThat(kills.reason("the build")).isNull();
        kills.abortIfAny("the build");
    }

    @Test
    void a_container_root_counts_for_the_whole_box() throws IOException {
        Path sys = tmp.resolve("sys");
        events(sys, 0);
        MemoryKills kills = MemoryKills.mark(procSelfCgroup("0::/\n"), sys);

        events(sys, 1);

        assertThat(kills.reason("jk native")).contains("killed 1 process for memory (oom_kill in cgroup / went");
    }

    @Test
    void without_cgroup_v2_nothing_aborts() throws IOException {
        Path sys = tmp.resolve("sys");
        events(sys.resolve("box"), 0);
        MemoryKills v1 = MemoryKills.mark(procSelfCgroup("1:memory:/box\n"), sys);
        MemoryKills none = MemoryKills.mark(tmp.resolve("no-proc"), sys);

        events(sys.resolve("box"), 3);

        assertThat(v1.reason("the build")).isNull();
        assertThat(none.reason("the build")).isNull();
    }

    private Path procSelfCgroup(String text) throws IOException {
        return Files.writeString(tmp.resolve("cgroup"), text);
    }

    /** Writes a {@code memory.events} under {@code dir} whose {@code oom_kill} is {@code kills}. */
    private static Path events(Path dir, long kills) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(
                dir.resolve("memory.events"),
                "low 0\nhigh 0\nmax 12\noom 3\noom_kill " + kills + "\noom_group_kill 0\n");
    }
}
