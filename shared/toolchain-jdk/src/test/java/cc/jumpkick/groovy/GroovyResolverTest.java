// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroovyResolverTest {

    @Test
    void default_version_is_a_groovy_5_release() {
        assertThat(GroovyResolver.DEFAULT_VERSION).isEqualTo("5.0.4");
        assertThat(GroovyResolver.DEFAULT_VERSION).startsWith("5.");
    }
}
