// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InFlightBuildsTest {

    @Test
    void second_same_fingerprint_is_rejected() {
        InFlightBuilds reg = new InFlightBuilds();
        var h1 = new InFlightBuilds.Hold(1, 27, "fp-a", "build", "/p", "g:a", 1L, "j1", "cli");
        assertThat(reg.tryAcquire(h1)).isEmpty();
        var h2 = new InFlightBuilds.Hold(2, 28, "fp-a", "build", "/p", "g:a", 2L, "j2", "cli");
        assertThat(reg.tryAcquire(h2)).contains(h1);
        assertThat(reg.peek("fp-a")).contains(h1);
    }

    @Test
    void different_fingerprints_run_concurrently() {
        InFlightBuilds reg = new InFlightBuilds();
        var h1 = new InFlightBuilds.Hold(1, 1, "fp-a", "build", "/a", null, 1L, null, "cli");
        var h2 = new InFlightBuilds.Hold(2, 2, "fp-b", "build", "/b", null, 2L, null, "cli");
        assertThat(reg.tryAcquire(h1)).isEmpty();
        assertThat(reg.tryAcquire(h2)).isEmpty();
        assertThat(reg.list()).hasSize(2);
    }

    @Test
    void release_allows_reacquire() {
        InFlightBuilds reg = new InFlightBuilds();
        var h1 = new InFlightBuilds.Hold(1, 5, "fp", "build", "/p", null, 1L, null, "cli");
        reg.tryAcquire(h1);
        reg.release(1);
        var h2 = new InFlightBuilds.Hold(2, 6, "fp", "build", "/p", null, 2L, null, "cli");
        assertThat(reg.tryAcquire(h2)).isEmpty();
        assertThat(reg.peek("fp").orElseThrow().requestId()).isEqualTo(2);
    }
}
