// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.testing.Sleepers;
import cc.jumpkick.testing.Symlinks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Engines that draw on one memory budget share one lease file, whatever home each runs from: the
 * cgroup whose limit sets the budget names the directory, the engines inside its engines' workers
 * share one beside it, and a directory someone else could write is refused for the engine's own.
 */
class LeaseScopeTest {

    private static final long GIB = 1L << 30;

    @TempDir
    Path root;

    @Test
    void engines_with_different_homes_in_one_cgroup_lease_from_one_total() throws Exception {
        Path shared = root.resolve("var/tmp/jk-me-leases").resolve(LeaseScope.HOST);
        Path homeA = Files.createDirectories(root.resolve("home-a/engine"));
        Path homeB = Files.createDirectories(root.resolve("home-b/engine"));
        HostLeases a = share(LeaseScope.usable(shared, homeA), 1001);
        HostLeases b = share(LeaseScope.usable(shared, homeB), 1002);

        assertThat(a.publish(0, new HostLeases.Line(8 * GIB, 8, 6 * GIB, 2, 0, List.of())))
                .isTrue();

        assertThat(b.fresh().bytes()).isEqualTo(6 * GIB);
        assertThat(b.fresh().jvms()).isEqualTo(2);
        assertThat(b.budget(4 * GIB)).isEqualTo(8 * GIB);
    }

    @Test
    void the_budget_is_the_group_with_the_tightest_limit_so_sibling_scopes_share_it() throws Exception {
        Path slice = group("user.slice");
        Files.writeString(slice.resolve("memory.max"), 16 * GIB + "\n");
        Files.writeString(group("user.slice/a.scope").resolve("memory.max"), "max\n");
        group("user.slice/b.scope");
        Files.writeString(root.resolve("sys/fs/cgroup/memory.max"), "max\n");

        self("0::/user.slice/a.scope\n");
        String fromA = LeaseScope.budget(root, true);
        self("0::/user.slice/b.scope\n");
        String fromB = LeaseScope.budget(root, true);

        assertThat(fromA).startsWith("cgroup-").isEqualTo(fromB);
        assertThat(LeaseScope.tightestGroup(OwnCgroup.chain(root))).isEqualTo(slice);
    }

    @Test
    void a_limit_on_ones_own_group_names_a_budget_apart_from_the_unlimited_one() throws Exception {
        group("user.slice/a.scope");
        Files.writeString(group("user.slice/b.scope").resolve("memory.max"), 4 * GIB + "\n");

        self("0::/user.slice/a.scope\n");
        assertThat(LeaseScope.budget(root, true)).isEqualTo(LeaseScope.HOST);
        self("0::/user.slice/b.scope\n");
        assertThat(LeaseScope.budget(root, true)).startsWith("cgroup-");
    }

    @Test
    void no_cgroup_limit_or_another_os_is_the_host() throws Exception {
        group("");
        self("0::/\n");
        assertThat(LeaseScope.budget(root, true)).isEqualTo(LeaseScope.HOST);
        Files.writeString(root.resolve("sys/fs/cgroup/memory.max"), 2 * GIB + "\n");
        assertThat(LeaseScope.budget(root, true)).startsWith("cgroup-");
        assertThat(LeaseScope.budget(root, false)).isEqualTo(LeaseScope.HOST);
    }

