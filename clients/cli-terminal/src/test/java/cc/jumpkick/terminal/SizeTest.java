// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Probing terminal size is a native ioctl / console call, and the CLI's animation loop reads the
 * size on every frame (80ms period) — so the probe must happen once and be cached, with
 * {@link Size#refresh()} re-probing only at plan boundaries. The matching assertion about the
 * CLI-side reader is {@code cc.jumpkick.cli.tui.RenderContextSizeProbeTest} over in {@code :cli},
 * which is where {@code RenderContext} lives.
 */
class SizeTest {

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
    void repeated_reads_probe_once() {
        for (int i = 0; i < 25; i++) {
            assertThat(Size.columns()).isEqualTo(120);
        }
        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void refresh_reprobes_and_updates_the_cache() {
        assertThat(Size.columns()).isEqualTo(120);
        Size.probe = () -> {
            probes.incrementAndGet();
            return new Size.Window(24, 100);
        };
        assertThat(Size.refresh()).isEqualTo(new Size.Window(24, 100));
        assertThat(Size.columns()).isEqualTo(100);
        assertThat(probes.get()).isEqualTo(2);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sigwinch_invalidates_the_cache_so_the_next_read_reprobes() throws Exception {
        // a mid-build resize must reach post-resize rendering (failure snippets,
        // settle wedges) without waiting for the next plan start. The handler only drops the
        // cache; the next consumer pays the single re-probe.
        assertThat(Size.columns()).isEqualTo(120);
        Size.probe = () -> {
            probes.incrementAndGet();
            return new Size.Window(40, 66);
        };
        raiseWinch();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Size.columns() != 66 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(Size.columns()).isEqualTo(66);
    }

    /** Reflective {@code sun.misc.Signal.raise(WINCH)} — no direct sun.* compile dependency. */
    private static void raiseWinch() throws Exception {
        Class<?> signalClass = Class.forName("sun.misc.Signal");
        Constructor<?> ctor = signalClass.getConstructor(String.class);
        Object signal = ctor.newInstance("WINCH");
        Method raise = signalClass.getMethod("raise", signalClass);
        raise.invoke(null, signal);
    }

    @Test
    void winch_during_an_in_flight_probe_is_never_lost() {
        // the resize lands between the ioctl and the cache store — the (possibly
        // pre-resize) result must not be cached over the invalidation.
        Size.probe = () -> {
            probes.incrementAndGet();
            Size.onResize(); // deterministic mid-probe WINCH
            return new Size.Window(24, 80); // pre-resize geometry
        };
        assertThat(Size.current()).isEqualTo(new Size.Window(24, 80)); // best effort for this frame
        // The cache stayed empty, so the next read re-probes and sees post-resize geometry.
        Size.probe = () -> {
            probes.incrementAndGet();
            return new Size.Window(50, 100);
        };
        assertThat(Size.current()).isEqualTo(new Size.Window(50, 100));
        assertThat(Size.current()).isEqualTo(new Size.Window(50, 100)); // now cached
        assertThat(probes.get()).isEqualTo(2);
    }

    @Test
    void windows_init_publishes_the_attempted_flag_even_when_linking_fails() throws Exception {
        // ensureWindows mirrors ensurePosix: the attempted flag is written last (finally), so a
        // racing reader can never observe attempted=true with unpublished handles — that race
        // caches the 80x24 env fallback process-wide, and Windows has no WINCH to recover. The
        // finally also memoizes a linker failure (kernel32 absent off-Windows) so it is paid
        // once, never re-thrown per probe.
        Method ensure = Size.class.getDeclaredMethod("ensureWindows");
        ensure.setAccessible(true);
        try {
            ensure.invoke(null);
        } catch (InvocationTargetException expectedOffWindows) {
            // no kernel32 on this OS — the flag must still have been published
        }
        Field attempted = Size.class.getDeclaredField("winInitAttempted");
        attempted.setAccessible(true);
        assertThat(attempted.getBoolean(null)).isTrue();
        ensure.invoke(null); // memoized: a second call never re-links or re-throws
    }

    @Test
    void env_size_uses_defaults_when_env_absent_or_invalid() {
        // Cannot clear process env in-process; defaults must at least be positive and stable.
        Size.Window size = Size.envSize();
        assertThat(size.rows()).isPositive();
        assertThat(size.cols()).isPositive();
    }

    @Test
    void production_probe_returns_positive_rows_and_cols() {
        // Exercise the real FFM path (or env/default fallback when no tty).
        Size.probe = savedProbe;
        Size.reset();
        Size.Window size = Size.refresh();
        assertThat(size.rows()).isPositive();
        assertThat(size.cols()).isPositive();
        assertThat(Size.columns()).isEqualTo(size.cols());
        assertThat(Size.current()).isEqualTo(size);
    }
}
