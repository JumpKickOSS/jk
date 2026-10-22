// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class PublishVerbDestinationTest {

    @Test
    void a_dry_run_with_no_repository_says_so_instead_of_null() {
        assertThat(PublishVerb.destination(false, "user-managed", null)).isEqualTo("no repository");
    }

    @Test
    void a_repository_and_the_portal_are_named() {
        assertThat(PublishVerb.destination(false, "user-managed", URI.create("https://repo.example/m2")))
                .isEqualTo("https://repo.example/m2");
        assertThat(PublishVerb.destination(true, "automatic", null)).isEqualTo("Central Portal (automatic)");
    }
}
