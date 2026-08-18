// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;

/**
 * Bounded progress: bar + derived percent + optional suffix. Snapshot renderer only — plain
 * ({@code --no-ansi}) live lines are driven by stage changes in {@link JkManagerView}.
 */
public record Progress(long numerator, long denominator, RichText suffix, Look look, int segments) {

    public enum Look {
        /** Underlined block bar used on the plan header ({@code █}). */
        PLAN,
        /** Track cells used inside tables ({@code ▰▱}). */
        TRACK
    }

    public static final int DEFAULT_SEGMENTS = ProgressBar.SEGMENTS;

    /**
     * The canonical constructor is public by record rule — it can be no narrower than the class,
     * and the class is public for cross-package callers ({@code CacheCommand}). Every component
     * is normalized here, so no caller can construct a denormalized instance; the withers and the
     * two-arg convenience constructor remain the idiomatic surface.
     */
    public Progress {
        suffix = suffix == null ? RichText.empty() : suffix;
        look = look == null ? Look.PLAN : look;
        segments = segments <= 0 ? DEFAULT_SEGMENTS : segments;
    }

    public Progress(long numerator, long denominator) {
        this(numerator, denominator, RichText.empty(), Look.PLAN, DEFAULT_SEGMENTS);
    }

    public Progress suffix(RichText text) {
        return new Progress(numerator, denominator, text, look, segments);
    }

    public Progress suffix(String text) {
        return suffix(text == null || text.isEmpty() ? RichText.empty() : RichText.plain(text));
    }

    public Progress look(Look newLook) {
        return new Progress(numerator, denominator, suffix, newLook, segments);
    }

    public Progress segments(int n) {
        return new Progress(numerator, denominator, suffix, look, n);
    }

    public int percent() {
        return ProgressBar.percent(numerator, denominator);
    }

    /** Bar + {@code NN%} + optional suffix. */
    public String render(RenderContext ctx) {
        String core =
                switch (look) {
                    case PLAN -> ProgressBar.shared().render(numerator, denominator);
                    case TRACK -> trackBar(ctx);
                };
        return suffix.isEmpty() ? core : core + " " + suffix.render(ctx);
    }

    private String trackBar(RenderContext ctx) {
        Theme theme = ctx.theme();
        String bar = ProgressBar.shared()
                .renderBar(numerator, denominator, segments, theme.bright(theme.planBadgeColor()), theme.darkGray());
        String pct = percent() + "%";
        String painted = ctx.ansi() ? Theme.colorize(pct, theme.brightWhite()) : pct;
        return bar + " " + painted;
    }
}
