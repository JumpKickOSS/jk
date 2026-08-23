// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Detection runs on every CLI launch, so its cost is a correctness property, not a nicety.
 *
 * <p>Two things are pinned here: the environment-only tiers must do <strong>no</strong> I/O at all
 * (asserted structurally, by counting source lookups — a timing assertion alone would pass a
 * regression that added a stat on a fast disk), and the whole routine must stay inside the 10 ms
 * budget even when it does consult a source.
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
    void cold_detection_stays_inside_the_budget() {
        // A wall-clock budget: flaky under CI load / cold JIT (10 ms vs an occasional 11 ms), so it
        // runs in the bench tier, not the unit gate (JK-2314). The call-count assertions above are
        // the deterministic unit coverage of the same no-I/O guarantee.
        // Real sources, real environment — this is the live per-launch path, not a stub.
        long start = System.nanoTime();
        NerdFontDetect.detect();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs)
                .as("cold detection took %d ms; the per-launch budget is 10 ms", elapsedMs)
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
            public Optional<String> vscodeFont() {
                return null; // a contract violation, not just a failure
            }

            @Override
            public Optional<String> zedFont() {
                return Optional.empty();
            }
        };
        // A broken source must degrade to "no glyphs", never propagate. Nerd-font detection is not
        // allowed to fail a build.
        for (Map<String, String> e : List.of(
                env("TERM_PROGRAM", "iTerm.app"),
                env("ALACRITTY_LOG", "x"),
                env("TERM_PROGRAM", "vscode"),
                env("TERM_PROGRAM", "zed"))) {
            var r = NerdFontDetect.detect(e::get, hostile);
            assertThat(r).as("env %s", e).isNotNull();
            assertThat(r.caps()).as("env %s", e).isEqualTo(NerdFontCaps.NONE);
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
