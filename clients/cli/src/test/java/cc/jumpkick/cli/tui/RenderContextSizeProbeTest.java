// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.terminal.Size;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RenderContext#current()} runs on every animation frame (80ms period) and a terminal-size
 * probe is a native ioctl. The caching contract itself belongs to {@code Size}, and is asserted by
 * {@code cc.jumpkick.terminal.SizeTest} in {@code :cli-terminal}; what belongs HERE is the one fact
 * about the {@code :cli} reader — that a frame snapshot never re-probes.
 */
class RenderContextSizeProbeTest {

    private final AtomicInteger probes = new AtomicInteger();
    private Supplier<Size.Window> savedProbe;

    @BeforeEach
    void countProbes() {
        savedProbe = Size.probe;
        Size.probe = () -> {
            probes.incrementAndGet();
            return new Size.Window(24, 120);
        };
        Size.reset();
    }

    @AfterEach
    void restore() {
        Size.probe = savedProbe;
        Size.reset();
    }

    @Test
    void render_context_snapshots_do_not_reprobe() {
        Size.columns();
        for (int i = 0; i < 25; i++) {
            RenderContext.current();
        }
        assertThat(probes.get()).isEqualTo(1);
    }
}
