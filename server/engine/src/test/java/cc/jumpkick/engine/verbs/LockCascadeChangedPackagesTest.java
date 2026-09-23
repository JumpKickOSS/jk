// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockCascadeChangedPackagesTest {

    @Test
    void an_unchanged_rewrite_changes_nothing() {
        Lockfile lock = lock(row("g:a", "1.0"), row("g:b", "2.0"));
        assertThat(LockCascade.changedPackages(lock, lock(row("g:a", "1.0"), row("g:b", "2.0"))))
                .isZero();
    }

    @Test
    void counts_moved_added_and_removed_rows() {
        Lockfile before = lock(row("g:a", "1.0"), row("g:b", "2.0"), row("g:gone", "1.0"));
        Lockfile after = lock(row("g:a", "1.1"), row("g:b", "2.0"), row("g:new", "3.0"));
        assertThat(LockCascade.changedPackages(before, after)).isEqualTo(3);
    }

    @Test
    void every_row_is_new_without_a_prior_lock() {
        assertThat(LockCascade.changedPackages(null, lock(row("g:a", "1.0"), row("g:b", "2.0"))))
                .isEqualTo(2);
    }

    private static Lockfile.Artifact row(String name, String version) {
        return new Lockfile.Artifact(name, version, "central", null, null, List.of());
    }

    private static Lockfile lock(Lockfile.Artifact... rows) {
        return new Lockfile(1, "jk test", Lockfile.RESOLUTION_ALGORITHM, Arrays.asList(rows));
    }
}
