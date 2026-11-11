// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Scope;
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

    /** A main row and a test row of one coordinate, listed in another order, are no change. */
    @Test
    void reordered_main_and_test_rows_of_one_coordinate_are_unchanged() {
        Lockfile.Artifact main = row("g:a", "2.22.4").withScopes(List.of(Scope.MAIN));
        Lockfile.Artifact test = row("g:a", "2.21.0").withScopes(List.of(Scope.TEST));
        assertThat(LockDiff.between(lock(test, main), lock(main, test))).isEmpty();
    }

    @Test
    void a_moved_test_row_beside_a_main_row_reports_its_own_move() {
        Lockfile.Artifact main = row("g:a", "2.22.4").withScopes(List.of(Scope.MAIN));
        Lockfile.Artifact before = row("g:a", "2.21.0").withScopes(List.of(Scope.TEST));
        Lockfile.Artifact after = row("g:a", "2.21.1").withScopes(List.of(Scope.TEST));
        assertThat(LockDiff.between(lock(before, main), lock(main, after)))
                .containsExactly(new LockDiff.Change("g:a", "2.21.0", "2.21.1", List.of()));
    }

    private static Lockfile.Artifact row(String name, String version) {
        return new Lockfile.Artifact(name, version, "central", null, null, List.of());
    }

    private static Lockfile lock(Lockfile.Artifact... rows) {
        return new Lockfile(1, "jk test", Lockfile.RESOLUTION_ALGORITHM, Arrays.asList(rows));
    }
}
