// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CiIdentityTest {

    @Test
    void github_actions_needs_the_request_url_and_its_token() {
        assertThat(CiIdentity.available(Map.of(
                        "ACTIONS_ID_TOKEN_REQUEST_URL", "https://token.example/",
                        "ACTIONS_ID_TOKEN_REQUEST_TOKEN", "t")::get))
                .isTrue();
        assertThat(CiIdentity.available(Map.of("ACTIONS_ID_TOKEN_REQUEST_URL", "https://token.example/")::get))
                .isFalse();
    }

    @Test
    void a_gitlab_identity_token_is_enough() {
        assertThat(CiIdentity.available(Map.of("SIGSTORE_ID_TOKEN", "jwt")::get))
                .isTrue();
    }

    @Test
    void a_blank_or_missing_variable_is_no_identity() {
        assertThat(CiIdentity.available(Map.of("SIGSTORE_ID_TOKEN", " ")::get)).isFalse();
        assertThat(CiIdentity.available(Map.<String, String>of()::get)).isFalse();
    }
}
