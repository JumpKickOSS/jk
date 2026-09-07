// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoalescingLockPackagesTest {

    @Test
    void coalesces_to_latest_with_running_total() {
        List<String> out = new ArrayList<>();
        try (CoalescingLockPackages c = new CoalescingLockPackages(
                (dir, name, ver, total) -> out.add(name + "@" + ver + "#" + total), 60_000L)) {
            c.onPackage("/p", "a", "1");
            c.onPackage("/p", "b", "2");
            c.onPackage("/p", "c", "3");
            assertThat(out).isEmpty();
            c.flush();
            assertThat(out).containsExactly("c@3#3");
            c.onPackage("/p", "d", "4");
            c.flush();
            assertThat(out).containsExactly("c@3#3", "d@4#4");
        }
    }

    @Test
    void close_flushes_pending_events() {
        // close marked closed before flushing, and flush no-ops when closed
        // the documented close-flushes contract silently dropped the final event.
        List<String> out = new ArrayList<>();
        CoalescingLockPackages c =
                new CoalescingLockPackages((dir, name, ver, total) -> out.add(name + "@" + ver + "#" + total), 60_000L);
        c.onPackage("/p", "a", "1");
        c.close();
        assertThat(out).containsExactly("a@1#1");
        c.close(); // idempotent
        assertThat(out).hasSize(1);
    }
}
