// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreferIpv4Test {

    @Test
    void jvm_flag_is_the_standard_dash_d_form() {
        assertThat(PreferIpv4.JVM_FLAG).isEqualTo("-Djava.net.preferIPv4Stack=true");
        assertThat(PreferIpv4.PROPERTY).isEqualTo("java.net.preferIPv4Stack");
    }

    @Test
    void install_sets_the_property() {
        String prev = System.getProperty(PreferIpv4.PROPERTY);
        try {
            System.clearProperty(PreferIpv4.PROPERTY);
            PreferIpv4.install();
            assertThat(System.getProperty(PreferIpv4.PROPERTY)).isEqualTo("true");
            PreferIpv4.install(); // idempotent
            assertThat(System.getProperty(PreferIpv4.PROPERTY)).isEqualTo("true");
        } finally {
            if (prev == null) System.clearProperty(PreferIpv4.PROPERTY);
            else System.setProperty(PreferIpv4.PROPERTY, prev);
        }
    }
}
