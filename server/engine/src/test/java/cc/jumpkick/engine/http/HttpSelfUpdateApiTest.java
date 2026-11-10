// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What the Update pill reads: the running version, the release on offer and whether it installs. */
class HttpSelfUpdateApiTest {

    @Test
    void an_engine_that_never_updates_offers_nothing() {
        assertThat(new HttpSelfUpdateApi("0.51.0", () -> SelfUpdateControl.NONE)
                        .state()
                        .toString())
                .isEqualTo("{\"running\":\"0.51.0\",\"offer\":null,\"installing\":false}");
    }

    @Test
    void a_downloaded_release_is_on_offer() {
        SelfUpdateControl offering = new SelfUpdateControl() {
            @Override
            public Optional<String> offer() {
                return Optional.of("0.52.0");
            }

            @Override
            public boolean installing() {
                return true;
            }

            @Override
            public boolean installNow() {
                return true;
            }

            @Override
            public boolean snooze() {
                return true;
            }
        };
        assertThat(new HttpSelfUpdateApi("0.51.0", () -> offering).state().toString())
                .isEqualTo("{\"running\":\"0.51.0\",\"offer\":\"0.52.0\",\"installing\":true}");
    }
}
