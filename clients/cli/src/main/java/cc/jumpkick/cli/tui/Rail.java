// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.terminal.Styled;
import cc.jumpkick.terminal.StyledBuilder;

/**
 * Left-rail line builders. The rail is the box-drawing column on the left edge of the wizard frame:
 * a {@code ┌} opener, repeated {@code │} interior lines, and a final {@code └} closer.
 */
public final class Rail {

    private Rail() {}

    public enum StepState {
        COMPLETED,
        ACTIVE,
        INACTIVE
    }

    /** The style of every rail glyph of a step in {@code state}: lit while active, dim otherwise. */
    public static Style style(StepState state) {
        return state == StepState.ACTIVE
                ? Theme.active().activeStep()
                : Theme.active().darkGray();
    }

    private static final String OPEN_CHAR = "╭";
    private static final String MID_CHAR = "│";
    private static final String CLOSE_CHAR = "╰";

    /** Filled box for the active step; empty box for completed/inactive steps. */
    private static final String BULLET_CHAR = "■";

    private static final String BULLET_CHAR_EMPTY = "□";

    /** Horizontal bars drawn after the corner glyph on the top/bottom of the frame. */
    private static final String CORNER_DASHES = "──";

    public static final String CHECKBOX_OFF = "□";
    public static final String CHECKBOX_ON = "■";
    public static final String RADIO_ON = "●";
    public static final String RADIO_OFF = "○";

    /** {@code ╭── <title>} — first line of the frame. */
    public static Styled opener(String title, StepState state) {
        var cornerStyle = style(state);
        return new StyledBuilder()
                .append(OPEN_CHAR, cornerStyle)
                .append(CORNER_DASHES, cornerStyle)
                .append(" ")
                .append(title, Theme.active().focused())
                .build();
    }

    /** Same as {@link #opener(String, StepState)} but the title carries its own styling. */
    public static Styled opener(Styled styledTitle, StepState state) {
        var cornerStyle = style(state);
        return new StyledBuilder()
                .append(OPEN_CHAR, cornerStyle)
                .append(CORNER_DASHES, cornerStyle)
                .append(" ")
                .append(styledTitle)
                .build();
    }

    /** {@code │ <text>} — interior line, with text styled by caller. */
    public static Styled mid(Styled text, StepState state) {
        return new StyledBuilder()
                .append(MID_CHAR, style(state))
                .append("  ")
                .append(text)
                .build();
    }

    public static Styled mid(String text, StepState state, Style textStyle) {
        return new StyledBuilder()
                .append(MID_CHAR, style(state))
                .append("  ")
                .append(text, textStyle)
                .build();
    }

    /** Bare {@code │} (no following text). */
    public static Styled midBlank(StepState state) {
        return new StyledBuilder().append(MID_CHAR, style(state)).build();
    }

    /** {@code ╰── [text]} — final line; trailing text is omitted when empty. */
    public static Styled closer(String text, Style textStyle) {
        return closer(text, textStyle, StepState.INACTIVE);
    }

    /** {@code ╰── [text]} with explicit rail state (controls the corner-glyph color). */
    public static Styled closer(String text, Style textStyle, StepState state) {
        var cornerStyle = style(state);
        var sb = new StyledBuilder().append(CLOSE_CHAR, cornerStyle).append(CORNER_DASHES, cornerStyle);
        if (text != null && !text.isEmpty()) {
            sb.append(" ").append(text, textStyle);
        }
        return sb.build();
    }

    /**
     * Step header bullet: filled {@code ■} only for the active step, empty {@code □} for
     * completed/inactive steps. Coloring is unchanged — accent when active, dark-gray otherwise.
     */
    public static Styled stepBullet(StepState state, String prompt) {
        var promptStyle =
                switch (state) {
                    case ACTIVE -> Theme.active().focused();
                    case COMPLETED -> Theme.active().completedPrompt();
                    case INACTIVE -> Theme.active().darkGray();
                };
        var bullet = state == StepState.ACTIVE ? BULLET_CHAR : BULLET_CHAR_EMPTY;
        return new StyledBuilder()
                .append(bullet, style(state))
                .append("  ")
                .append(prompt, promptStyle)
                .build();
    }
}
