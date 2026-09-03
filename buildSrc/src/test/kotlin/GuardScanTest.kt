// SPDX-License-Identifier: Apache-2.0

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GuardScanTest {

    @Test
    fun squash_does_not_eat_spaces_inside_string_literals() {
        val src = """String label = " native-image ";"""
        assertThat(GuardScan.squashBetweenLiterals(src)).contains("\" native-image \"")
        assertThat(GuardScan.guardText(src)).doesNotContain("\"native-image\"")
    }

    @Test
    fun ratchet_grew_unlisted_loose_and_other_module_entries() {
        val hits = mapOf("shared/host/Foo.java" to 2, "shared/host/Bar.java" to 1)
        val allowed = mapOf("shared/host/Foo.java" to 1, "shared/host/Bar.java" to 1, "server/engine/Baz.java" to 3)
        val (grew, unlisted, loose) = GuardScan.ratchetVerdict(hits, allowed, "shared/host/")
        assertThat(grew).containsExactly("  shared/host/Foo.java: 2 sites, allowed 1 (+1)")
        assertThat(unlisted).isEmpty()
        assertThat(loose).isEmpty()
        val missing = GuardScan.ratchetVerdict(emptyMap(), mapOf("shared/host/Foo.java" to 1), "shared/host/")
        assertThat(missing.third).containsExactly("  (clean) shared/host/Foo.java   (was 1)")
        val otherModule = GuardScan.ratchetVerdict(emptyMap(), mapOf("server/engine/Baz.java" to 3), "shared/host/")
        assertThat(otherModule.third).isEmpty()
    }
}
