// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoopbackTest {

    @Test
    void this_machine_by_name_v4_and_v6_and_nothing_else() {
        assertThat(Loopback.is("localhost")).isTrue();
        assertThat(Loopback.is("LOCALHOST")).isTrue();
        assertThat(Loopback.is("127.0.0.1")).isTrue();
        assertThat(Loopback.is("::1")).isTrue();
        assertThat(Loopback.is("[::1]")).isTrue();
        assertThat(Loopback.is("10.0.0.5")).isFalse();
        assertThat(Loopback.is("localhost.example.com")).isFalse();
    }

    @Test
    void an_authority_is_judged_by_its_host() {
        assertThat(Loopback.authority("127.0.0.1:8081")).isTrue();
        assertThat(Loopback.authority("[::1]:8081")).isTrue();
        assertThat(Loopback.authority("repo.example.com:443")).isFalse();
    }
}
