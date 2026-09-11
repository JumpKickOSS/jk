// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Detection runs on every CLI launch, so its cost is a correctness property, not a nicety.
 *
 * <p>Two things are pinned here: the environment-only tiers must do <strong>no</strong> I/O at all
 * (asserted structurally, by counting source lookups — a timing assertion alone would pass a
 * regression that added a stat on a fast disk), and the whole routine, warm, must stay inside the
 * 10 ms budget even when it does consult a source.
 */
class NerdFontDetectCostTest {

    /** Tiers that resolve from the environment must never reach for a font. */
    @Test
    void env_only_tiers_never_consult_a_font_source() {
        record Case(String label, Map<String, String> env) {}
        for (Case c : new Case[] {
            new Case("CI", env("CI", "true")),
            new Case("TERM=dumb", env("TERM", "dumb")),
            new Case("ghostty", env("TERM_PROGRAM", "ghostty")),
            new Case("kitty via TERM", env("TERM", "xterm-kitty")),
            new Case("wezterm", env("TERM_PROGRAM", "WezTerm")),
            new Case("windows terminal", env("WT_SESSION", "x")),
            new Case("ssh", env("SSH_TTY", "/dev/ttys001")),
            new Case("apple terminal", env("TERM_PROGRAM", "Apple_Terminal")),
            new Case("unknown", env()),
            new Case("no resolver", env("TERM_PROGRAM", "Hyper")),
        }) {
            Counting fonts = new Counting();
            NerdFontDetect.detect(c.env()::get, fonts);
            assertThat(fonts.calls())
                    .as("%s must resolve from the environment alone", c.label())
                    .isZero();
        }
    }

    /** The terminals that do need a font ask for exactly one, not all four. */
    @Test
    void a_config_tier_consults_exactly_one_source() {
        Counting fonts = new Counting();
        NerdFontDetect.detect(env("TERM_PROGRAM", "iTerm.app")::get, fonts);
        assertThat(fonts.calls()).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Tag("bench")
    void detection_stays_inside_the_per_launch_budget() {
        // Real sources, real environment — the live per-launch path, not a stub. The first call in
        // a test JVM pays class loading and a cold JIT, which the native CLI never does, so it is
        // reported and the budget is judged on the best of the warm runs. The call-count
        // assertions above are the deterministic unit coverage of the same no-I/O guarantee.
        long coldStart = System.nanoTime();
        NerdFontDetect.detect();
        long coldMs = (System.nanoTime() - coldStart) / 1_000_000;
        long bestNanos = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            NerdFontDetect.detect();
            bestNanos = Math.min(bestNanos, System.nanoTime() - start);
        }
        long warmMs = bestNanos / 1_000_000;
        System.out.println("nerd-font detect: cold=" + coldMs + " ms warm-best=" + warmMs + " ms");
        assertThat(warmMs)
                .as("detection took %d ms warm (%d ms cold); the per-launch budget is 10 ms", warmMs, coldMs)
                .isLessThan(10);
    }

    @Test
    void detection_never_throws_whatever_the_sources_do() {
        TerminalFonts hostile = new TerminalFonts() {
            @Override
            public Optional<String> itermFont() {
                throw new IllegalStateException("boom");
            }

            @Override
            public Optional<String> alacrittyFont() {
                throw new OutOfMemoryError("boom");
            }

            @Override
            public @Nullable Optional<String> vscodeFont() {
                return null; // a contract violation, not just a failure
            }

            @Override
            public Optional<String> zedFont() {
                return Optional.empty();
            }
        };
        // A broken source must degrade to the terminal's floor, never propagate. Nerd-font detection
        // is not allowed to fail a build. Alacritty's floor is the wedge it draws itself, so a dead
        // source costs it the pill upgrade and nothing more.
        record Case(Map<String, String> env, NerdFontCaps floor) {}
        for (Case c : new Case[] {
            new Case(env("TERM_PROGRAM", "iTerm.app"), NerdFontCaps.NONE),
            new Case(env("ALACRITTY_LOG", "x"), NerdFontCaps.WEDGE_ONLY),
            new Case(env("TERM_PROGRAM", "vscode"), NerdFontCaps.NONE),
            new Case(env("TERM_PROGRAM", "zed"), NerdFontCaps.NONE),
        }) {
            var r = NerdFontDetect.detect(c.env()::get, hostile);
            assertThat(r).as("env %s", c.env()).isNotNull();
            assertThat(r.caps()).as("env %s", c.env()).isEqualTo(c.floor());
        }
    }

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Counts how many font sources were asked, so "no I/O" can be asserted structurally. */
    private static final class Counting implements TerminalFonts {
        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return calls.get();
        }

        private Optional<String> tick() {
            calls.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public Optional<String> itermFont() {
            return tick();
        }

        @Override
        public Optional<String> alacrittyFont() {
            return tick();
        }

        @Override
        public Optional<String> vscodeFont() {
            return tick();
        }

        @Override
        public Optional<String> zedFont() {
            return tick();
        }
    }
}
