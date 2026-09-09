// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    record Shape(int parenDepth, int parenLine, int lambdaNesting) {}

    /**
     * As {@link #postMortem(String)}, reading {@code file}. Empty when it cannot be read — a
     * diagnostic must not become the failure.
     */
    static String postMortem(File file) {
        try {
            return postMortem(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The tail to append to a timeout message: a phrase naming the shape, or empty when the source's
     * shape is unremarkable and so explains nothing.
     */
    static String postMortem(String source) {
        Shape shape = of(source);
        if (shape.parenDepth() < NOTABLE_PAREN_DEPTH && shape.lambdaNesting() < NOTABLE_LAMBDA_NESTING) {
            return "";
        }
        String lambdas = shape.lambdaNesting() >= 2 ? ", " + shape.lambdaNesting() + " nested lambdas" : "";
        return "; deepest expression nesting here is " + shape.parenDepth() + " parentheses at line "
                + shape.parenLine() + lambdas
                + " — a line-break search grows steeply with nesting, so splitting that expression into"
                + " named locals or helper methods is usually the fix";
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
        // `(a, b) -> ...` chain contributes once per level rather than once per arrow.
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
                    if (i + 1 < src.length() && src.charAt(i + 1) == '>' && !arrowed[depth]) {
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
