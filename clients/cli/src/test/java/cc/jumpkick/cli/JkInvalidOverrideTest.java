// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.JkDirs;
import org.junit.jupiter.api.Test;

/** A relative {@code JK_*_DIR} is a usage error however deep it was first read. */
class JkInvalidOverrideTest {

    @Test
    void an_override_first_read_by_a_static_initializer_is_found_under_the_wrapper() {
        var invalid = new JkDirs.InvalidOverrideException("JK_CACHE_DIR must be an absolute path: ../cache");
        assertThat(Jk.invalidOverride(new ExceptionInInitializerError(invalid))).isSameAs(invalid);
        assertThat(Jk.invalidOverride(invalid)).isSameAs(invalid);
        assertThat(Jk.invalidOverride(new IllegalStateException("other"))).isNull();
    }
}
