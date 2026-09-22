// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.testing.Symlinks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sandbox home a forked test JVM runs against. Three properties carry the weight: it is not
 * inside the project under test, it is short, and it moves with {@code JK_HOME}.
 *
 * <p>The first is the one that costs work — a directory shaped like jk's store, inside a source
 * tree, is what a stray {@code git} command resolves out of and resets. The second is why the root is
 * a fixed short suffix of the product home rather than something under the platform temp dir.
 */
class TestHomesTest {

    private static final long NO_CAP = Long.MAX_VALUE;

    @Test
    void the_home_is_not_inside_the_project_under_test(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("ws/clients/cli"));

        Path home = TestHomes.pathFor(module);

        assertThat(home.toString()).doesNotStartWith(module.toString());
        assertThat(home.toString()).doesNotStartWith(tmp.resolve("ws").toString());
        assertThat(home).isAbsolute();
    }

    /** An engine already running in a sandbox keeps its own sandboxes inside it, not in the real home. */
    @Test
    void the_root_moves_with_jk_home(@TempDir Path tmp) {
        System.setProperty("jk.env.JK_HOME", tmp.resolve("relocated").toString());
        try {
            assertThat(TestHomes.root()).isEqualTo(tmp.resolve("relocated").resolve("test-homes"));
        } finally {
            System.clearProperty("jk.env.JK_HOME");
        }
    }

    /**
     * Two checkouts of one repository have identically named modules. Sharing a store between them
     * would race, so the key is the path and not the name.
     */
    @Test
    void modules_of_the_same_name_in_different_checkouts_get_different_homes(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a/jk/clients/cli"));
        Path b = Files.createDirectories(tmp.resolve("b/jk/clients/cli"));

        assertThat(TestHomes.keyFor(a)).isNotEqualTo(TestHomes.keyFor(b));
        assertThat(TestHomes.keyFor(a))
                .isEqualTo(TestHomes.keyFor(a.toAbsolutePath().normalize()));
    }

    /** One directory reached two ways is one module, so it gets one slot. */
    @Test
    void a_module_reached_through_a_link_keys_as_the_directory_itself(@TempDir Path tmp) throws Exception {
        Path real = Files.createDirectories(tmp.resolve("real/module"));
        Path link = Symlinks.create(tmp.resolve("link"), requireNonNull(real.getParent()));

        assertThat(TestHomes.keyFor(link.resolve("module"))).isEqualTo(TestHomes.keyFor(real));
    }

    /**
     * The budget: whatever the product home costs, this class adds a fixed short suffix to it —
     * {@code test-homes/<12 hex>/home} — which is what keeps a Windows path under its ceiling.
     */
    @Test
    void the_path_beneath_the_home_is_a_fixed_short_suffix() {
        Path module = Path.of(System.getProperty("user.home"), "src", "oss", "jk", "clients", "cli");

        Path home = TestHomes.pathFor(module);

        assertThat(home).startsWithRaw(JkDirs.home());
        assertThat(JkDirs.home().relativize(home).toString()).hasSizeLessThanOrEqualTo(28);
        assertThat(TestHomes.keyFor(module)).hasSize(12).matches("[0-9a-f]+");
    }

    @Test
    void a_home_is_created_and_stamped_so_the_next_reap_spares_it(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("homes");
        Path home = Files.createDirectories(root.resolve("abc123abc123"));
        TestHomes.stamp(home, root);

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP).removed())
                .as("a home in use is not reaped")
                .isZero();
        assertThat(home).isDirectory();
    }

    /** Warm is not unbounded: a project that moved on stops paying rent. */
    @Test
    void a_home_untouched_past_the_window_is_reaped(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path stale = Files.createDirectories(root.resolve("dddddddddddd"));
        Files.createDirectories(stale.resolve("store/templates"));
        TestHomes.stamp(stale, root);
        age(stale.resolve(".used-at"), Duration.ofDays(TestHomes.KEEP_DAYS + 1));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP).removed())
                .isEqualTo(1);
        assertThat(stale).doesNotExist();
    }

    /** Nothing else writes here, so an unstamped directory is a crashed run's leftover. */
    @Test
    void an_unstamped_leftover_is_reaped(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path orphan = Files.createDirectories(root.resolve("eeeeeeeeeeee"));
        Files.createDirectories(orphan.resolve("store"));
        age(orphan, Duration.ofDays(TestHomes.KEEP_DAYS + 1));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP).removed())
                .isEqualTo(1);
        assertThat(orphan).doesNotExist();
    }

    /** Past the byte cap, the least recently used slots go first and the reap stops once the rest fit. */
    @Test
    void over_the_byte_cap_the_least_recently_used_slot_goes_first(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path older = slotWithBytes(root, "aaaaaaaaaaaa", 100);
        Path newer = slotWithBytes(root, "bbbbbbbbbbbb", 100);
        age(older.resolve(".used-at"), Duration.ofDays(1));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), 150).removed())
                .isEqualTo(1);
        assertThat(older).doesNotExist();
        assertThat(newer).isDirectory();
    }

    /**
     * Several gates share one machine: a slot stamped within the hold window is one a running suite
     * may be reading its dependency jars out of, so the byte cap never takes it.
     */
    @Test
    void over_the_byte_cap_a_slot_inside_the_hold_window_is_kept(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path running = slotWithBytes(root, "aaaaaaaaaaaa", 100);
        Path launching = slotWithBytes(root, "bbbbbbbbbbbb", 100);
        age(running.resolve(".used-at"), Duration.ofHours(TestHomes.HOLD_HOURS - 1));

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), 150).removed())
                .isZero();
        assertThat(running).isDirectory();
        assertThat(launching).isDirectory();
    }

    /**
     * A running suite reads its jars out of a held slot, however long ago the slot was stamped: the
     * byte cap takes the unheld neighbour instead, and the slot is reclaimable once the hold is
     * released.
     */
    @Test
    void over_the_byte_cap_a_slot_held_by_a_live_launch_is_kept_until_released(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path running = slotWithBytes(root, "aaaaaaaaaaaa", 100);
        Path idle = slotWithBytes(root, "bbbbbbbbbbbb", 100);
        age(running.resolve(".used-at"), Duration.ofDays(3));
        age(idle.resolve(".used-at"), Duration.ofDays(2));

        try (TestHomes.Hold held = TestHomes.hold(running)) {
            assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), 150)
                            .removed())
                    .isEqualTo(1);
            assertThat(running).isDirectory();
            assertThat(idle).doesNotExist();
        }
        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), 50).removed())
                .isEqualTo(1);
        assertThat(running).doesNotExist();
    }

    /** A hold left by a process that has exited holds nothing: the slot is reclaimed and the hold with it. */
    @Test
    void a_slot_whose_hold_names_a_dead_process_is_reclaimable(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path abandoned = slotWithBytes(root, "aaaaaaaaaaaa", 100);
        age(abandoned.resolve(".used-at"), Duration.ofDays(2));
        Path holds = Files.createDirectories(abandoned.resolve(".holds"));
        Files.writeString(holds.resolve(deadPid() + "-1"), deadPid() + " 0\n");

        assertThat(TestHomes.held(abandoned)).isFalse();
        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), 50).removed())
                .isEqualTo(1);
        assertThat(abandoned).doesNotExist();
    }

    /** A pid the OS has reused since the hold was written belongs to another process. */
    @Test
    void a_hold_whose_pid_started_at_another_time_is_stale() {
        ProcessHandle self = ProcessHandle.current();
        assumeTrue(self.info().startInstant().isPresent());
        long started = self.info().startInstant().orElseThrow().toEpochMilli();

        assertThat(TestHomes.isLive(self.pid(), started)).isTrue();
        assertThat(TestHomes.isLive(self.pid(), started - Duration.ofMinutes(5).toMillis()))
                .isFalse();
    }

    /** The workspace's shared m2 lives in a slot with no home; stamped, it reads as in use, not as a leftover. */
    @Test
    void a_slot_prepared_without_a_home_is_stamped(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("ws"));
        Path slot = TestHomes.prepareSlot(workspace);

        assertThat(slot).isEqualTo(TestHomes.slotFor(workspace));
        assertThat(slot.resolve(".used-at")).isRegularFile();
        assertThat(slot.resolve("home")).doesNotExist();
    }

    @Test
    void under_the_byte_cap_nothing_recent_is_reaped(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        slotWithBytes(root, "aaaaaaaaaaaa", 100);
        slotWithBytes(root, "bbbbbbbbbbbb", 100);

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), 1000).removed())
                .isZero();
    }

    /**
     * A root of many idle slots is sized once: the walk's answer is recorded beside the stamp and
     * stands until the slot is used again, which a launch marks by re-stamping and a finished suite
     * by dropping the record.
     */
    @Test
    void an_idle_slot_is_sized_from_its_record_until_it_is_used_again(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path slot = slotWithBytes(root, "aaaaaaaaaaaa", 100);
        long now = System.currentTimeMillis();

        // The blob plus the stamp the slot carries; the record itself is never counted.
        long walked = TestHomes.reapStale(root, now, NO_CAP).bytes();
        assertThat(walked).isGreaterThanOrEqualTo(100);
        Path record = slot.resolve(TestHomes.SIZE);
        assertThat(record).hasContent(Long.toString(walked));

        // Grown without a use: the record still answers, so the pass walks nothing here.
        Files.write(slot.resolve("home/store/more"), new byte[50]);
        Files.setLastModifiedTime(record, FileTime.fromMillis(now + 5_000));
        assertThat(TestHomes.reapStale(root, now, NO_CAP).bytes()).isEqualTo(walked);

        // Used again: the stamp is newer than the record, so the slot is walked and re-recorded.
        Files.setLastModifiedTime(slot.resolve(".used-at"), FileTime.fromMillis(now + 10_000));
        assertThat(TestHomes.reapStale(root, now + 10_000, NO_CAP).bytes()).isEqualTo(walked + 50);
        assertThat(record).hasContent(Long.toString(walked + 50));

        // A suite that finished drops the record so the next pass measures what it left behind.
        try (TestHomes.Hold held = TestHomes.hold(slot)) {
            assertThat(slot.resolve(".holds")).isDirectory();
        }
        assertThat(record).doesNotExist();
    }

    @Test
    void a_missing_root_reaps_nothing_and_does_not_throw(@TempDir Path tmp) {
        assertThat(TestHomes.reapStale(tmp.resolve("never-created"), System.currentTimeMillis(), NO_CAP)
                        .removed())
                .isZero();
    }

    /**
     * The reap window is the one thing here that reads a clock, and it decides whether a directory is
     * deleted — so it is settled, not arranged around whatever now happens to be.
     */
    @Test
    void prepare_reaps_on_the_supplied_clock(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path old = Files.createDirectories(root.resolve("aaaaaaaaaaaa"));
        TestHomes.stamp(old, root);
        long wellPast = Files.getLastModifiedTime(old.resolve(".used-at")).toMillis()
                + Duration.ofDays(TestHomes.KEEP_DAYS + 5).toMillis();

        assertThat(TestHomes.reapStale(root, wellPast, NO_CAP).removed())
                .as("a clock moved past the window reaps it; no file timestamps were touched")
                .isEqualTo(1);
        assertThat(old).doesNotExist();
    }

    /**
     * The reap is every launch's, not the JVM's first: a slot that goes stale while an engine lives
     * is reaped by the next launch that is due, and a launch right after a quiet pass is spared the
     * listing.
     */
    @Test
    void every_due_launch_reaps_what_went_stale_since_the_last_quiet_pass(@TempDir Path tmp) throws Exception {
        System.setProperty("jk.env.JK_HOME", tmp.resolve("relocated").toString());
        try {
            Path root = Files.createDirectories(TestHomes.root());
            Path module = Files.createDirectories(tmp.resolve("module"));
            long t0 = System.currentTimeMillis();
            TestHomes.prepareSlot(module, at(t0));
            Path stale = Files.createDirectories(root.resolve("cccccccccccc"));
            TestHomes.stamp(stale, root);
            age(stale.resolve(".used-at"), Duration.ofDays(TestHomes.KEEP_DAYS + 1));

            TestHomes.prepareSlot(module, at(t0 + 1000));
            assertThat(stale)
                    .as("a launch right after a quiet pass skips its own")
                    .isDirectory();

            TestHomes.prepareSlot(module, at(t0 + TestHomes.REAP_EVERY_MILLIS));
            assertThat(stale)
                    .as("the next due launch reaps it, in the same JVM")
                    .doesNotExist();
            assertThat(TestHomes.slotFor(module).resolve(".used-at")).isRegularFile();
        } finally {
            System.clearProperty("jk.env.JK_HOME");
        }
    }

    /** A pass that removed something, or left the root over the cap, is followed by a full pass at the next launch. */
    @Test
    void a_pass_that_found_work_is_followed_by_another_at_the_next_launch(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        long now = System.currentTimeMillis();
        Path first = staleSlot(root, "aaaaaaaaaaaa");
        assertThat(TestHomes.reapIfDue(root, now, NO_CAP)).isTrue();
        assertThat(first).doesNotExist();

        Path second = staleSlot(root, "bbbbbbbbbbbb");
        assertThat(TestHomes.reapIfDue(root, now + 1000, NO_CAP))
                .as("the pass before removed a slot")
                .isTrue();
        assertThat(second).doesNotExist();
        assertThat(TestHomes.reapIfDue(root, now + 2000, NO_CAP))
                .as("so did the one before this")
                .isTrue();

        Path third = staleSlot(root, "cccccccccccc");
        assertThat(TestHomes.reapIfDue(root, now + 3000, NO_CAP))
                .as("the pass before was quiet")
                .isFalse();
        assertThat(third).isDirectory();

        Path held = slotWithBytes(root, "dddddddddddd", 100);
        long due = now + 2000 + TestHomes.REAP_EVERY_MILLIS;
        assertThat(TestHomes.reapIfDue(root, due, 50))
                .as("due again: the stale slot goes")
                .isTrue();
        assertThat(third).doesNotExist();
        assertThat(held).as("stamped just now: inside the hold window").isDirectory();
        assertThat(TestHomes.reapIfDue(root, due + 1000, 50))
                .as("the pass before removed a slot")
                .isTrue();
        assertThat(TestHomes.reapIfDue(root, due + 2000, 50))
                .as("over the cap, even when nothing can go, every launch looks again")
                .isTrue();
    }

    private static Path staleSlot(Path root, String key) throws Exception {
        Path slot = Files.createDirectories(root.resolve(key));
        TestHomes.stamp(slot, root);
        age(slot.resolve(".used-at"), Duration.ofDays(TestHomes.KEEP_DAYS + 1));
        return slot;
    }

    /** A clock stopped at {@code millis}. */
    private static Clock at(long millis) {
        return new Clock() {
            @Override
            public long millis() {
                return millis;
            }

            @Override
            public long nanos() {
                return millis * 1_000_000L;
            }
        };
    }

    /** A pid no process has: counted down from the top of the range until the OS knows nothing by it. */
    private static long deadPid() {
        return LongStream.iterate(4_000_000L, pid -> pid - 1)
                .filter(pid -> ProcessHandle.of(pid).isEmpty())
                .findFirst()
                .orElseThrow();
    }

    /**
     * What a suite's teardown relies on: the slots of fixture workspaces that no longer exist go
     * now, rather than waiting for something to launch into that root again. A slot whose module
     * is still there stays, and so does one a live launch holds — a nested root is shared with
     * whatever else the sandbox is running.
     */
    @Test
    void reap_now_takes_the_slots_of_modules_that_are_gone_and_leaves_the_rest(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("nested-homes"));
        Path liveModule = Files.createDirectories(tmp.resolve("fixture-still-here"));
        Path goneModule = Files.createDirectories(tmp.resolve("fixture-torn-down"));
        Path heldModule = Files.createDirectories(tmp.resolve("fixture-still-running"));

        Path live = slotWithBytes(root, "aaaaaaaaaaaa", 64);
        TestHomes.stamp(live, liveModule);
        Path dead = slotWithBytes(root, "bbbbbbbbbbbb", 64);
        TestHomes.stamp(dead, goneModule);
        Path heldDead = slotWithBytes(root, "cccccccccccc", 64);
        TestHomes.stamp(heldDead, heldModule);

        // Teardown: the fixture directories are removed when the forks exit.
        Files.delete(goneModule);
        Files.delete(heldModule);

        try (TestHomes.Hold held = TestHomes.hold(heldDead)) {
            assertThat(TestHomes.reapNow(root)).isEqualTo(1);
            assertThat(dead).doesNotExist();
            assertThat(live).as("its module is still on disk").isDirectory();
            assertThat(heldDead).as("a live launch is reading it").isDirectory();
        }
        assertThat(TestHomes.reapNow(root))
                .as("reclaimable once the hold is released")
                .isEqualTo(1);
        assertThat(heldDead).doesNotExist();
        assertThat(live).isDirectory();
    }

    private static Path slotWithBytes(Path root, String key, int bytes) throws Exception {
        Path slot = Files.createDirectories(root.resolve(key));
        Files.write(Files.createDirectories(slot.resolve("home/store")).resolve("blob"), new byte[bytes]);
        TestHomes.stamp(slot, root);
        return slot;
    }

    private static void age(Path path, Duration by) throws Exception {
        Files.setLastModifiedTime(
                path, FileTime.from(System.currentTimeMillis() - by.toMillis(), TimeUnit.MILLISECONDS));
    }

    // ---- a slot lives as long as its module -----------------------------------

    /**
     * A CLI fixture is a project under a temp dir, and its nested engine gives it a slot. The
     * fixture goes when the test ends; the slot must not stay for fourteen days per run.
     */
    @Test
    void a_slot_whose_module_directory_is_gone_is_reaped_at_the_next_launch(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path fixture = Files.createDirectories(tmp.resolve("junit-1234/app"));
        Path slot = Files.createDirectories(root.resolve(TestHomes.keyFor(fixture)));
        TestHomes.stamp(slot, fixture);
        Path living = Files.createDirectories(root.resolve(TestHomes.keyFor(tmp)));
        TestHomes.stamp(living, tmp);

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP).removed())
                .as("both modules exist")
                .isZero();

        PathUtil.deleteRecursivelyOrThrow(fixture);

        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP).removed())
                .isEqualTo(1);
        assertThat(slot).doesNotExist();
        assertThat(living)
                .as("the module that still exists keeps its slot, however fresh")
                .isDirectory();
    }

    /** The stamp names the module, so a reader can see what a slot was for. */
    @Test
    void the_stamp_names_the_module_it_was_handed_to(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("ws/lib"));
        Path slot = Files.createDirectories(tmp.resolve("homes").resolve(TestHomes.keyFor(module)));
        TestHomes.stamp(slot, module);

        assertThat(Files.readString(slot.resolve(".used-at")).lines().findFirst())
                .contains(module.toRealPath().toString());
        assertThat(TestHomes.moduleGone(slot)).isFalse();
    }

    /** A stamp that names no module — a note alone — is judged by its age. */
    @Test
    void a_stamp_without_a_module_path_is_kept_by_age_alone(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path slot = Files.createDirectories(root.resolve("eeeeeeeeeeee"));
        Files.writeString(slot.resolve(".used-at"), "jk test sandbox\n");

        assertThat(TestHomes.moduleGone(slot)).isFalse();
        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP).removed())
                .isZero();
        assertThat(slot).isDirectory();
    }

    /** A launch still reading the slot's jars keeps it, even after the module directory went. */
    @Test
    void a_held_slot_survives_its_module_going_away(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("homes"));
        Path fixture = Files.createDirectories(tmp.resolve("fixture"));
        Path slot = Files.createDirectories(root.resolve(TestHomes.keyFor(fixture)));
        TestHomes.stamp(slot, fixture);
        try (TestHomes.Hold hold = TestHomes.hold(slot)) {
            PathUtil.deleteRecursivelyOrThrow(fixture);
            assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP)
                            .removed())
                    .isZero();
            assertThat(slot).isDirectory();
        }
        assertThat(TestHomes.reapStale(root, System.currentTimeMillis(), NO_CAP).removed())
                .isEqualTo(1);
    }
}
