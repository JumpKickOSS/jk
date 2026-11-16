// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReleasePinTest {

    @Test
    void a_release_installs_itself_and_asks_nothing() throws Exception {
        List<String> asked = new ArrayList<>();
        ReleasePin.Pin pin = ReleasePin.of("0.51.1", v -> asked.add(v), () -> "9.9.9");
        assertThat(Objects.requireNonNull(pin).version()).isEqualTo("0.51.1");
        assertThat(asked).isEmpty();
    }

    @Test
    void a_snapshot_installs_the_newest_release_at_or_below_its_base() throws Exception {
        List<String> asked = new ArrayList<>();
        Set<String> published = Set.of("0.51.0", "0.51.1", "0.52.0");
        ReleasePin.Pin pin = ReleasePin.of(
                "0.51.2-SNAPSHOT",
                v -> {
                    asked.add(v);
                    return published.contains(v);
                },
                () -> "0.52.0");
        assertThat(Objects.requireNonNull(pin).version()).isEqualTo("0.51.1");
        assertThat(pin.why()).contains("0.51.2-SNAPSHOT").contains("never published");
        assertThat(asked).containsExactly("0.51.2", "0.51.1");
    }

    @Test
    void a_snapshot_of_a_new_minor_takes_the_latest_when_it_is_older() throws Exception {
        ReleasePin.Pin pin = ReleasePin.of("0.52.0-SNAPSHOT", v -> false, () -> "0.51.1");
        assertThat(Objects.requireNonNull(pin).version()).isEqualTo("0.51.1");
    }

    @Test
    void no_release_fits_a_snapshot_older_than_every_published_one() throws Exception {
        assertThat(ReleasePin.of("0.1.0-SNAPSHOT", v -> false, () -> "0.51.1")).isNull();
        assertThat(ReleasePin.of("not a version", v -> true, () -> "0.51.1")).isNull();
    }
}
