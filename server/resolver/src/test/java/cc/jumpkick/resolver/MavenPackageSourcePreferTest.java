// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for BOM/lock soft-prefer helpers on {@link MavenPackageSource}. */
class MavenPackageSourcePreferTest {

    @Test
    void preferFirst_moves_preferred_to_front() {
        List<String> v = new ArrayList<>(List.of("2.0", "1.5", "1.0"));
        MavenPackageSource.preferFirst(v, "1.5");
        assertThat(v).containsExactly("1.5", "2.0", "1.0");
    }

    @Test
    void preferFirst_no_op_when_absent() {
        List<String> v = new ArrayList<>(List.of("2.0", "1.0"));
        MavenPackageSource.preferFirst(v, "1.5");
        assertThat(v).containsExactly("2.0", "1.0");
    }

    @Test
    void preferBom_inserts_pin_when_missing_from_metadata() {
        List<String> v = new ArrayList<>();
        MavenPackageSource.preferBom(v, "1.0");
        assertThat(v).containsExactly("1.0");
    }

    @Test
    void preferBom_moves_existing_pin_to_front() {
        List<String> v = new ArrayList<>(List.of("2.0", "1.5", "1.0"));
        MavenPackageSource.preferBom(v, "1.5");
        assertThat(v).containsExactly("1.5", "2.0", "1.0");
    }

    @Test
    void lock_prefer_after_bom_ends_at_front() {
        // Production order: preferBom then preferFirst(lock).
        List<String> v = new ArrayList<>(List.of("3.0", "2.0", "1.0"));
        MavenPackageSource.preferBom(v, "1.0");
        MavenPackageSource.preferFirst(v, "2.0");
        assertThat(v).containsExactly("2.0", "1.0", "3.0");
    }
}
