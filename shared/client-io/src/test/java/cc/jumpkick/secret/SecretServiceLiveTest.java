// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Secret Service backend against this machine's real keyring. Opt-in, because it writes to the
 * user's keyring and a locked one prompts: set {@code JK_TEST_SECRET_SERVICE=1} in a desktop
 * session with {@code secret-tool} installed.
 */
@Tag("integration")
class SecretServiceLiveTest {

    @Test
    void a_secret_round_trips_through_the_real_secret_service_and_is_removed() {
        assumeTrue("1".equals(System.getenv("JK_TEST_SECRET_SERVICE")), "opt-in: JK_TEST_SECRET_SERVICE=1");
        SecretServiceBackend backend = new SecretServiceBackend(SecretCommand.REAL);
        assumeTrue(backend.probe().available(), "no Secret Service here");
        String item = "test/" + UUID.randomUUID();
        try {
            backend.write(item, "jk test item", "p@ss w0rd \"x\"");
            assertThat(backend.read(item)).contains("p@ss w0rd \"x\"");
        } finally {
            backend.delete(item);
        }
        assertThat(backend.read(item)).isEmpty();
    }
}
