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
}
