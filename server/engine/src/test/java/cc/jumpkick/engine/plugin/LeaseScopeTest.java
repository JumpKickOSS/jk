// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.testing.Symlinks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Engines that draw on one memory budget share one lease file, whatever home each runs from: the
 * cgroup whose limit sets the budget names the directory, and a directory someone else could write
 * is refused for the engine's own.
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
