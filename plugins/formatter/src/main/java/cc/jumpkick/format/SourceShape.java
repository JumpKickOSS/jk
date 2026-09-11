// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import java.util.Arrays;

/**
 * What a file that outlasted its per-file timeout looks like.
 *
 * <p>A formatter chooses line breaks by searching the expression tree, so its cost climbs with
 * nesting depth rather than with file size: the source that pegs a thread is a chain of nested
 * lambdas, not a big class. Reading the offending file once and reporting those two shapes turns
 * "timed out" into something the author can act on.
 *
 * <p>Runs only after a timeout. A pre-pass would charge the whole tree a scan to describe the one
 * file in a thousand that needs it, which is the cost this deliberately does not pay.
 */
final class SourceShape {

    private SourceShape() {}

    /** Below these the shape is ordinary and says nothing about why the formatter struggled. */
    private static final int NOTABLE_PAREN_DEPTH = 8;

    private static final int NOTABLE_LAMBDA_NESTING = 3;

    /**
     * Deepest parenthesis nesting in a file, the line it peaks on, and how many lambda arrows are
     * open at once at the deepest point of any nest.
     */
    record Shape(int parenDepth, int parenLine, int lambdaNesting) {

        /**
         * Whether this shape accounts for a formatter stalling on the file.
         *
         * <p>Two uses, and the second is why the threshold carries weight. It decides whether the
         * timeout error can say <em>why</em>, and it decides whether the verdict is worth
         * remembering: an ordinary file that blew the limit is far more likely a host that stalled
         * than a source nothing can format, and remembering that would refuse a good file on every
         * later run.
         */
        boolean explainsAStall() {
            return parenDepth >= NOTABLE_PAREN_DEPTH || lambdaNesting >= NOTABLE_LAMBDA_NESTING;
        }
    }

    /**
     * The tail to append to a timeout message: a phrase naming the shape, or empty when the shape
     * {@linkplain Shape#explainsAStall explains nothing}.
     */
    static String phrase(Shape shape) {
        if (!shape.explainsAStall()) return "";
        String lambdas = shape.lambdaNesting() >= 2 ? ", " + shape.lambdaNesting() + " nested lambdas" : "";
        return "; deepest expression nesting here is " + shape.parenDepth() + " parentheses at line "
                + shape.parenLine() + lambdas
                + " — a line-break search grows steeply with nesting, so splitting that expression into"
                + " named locals or helper methods is usually the fix";
    }

    /** {@link #phrase} for {@code source}, measured. */
    static String postMortem(String source) {
        return phrase(of(source));
    }

    /**
     * Measure {@code source}. Comments and string bodies are blanked first, so a parenthesis in
     * prose or in a literal cannot look like structure.
     */
    static Shape of(String source) {
        String src = JavaText.blanked(source);
        int depth = 0;
        int maxDepth = 0;
        int maxLine = 1;
        int line = 1;
        int lambdas = 0;
        int maxLambdas = 0;
        // One flag per open group: whether an arrow has already been counted at that depth, so a
        // `(a, b) -> ...` chain contributes once per level rather than once per arrow. Depth 0 never
        // closes, so an arrow there — a `case X ->` arm, a lambda assigned to a field — is not one
        // that nests, and is not counted.
        boolean[] arrowed = new boolean[64];
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            switch (c) {
                case '\n' -> line++;
                case '(' -> {
                    depth++;
                    if (depth >= arrowed.length) arrowed = Arrays.copyOf(arrowed, arrowed.length * 2);
                    arrowed[depth] = false;
                    if (depth > maxDepth) {
                        maxDepth = depth;
                        maxLine = line;
                    }
                }
                case ')' -> {
                    if (depth > 0) {
                        if (arrowed[depth]) lambdas--;
                        depth--;
                    }
                }
                case '-' -> {
                    // `i-->0` is a decrement followed by a comparison, not an arrow.
                    boolean arrow =
                            i + 1 < src.length() && src.charAt(i + 1) == '>' && (i == 0 || src.charAt(i - 1) != '-');
                    if (arrow && depth > 0 && !arrowed[depth]) {
                        arrowed[depth] = true;
                        lambdas++;
                        if (lambdas > maxLambdas) maxLambdas = lambdas;
                    }
                }
                default -> {
                    /* not structure */
                }
            }
        }
        return new Shape(maxDepth, maxLine, maxLambdas);
    }
}
