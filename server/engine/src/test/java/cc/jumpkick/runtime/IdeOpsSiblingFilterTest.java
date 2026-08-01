// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * IDE config must file workspace-sibling lock rows as module deps, not external libraries. Lock
 * rows are keyed by full package id ({@code g:a:type:classifier}) while sibling coords are plain
 * {@code group:artifact} — a raw {@code contains(name())} never matched (JK-1343, same class of
 * mismatch as the lockModules processor-dependency bug).
 */
class IdeOpsSiblingFilterTest {

    private static Lockfile.Artifact row(String name) {
        return new Lockfile.Artifact(name, "1.0.0", "central+https://repo", "sha256:00", null, List.of());
    }

    @Test
    void sibling_matches_full_package_key_rows() {
        Set<String> siblings = Set.of("com.example:core");
        assertThat(IdeOps.isSibling(siblings, row("com.example:core:jar:"))).isTrue();
        assertThat(IdeOps.isSibling(siblings, row("com.example:core"))).isTrue(); // legacy GA row
        assertThat(IdeOps.isSibling(siblings, row("com.example:other:jar:"))).isFalse();
        assertThat(IdeOps.isSibling(siblings, row("org.acme:core:jar:"))).isFalse();
    }
}
