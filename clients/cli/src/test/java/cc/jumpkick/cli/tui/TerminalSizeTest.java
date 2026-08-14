// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Signal;

/**
 * Probing terminal size forks a {@code stty} subprocess, and {@link RenderContext#current()} runs
 * on every animation frame (80ms period) — so the probe must happen once and be cached, with
 * {@link TerminalSize#refresh()} re-probing only at plan boundaries.
 */
class TerminalSizeTest {

    private final AtomicInteger probes = new AtomicInteger();
    private Supplier<int[]> savedProbe;

    @BeforeEach
    void countProbes() {
        savedProbe = TerminalSize.probe;
        TerminalSize.probe = () -> {
            probes.incrementAndGet();
            return new int[] {24, 120};
        };
        TerminalSize.reset();
    }

    @AfterEach
    void restore() {
        TerminalSize.probe = savedProbe;
        TerminalSize.reset();
    }

    @Test
    void repeated_reads_probe_once() {
        for (int i = 0; i < 25; i++) {
            assertThat(TerminalSize.columns()).isEqualTo(120);
        }
        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void render_context_snapshots_do_not_reprobe() {
        TerminalSize.columns();
        for (int i = 0; i < 25; i++) {
            RenderContext.current();
        }
        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void refresh_reprobes_and_updates_the_cache() {
        assertThat(TerminalSize.columns()).isEqualTo(120);
        TerminalSize.probe = () -> {
            probes.incrementAndGet();
            return new int[] {24, 100};
        };
        assertThat(TerminalSize.refresh()).containsExactly(24, 100);
        assertThat(TerminalSize.columns()).isEqualTo(100);
        assertThat(probes.get()).isEqualTo(2);
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void sigwinch_invalidates_the_cache_so_the_next_read_reprobes() throws Exception {
        // JK-1966: a mid-build resize must reach post-resize rendering (failure snippets,
        // settle wedges) without waiting for the next plan start. The handler only drops the
        // cache; the next consumer pays the single re-probe.
        assertThat(TerminalSize.columns()).isEqualTo(120);
        TerminalSize.probe = () -> {
            probes.incrementAndGet();
            return new int[] {40, 66};
        };
        Signal.raise(new Signal("WINCH"));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (TerminalSize.columns() != 66 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(TerminalSize.columns()).isEqualTo(66);
    }
}
