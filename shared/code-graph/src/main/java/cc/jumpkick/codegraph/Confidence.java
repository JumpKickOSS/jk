// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

/** How an edge is known: read straight from the source, derived by resolution, or a guess. */
public enum Confidence {
    EXTRACTED(1.0),
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

    /** The tag for {@code name}, {@link #EXTRACTED} for an unknown or missing one. */
    public static Confidence of(String name) {
        for (Confidence c : values()) {
            if (c.name().equals(name)) return c;
        }
        return EXTRACTED;
    }
}
