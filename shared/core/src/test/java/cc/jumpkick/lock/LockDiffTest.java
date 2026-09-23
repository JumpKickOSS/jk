// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockDiffTest {

    @Test
    void an_unchanged_rewrite_changes_nothing() {
        Lockfile lock = lock(row("g:a", "1.0"), row("g:b", "2.0"));
        assertThat(LockDiff.between(lock, lock(row("g:a", "1.0"), row("g:b", "2.0"))))
                .isEmpty();
    }

    @Test
    void reports_moved_added_and_removed_rows_by_coordinate() {
        Lockfile before = lock(row("g:a", "1.0"), row("g:b", "2.0"), row("g:gone", "1.0"));
        Lockfile after = lock(row("g:a", "1.1"), row("g:b", "2.0"), row("g:new", "3.0"));
        assertThat(LockDiff.between(before, after))
                .containsExactly(
                        new LockDiff.Change("g:a", "1.0", "1.1", List.of()),
                        new LockDiff.Change("g:gone", "1.0", null, List.of()),
                        new LockDiff.Change("g:new", null, "3.0", List.of()));
    }

    @Test
    void a_member_override_row_is_its_own_package() {
        Lockfile before = lock(row("g:a", "1.0"), row("g:a", "1.0").withMembers(List.of("web")));
        Lockfile after = lock(row("g:a", "1.0"), row("g:a", "1.2").withMembers(List.of("web")));
        assertThat(LockDiff.between(before, after))
                .containsExactly(new LockDiff.Change("g:a", "1.0", "1.2", List.of("web")));
    }

    @Test
    void every_row_is_added_without_a_prior_lock() {
        assertThat(LockDiff.between(null, lock(row("g:a", "1.0"), row("g:b", "2.0"))))
                .extracting(LockDiff.Change::from)
                .containsExactly(null, null);
    }

    private static Lockfile.Artifact row(String name, String version) {
        return new Lockfile.Artifact(name, version, "central", null, null, List.of());
    }

    private static Lockfile lock(Lockfile.Artifact... rows) {
        return new Lockfile(1, "jk test", Lockfile.RESOLUTION_ALGORITHM, Arrays.asList(rows));
    }
}
