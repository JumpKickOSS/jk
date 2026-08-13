// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;

/**
 * Bounded progress: bar + derived percent + optional suffix. Snapshot renderer; live cadence
 * (plain 20% steps) is {@link #plainDecade} / {@link #shouldEmitPlain}.
 */
public final class Progress {

    public enum Look {
        /** Underlined block bar used on the plan header ({@code █}). */
        PLAN,
        /** Track cells used inside tables ({@code ▰▱}). */
        TRACK
    }

    public static final int DEFAULT_SEGMENTS = ProgressBar.SEGMENTS;

    /** Plain live updates fire at these percents (0 is the start line; 100 is settle-only). */
    public static final int PLAIN_STEP_PERCENT = 20;

    private final long numerator;
    private final long denominator;
    private final RichText suffix;
    private final Look look;
    private final int segments;

    public Progress(long numerator, long denominator) {
        this(numerator, denominator, RichText.empty(), Look.PLAN, DEFAULT_SEGMENTS);
    }

    private Progress(long numerator, long denominator, RichText suffix, Look look, int segments) {
        this.numerator = numerator;
        this.denominator = denominator;
        this.suffix = suffix == null ? RichText.empty() : suffix;
        this.look = look == null ? Look.PLAN : look;
        this.segments = segments <= 0 ? DEFAULT_SEGMENTS : segments;
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

    public long numerator() {
        return numerator;
    }

    public long denominator() {
        return denominator;
    }

    public RichText suffix() {
        return suffix;
    }

    public Look look() {
        return look;
    }

    public int segments() {
        return segments;
    }

    public int percent() {
        return ProgressBar.percent(numerator, denominator);
    }

    /**
     * Last 20% step reached at or below {@link #percent()}, clamped to 80. 100% is settle-only and
     * is not a live decade.
     */
    public int plainDecade() {
        int pct = percent();
        if (pct >= 100) return 80;
        return (pct / PLAIN_STEP_PERCENT) * PLAIN_STEP_PERCENT;
    }

    /**
     * Whether a plain-mode live printer should emit a new line. {@code lastEmittedDecade} is
     * {@code -1} before the mandatory 0% start.
     */
    public static boolean shouldEmitPlain(int lastEmittedDecade, int newPercent) {
        if (newPercent >= 100) return false;
        int decade = (Math.max(0, newPercent) / PLAIN_STEP_PERCENT) * PLAIN_STEP_PERCENT;
        return decade > lastEmittedDecade;
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
