// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.Locale;

/**
 * How an edge is known, which is also the grade of an edge to an anchor ({@link Anchors}): {@link
 * #EXTRACTED} read straight from the code or bound by a compiler or generator ({@code bound}),
 * {@link #DECLARED} read from a framework annotation's literal or a definition file's syntax
 * ({@code declared}), {@link #INFERRED} and {@link #AMBIGUOUS} a string or a name matched ({@code
 * inferred}).
 */
public enum Confidence {
    EXTRACTED(1.0),
    DECLARED(0.9),
    INFERRED(0.55),
    AMBIGUOUS(0.2);

    private final double defaultScore;

    Confidence(double defaultScore) {
        this.defaultScore = defaultScore;
    }

    /** The {@code confidence_score} an edge that carries none is read with. */
    public double defaultScore() {
        return defaultScore;
    }

    /** {@code bound}, {@code declared} or {@code inferred}: the grade an answer names. */
    public String grade() {
        return switch (this) {
            case EXTRACTED -> "bound";
            case DECLARED -> "declared";
            case INFERRED, AMBIGUOUS -> "inferred";
        };
    }

    /** True for an edge an answer marks: one matched rather than read or bound. */
    public boolean marked() {
        return this == INFERRED || this == AMBIGUOUS;
    }

    /** {@code inferred} or {@code ambiguous}, the word an answer marks an edge with; empty when unmarked. */
    public String mark() {
        return marked() ? name().toLowerCase(Locale.ROOT) : "";
    }

    /** The tag for {@code name}, {@link #EXTRACTED} for an unknown or missing one. */
    public static Confidence of(String name) {
        for (Confidence c : values()) {
            if (c.name().equals(name)) return c;
        }
        return EXTRACTED;
    }
}
