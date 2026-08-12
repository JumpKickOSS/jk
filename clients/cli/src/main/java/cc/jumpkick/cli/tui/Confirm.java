// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import org.jline.terminal.Terminal;

/**
 * Façade over {@link Confirmation} so existing {@code Confirm.of(...).ask()} callers stay valid.
 */
public final class Confirm {

    private final Confirmation confirmation;

    private Confirm(Confirmation confirmation) {
        this.confirmation = confirmation;
    }

    public static Confirm of(String question, boolean defaultYes) {
        return new Confirm(Confirmation.of(question, defaultYes));
    }

    public static void setAssumeYes(boolean yes) {
        Prompt.setAssumeYes(yes);
    }

    public static void clearAssumeYes() {
        Prompt.clearAssumeYes();
    }

    public static boolean assumeYes() {
        return Prompt.assumeYes();
    }

    public static boolean isInteractiveTerminal() {
        return Interactivity.canPrompt();
    }

    public boolean ask() {
        return confirmation.ask();
    }

    public boolean ask(Terminal terminal) {
        return confirmation.ask(terminal);
    }

    static boolean rawEligible(boolean canPrompt, boolean ansi) {
        return Prompt.rawEligible(canPrompt, ansi);
    }
}
