// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.PasswordAuthentication;
import org.junit.jupiter.api.Test;

class ProxyUserInfoTest {

    @Test
    void an_escaped_colon_stays_in_the_user_name_and_an_escaped_at_in_the_password() {
        PasswordAuthentication credential = ProxyUserInfo.credential("us%3Aer:p%40ss");

        assertThat(credential.getUserName()).isEqualTo("us:er");
        assertThat(new String(credential.getPassword())).isEqualTo("p@ss");
    }

    @Test
    void the_first_raw_colon_separates_and_a_later_one_belongs_to_the_password() {
        PasswordAuthentication credential = ProxyUserInfo.credential("alice:a:b+c");

        assertThat(credential.getUserName()).isEqualTo("alice");
        assertThat(new String(credential.getPassword())).isEqualTo("a:b+c");
    }

    @Test
    void a_user_name_alone_has_an_empty_password() {
        PasswordAuthentication credential = ProxyUserInfo.credential("alice");

        assertThat(credential.getUserName()).isEqualTo("alice");
        assertThat(credential.getPassword()).isEmpty();
    }
}
