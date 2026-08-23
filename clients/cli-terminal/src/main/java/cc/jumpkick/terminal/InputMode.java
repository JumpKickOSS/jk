// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

/**
 * Named line-discipline contracts. {@link #COOKED} and {@link #INHERIT_CHILD} apply the
 * original snapshot captured at session open and must not diverge.
 */
public enum InputMode {
    /** Parent-shell restore: ICANON+ECHO+IEXTEN+ISIG as originally saved. */
    COOKED,
    /** Wizard / Prompt: cbreak, no echo, IEXTEN off, ISIG off (Ctrl-C is 0x03). */
    PROMPT,
    /** Live plan / DrainView: cbreak, no echo, IEXTEN off, ISIG on (Ctrl-C is SIGINT). */
    PLAN_KEYS,
    /** {@code inheritIO} child: fully cooked + echo from the original snapshot. */
    INHERIT_CHILD
}
