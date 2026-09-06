// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

/**
 * A view of a string that gives up after a deadline: a catastrophically backtracking regex is a
 * {@code scanner-failed} for its rule, never a stalled engine. Checks the clock every 4,096 chars
 * read, so the check itself costs nothing measurable.
 */
final class DeadlineCharSequence implements CharSequence {

    static final class Expired extends RuntimeException {
        Expired(long millis) {
            super("regex exceeded " + millis + " ms");
        }
    }

    private final CharSequence text;
    private final long deadlineNanos;
    private final long budgetMillis;
    private int reads;

    DeadlineCharSequence(CharSequence text, long budgetMillis) {
        this.text = text;
        this.budgetMillis = budgetMillis;
        this.deadlineNanos = System.nanoTime() + budgetMillis * 1_000_000L;
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public char charAt(int index) {
        if ((++reads & 4095) == 0 && System.nanoTime() > deadlineNanos) throw new Expired(budgetMillis);
        return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return text.subSequence(start, end);
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
