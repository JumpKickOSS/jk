// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

/**
 * The warning a test run carries when it leaves out the classes that passed earlier under the same
 * inputs, so a fast red re-run says why it was fast. Its message starts with the number of classes.
 */
public final class TestsNotRerun {

    public static final String CODE = "tests-not-rerun";

    private TestsNotRerun() {}

    /** The message for {@code n} classes left out. */
    public static String message(int n) {
        return n + " test class" + (n == 1 ? "" : "es")
                + " passed earlier under these same inputs and are not run again; running the rest (--redo runs every class)";
    }

    /** The message for {@code n} classes left out when some passed under other inputs that none of them reach. */
    public static String unmoved(int n) {
        return n + " test class" + (n == 1 ? "" : "es")
                + " passed earlier and nothing they reach or read has changed since; they are not run again, running the rest (--redo runs every class)";
    }

    /** The classes a {@link #CODE} warning's {@code message} says were left out; 0 for any other text. */
    public static int count(String message) {
        int end = 0;
        while (end < message.length() && end < 9 && Character.isDigit(message.charAt(end))) end++;
        return end == 0 ? 0 : Integer.parseInt(message.substring(0, end));
    }
}