    @Test
    void the_shared_directory_is_made_owner_only() throws Exception {
        assumePosix();
        Path shared = root.resolve("jk-me-leases/host");
        assertThat(LeaseScope.usable(shared, root.resolve("home"))).isEqualTo(shared);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shared)))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shared.getParent())))
                .isEqualTo("rwx------");
    }

    @Test
    void a_parent_another_user_could_write_or_a_link_keeps_the_leases_at_home() throws Exception {
        assumePosix();
        Path home = root.resolve("home");
        Path open = Files.createDirectory(root.resolve("jk-open-leases"));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThat(LeaseScope.usable(open.resolve("host"), home)).isEqualTo(home);

        Symlinks.assumeSupported();
        Path elsewhere = Files.createDirectory(root.resolve("elsewhere"));
        Path link = Symlinks.create(root.resolve("jk-link-leases"), elsewhere);
        assertThat(LeaseScope.usable(link.resolve("host"), home)).isEqualTo(home);
    }

    /**
     * Two pooled test JVMs of one engine, each in a group of its own capped below the budget, each
     * start an engine: both lease in one file beside the outer engine's, not one each.
     */
    @Test
    void engines_inside_two_workers_of_one_engine_lease_from_one_total() throws Exception {
        Path leases = root.resolve("var/tmp/jk-me-leases");
        Process first = Sleepers.sleeper(120).start();
        Process second = Sleepers.sleeper(120).start();
        try {
            HostLeases.Owner outer = HostLeases.Owner.current();
            assumeTrue(outer.startedAtMillis() > 0);
            Path host = LeaseScope.usable(leases.resolve(LeaseScope.HOST), root.resolve("home-outer"));
            assertThat(host).isEqualTo(leases.resolve(LeaseScope.HOST));
            record(host, outer, worker(first) + " " + worker(second));
            Files.createDirectories(leases.resolve("cgroup-" + first.pid()));
            Files.createDirectories(leases.resolve("cgroup-" + second.pid()));

            Path fromFirst = LeaseScope.innerOf(leases, LeaseScope.ancestry(first.toHandle()));
            Path fromSecond = LeaseScope.innerOf(leases, LeaseScope.ancestry(second.toHandle()));

            Path nested = host.resolve(LeaseScope.NESTED);
            assertThat(fromFirst).isEqualTo(nested).isEqualTo(fromSecond);
            Path shared = LeaseScope.usable(nested, root.resolve("home-a"));
            assertThat(shared).isEqualTo(nested);
            HostLeases a = share(shared, 1001);
            HostLeases b = share(LeaseScope.usable(nested, root.resolve("home-b")), 1002);
            assertThat(a.publish(0, new HostLeases.Line(6 * GIB, 8, 5 * GIB, 2, 0, List.of())))
                    .isTrue();
            assertThat(b.fresh().bytes())
                    .as("the first worker's inner engine holds")
                    .isEqualTo(5 * GIB);
            assertThat(b.budget(6 * GIB)).isEqualTo(6 * GIB);
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
        }
    }

    @Test
    void an_engine_inside_a_worker_of_an_inner_engine_leases_a_level_further_down() throws Exception {
        Path leases = root.resolve("var/tmp/jk-me-leases");
        Process worker = Sleepers.sleeper(120).start();
        try {
            HostLeases.Owner inner = HostLeases.Owner.current();
            assumeTrue(inner.startedAtMillis() > 0);
            Path nested =
                    Files.createDirectories(leases.resolve(LeaseScope.HOST).resolve(LeaseScope.NESTED));
            record(nested, inner, worker(worker));

            assertThat(LeaseScope.innerOf(leases, LeaseScope.ancestry(worker.toHandle())))
                    .isEqualTo(nested.resolve(LeaseScope.NESTED));
        } finally {
            worker.destroyForcibly();
        }
    }

    @Test
    void a_worker_pid_since_reused_a_gone_engine_or_no_engine_leaves_the_budget_s_own_file() throws Exception {
        ProcessHandle engine = ProcessHandle.current().parent().orElse(null);
        assumeTrue(engine != null && engine.info().startInstant().isPresent());
        Path leases = root.resolve("var/tmp/jk-me-leases");
        HostLeases.Owner self = HostLeases.Owner.current();
        assumeTrue(self.startedAtMillis() > 0);
        var ancestors = LeaseScope.ancestry(ProcessHandle.current());

        assertThat(LeaseScope.innerOf(leases, ancestors)).as("no directory yet").isNull();
        Path shared = Files.createDirectories(leases.resolve(LeaseScope.HOST));
        assertThat(LeaseScope.innerOf(leases, ancestors)).as("no file").isNull();

        record(shared, HostLeases.Owner.of(engine), self.pid() + ":" + (self.startedAtMillis() - 60_000));
        assertThat(LeaseScope.innerOf(leases, ancestors)).isNull();

        record(shared, new HostLeases.Owner(Integer.MAX_VALUE - 1L, 1), self.pid() + ":" + self.startedAtMillis());
        assertThat(LeaseScope.innerOf(leases, ancestors)).isNull();
    }

    /**
     * A test's engine waits less than its test does: an integration method stops at 5m, so a lease
     * that gives up first puts who held the memory in the failure, with room for the build before it.
     * An engine of the host keeps the ten-minute line.
     */
    @Test
    void an_engine_inside_a_worker_gives_up_its_lease_well_inside_an_integration_method_s_timeout() {
        Duration nested = new LeaseScope.Shared(root, true).waitAtMost();
        assertThat(nested).isLessThanOrEqualTo(Duration.ofMinutes(5).dividedBy(2));
        assertThat(new LeaseScope.Shared(root, false).waitAtMost()).isEqualTo(WorkerLeases.WAIT_AT_MOST);
    }

    @Test
    void the_ancestry_starts_at_the_process_and_climbs_to_its_parent() {
        ProcessHandle self = ProcessHandle.current();
        var ancestry = List.copyOf(LeaseScope.ancestry(self));
        assertThat(ancestry.getFirst()).isEqualTo(self.pid());
        self.parent().ifPresent(parent -> assertThat(ancestry.get(1)).isEqualTo(parent.pid()));
    }

    @Test
    void the_user_name_is_kept_to_a_safe_directory_name() {
        assertThat(LeaseScope.userDir("bsant")).isEqualTo("jk-bsant-leases");
        assertThat(LeaseScope.userDir("DOMAIN\\me x")).isEqualTo("jk-DOMAIN_me_x-leases");
        assertThat(LeaseScope.userDir("../..")).isEqualTo("jk-.._..-leases");
        assertThat(LeaseScope.userDir(null)).isEqualTo("jk-_-leases");
    }

    private static HostLeases share(Path dir, long pid) {
        return new HostLeases(dir, new HostLeases.Owner(pid, 0), owner -> true, workers -> 0);
    }

    /** {@code dir}'s lease file holding one line: {@code engine} with an 8 GiB budget and {@code workers}. */
    private static void record(Path dir, HostLeases.Owner engine, String workers) throws Exception {
        Files.writeString(
                dir.resolve(HostLeases.FILE),
                engine.pid() + " " + engine.startedAtMillis() + " " + 8 * GIB + " 8 0 1 0 " + workers + "\n");
    }

    /** {@code process} as the file names a worker: {@code <pid>:<start-epoch-millis>}. */
    private static String worker(Process process) {
        HostLeases.Owner owner = HostLeases.Owner.of(process.toHandle());
        assumeTrue(owner.startedAtMillis() > 0);
        return owner.pid() + ":" + owner.startedAtMillis();
    }

    private Path group(String rel) throws Exception {
        return Files.createDirectories(root.resolve("sys/fs/cgroup").resolve(rel));
    }

    private void self(String body) throws Exception {
        Files.createDirectories(root.resolve("proc/self"));
        Files.writeString(root.resolve("proc/self/cgroup"), body);
    }

    private void assumePosix() {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
    }
}
