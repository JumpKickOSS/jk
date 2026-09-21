// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CachePlansTagTest {

    /** A pointer, its generation list and the list's lock all carry the pointer's tag. */
    @Test
    void a_generation_list_and_its_lock_carry_the_pointers_tag() {
        assertThat(CachePlans.tagOf("compile-java@abc123")).isEqualTo("abc123");
        assertThat(CachePlans.tagOf("native-image@abc123.gens")).isEqualTo("abc123");
        assertThat(CachePlans.tagOf("native-image@abc123.gens.lock")).isEqualTo("abc123");
        assertThat(CachePlans.tagOf("compile-java")).isEmpty();
    }
}
