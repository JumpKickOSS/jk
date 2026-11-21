// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's own cgroup found through {@code /proc/self/cgroup}, and the memory readers that
 * walk from it to the mount's root: a limit on a unit or slice above the engine bounds it.
 */
class OwnCgroupTest {

    private static final long MIB = 1024L * 1024L;

    @TempDir
    Path root;

    @Test
    void the_chain_runs_from_the_named_group_up_to_the_mount_root() throws Exception {
        Path scope = group("user.slice/app.slice/jk.scope");
        self("0::/user.slice/app.slice/jk.scope\n");
        Path mount = root.resolve("sys/fs/cgroup");
        assertThat(OwnCgroup.own(root)).isEqualTo(scope);
        assertThat(OwnCgroup.chain(root))
                .containsExactly(scope, mount.resolve("user.slice/app.slice"), mount.resolve("user.slice"), mount);
    }

    @Test
    void a_container_root_line_is_the_mount_root_alone() throws Exception {
        group("");
        self("0::/\n");
        assertThat(OwnCgroup.chain(root)).containsExactly(root.resolve("sys/fs/cgroup"));
    }

    @Test
    void no_unified_line_an_escape_or_a_missing_group_falls_back_to_the_mount_root() throws Exception {
        Path mount = root.resolve("sys/fs/cgroup");
        Files.createDirectories(mount);
        self("4:memory:/x\n");
        assertThat(OwnCgroup.own(root)).isNull();
        assertThat(OwnCgroup.chain(root)).containsExactly(mount);
        self("0::/../../etc\n");
        assertThat(OwnCgroup.own(root)).isNull();
        self("0::/system.slice/docker-abc.scope\n");
        assertThat(OwnCgroup.own(root)).isNull();
        assertThat(OwnCgroup.chain(root)).containsExactly(mount);
    }

    @Test
    void a_usage_of_zero_is_an_empty_group_where_a_limit_of_zero_is_none() {
        assertThat(OwnCgroup.usage("0\n")).isZero();
        assertThat(OwnCgroup.limit("0\n")).isEqualTo(-1);
        assertThat(OwnCgroup.usage("4096\n")).isEqualTo(4096);
        assertThat(OwnCgroup.usage("")).isEqualTo(-1);
        assertThat(OwnCgroup.usage("-1")).isEqualTo(-1);
        assertThat(OwnCgroup.usage(null)).isEqualTo(-1);
    }

    @Test
    void the_tightest_limit_is_the_smallest_finite_memory_max_on_the_chain() throws Exception {
        Path scope = group("user.slice/jk.scope");
        self("0::/user.slice/jk.scope\n");
        Files.writeString(scope.resolve("memory.max"), "max\n");
        Files.writeString(root.resolve("sys/fs/cgroup/user.slice/memory.max"), 800 * MIB + "\n");
        assertThat(OwnCgroup.tightestMax(OwnCgroup.chain(root))).isEqualTo(800 * MIB);
        Files.writeString(scope.resolve("memory.max"), 500 * MIB + "\n");
        assertThat(OwnCgroup.tightestMax(OwnCgroup.chain(root))).isEqualTo(500 * MIB);
    }

    @Test
    void memory_probe_reads_a_slice_limit_above_the_engine_and_the_least_room_under_any_limit() throws Exception {
        Path scope = group("user.slice/jk.scope");
        Path slice = root.resolve("sys/fs/cgroup/user.slice");
        self("0::/user.slice/jk.scope\n");
        Files.writeString(scope.resolve("memory.max"), "max\n");
        Files.writeString(scope.resolve("memory.current"), 100 * MIB + "\n");
        Files.writeString(slice.resolve("memory.max"), 1000 * MIB + "\n");
        Files.writeString(slice.resolve("memory.current"), 700 * MIB + "\n");
        Files.writeString(slice.resolve("memory.stat"), "anon 1\ninactive_file " + 100 * MIB + "\n");

        MemoryProbe.CgroupRoom room = Objects.requireNonNull(MemoryProbe.cgroupRoom(OwnCgroup.chain(root)));
        assertThat(room.maxBytes()).isEqualTo(1000 * MIB);
        assertThat(room.availableBytes()).isEqualTo(400 * MIB);

        // A larger limit lower down with less room left under it is the one that binds.
        Files.writeString(scope.resolve("memory.max"), 1200 * MIB + "\n");
        Files.writeString(scope.resolve("memory.current"), 1100 * MIB + "\n");
        room = Objects.requireNonNull(MemoryProbe.cgroupRoom(OwnCgroup.chain(root)));
        assertThat(room.maxBytes()).isEqualTo(1000 * MIB);
        assertThat(room.availableBytes()).isEqualTo(100 * MIB);
    }

    @Test
    void memory_probe_has_no_room_without_a_finite_limit() throws Exception {
        Path scope = group("jk.scope");
        self("0::/jk.scope\n");
        Files.writeString(scope.resolve("memory.max"), "max\n");
        assertThat(MemoryProbe.cgroupRoom(OwnCgroup.chain(root))).isNull();
        assertThat(MemoryProbe.cgroupV1Room(-1, 5)).isNull();
        MemoryProbe.CgroupRoom v1 = Objects.requireNonNull(MemoryProbe.cgroupV1Room(1000, 300));
        assertThat(v1.availableBytes()).isEqualTo(700);
        assertThat(Objects.requireNonNull(MemoryProbe.cgroupV1Room(1000, -1)).measured())
                .isFalse();
    }

    @Test
    void overbook_signals_take_the_least_free_and_the_worst_pressure_on_the_chain() throws Exception {
        Path scope = group("user.slice/jk.scope");
        Path slice = root.resolve("sys/fs/cgroup/user.slice");
        self("0::/user.slice/jk.scope\n");
        Files.writeString(scope.resolve("memory.max"), "max\n");
        Files.writeString(scope.resolve("memory.pressure"), pressure(0.5, 0.0));
        Files.writeString(slice.resolve("memory.max"), 1000 * MIB + "\n");
        Files.writeString(slice.resolve("memory.current"), 600 * MIB + "\n");
        Files.writeString(slice.resolve("memory.pressure"), pressure(7.0, 2.0));
        List<Path> chain = OwnCgroup.chain(root);

        assertThat(OverbookSignals.ownFree(chain)).isEqualTo(400 * MIB);
        OverbookSignals.Pressure worst = Objects.requireNonNull(OverbookSignals.ownPressure(chain));
        assertThat(worst.some()).isEqualTo(7.0);
        assertThat(worst.full()).isEqualTo(2.0);

        Files.writeString(slice.resolve("memory.max"), "max\n");
        Files.delete(slice.resolve("memory.pressure"));
        assertThat(OverbookSignals.ownFree(chain)).isEqualTo(-1);
        assertThat(Objects.requireNonNull(OverbookSignals.ownPressure(chain)).some())
                .isEqualTo(0.5);
    }

    private Path group(String rel) throws Exception {
        Path dir = rel.isEmpty()
                ? root.resolve("sys/fs/cgroup")
                : root.resolve("sys/fs/cgroup").resolve(rel);
        Files.createDirectories(dir);
        return dir;
    }

    private void self(String text) throws Exception {
        Files.createDirectories(root.resolve("proc/self"));
        Files.writeString(root.resolve("proc/self/cgroup"), text);
    }

    private static String pressure(double some, double full) {
        return "some avg10=" + some + " avg60=0.00 avg300=0.00 total=0\nfull avg10=" + full
                + " avg60=0.00 avg300=0.00 total=0\n";
    }
}
