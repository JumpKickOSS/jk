// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import org.jspecify.annotations.NullMarked;

/**
 * The header's {@code tokens ≈ N}: how much of a context window the file costs, so an agent can
 * choose between it and {@code details.jsonl} before reading either. A fixed ratio of characters
 * per token stands in for a tokenizer; a report is paths, coordinates and code fences more than
 * prose, which tokenize shorter than the four characters a paragraph of English averages.
 */
@NullMarked
final class JkResultsTokens {

    static final double CHARS_PER_TOKEN = 3.6;

    static final String LABEL = "tokens ≈ ";

    private JkResultsTokens() {}

    /** The estimate for a file of {@code chars} characters. */
    static long estimate(int chars) {
        return (long) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    /** The header line for a file that is {@code bodyChars} long before this line is added to it. */
    static String line(int bodyChars) {
        long n = estimate(bodyChars);
        String line = LABEL + n + "\n";
        long whole = estimate(bodyChars + line.length());
        return whole == n ? line : LABEL + whole + "\n";
    }
}
