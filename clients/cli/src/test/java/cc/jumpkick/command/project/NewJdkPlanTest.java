// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NewJdkPlanTest {

    // --- parseExplicit: the vendor-in-the-pin policy --------------------

    @Test
    void bare_major_stays_bare() {
        var spec = NewJdkPlan.parseExplicit("25");
        assertThat(spec.major()).isEqualTo(25);
        assertThat(spec.pin()).isEqualTo("25");
    }

    @Test
    void explicit_vendor_is_preserved_lowercased() {
        var spec = NewJdkPlan.parseExplicit("Corretto-25");
        assertThat(spec.major()).isEqualTo(25);
        assertThat(spec.pin()).isEqualTo("corretto-25");
    }

    @Test
    void point_release_is_rejected() {
        assertThatThrownBy(() -> NewJdkPlan.parseExplicit("25.0.3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("point release");
        assertThatThrownBy(() -> NewJdkPlan.parseExplicit("temurin-25.0.3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("point release");
    }

    @Test
    void spec_without_a_major_is_rejected() {
        assertThatThrownBy(() -> NewJdkPlan.parseExplicit("temurin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("major version");
    }
}
