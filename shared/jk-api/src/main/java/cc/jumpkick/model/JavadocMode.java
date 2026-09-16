// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * How {@code jk package} documents a library ({@code javadoc}): no javadoc jar, javadoc with
 * doclint off, or javadoc's own recommended checks — which fail the step on a malformed comment.
 */
public enum JavadocMode {
    DISABLED,
    /**
     * {@code -Xdoclint:none}: an imperfect comment is a warning in the results, never a failure —
     * a line javadoc still calls an error is a warning too, and the jar is written regardless.
     */
    LENIENT,
    /** javadoc's default doclint groups; a doclint error fails {@code package-javadoc}. */
    STRICT;

    public boolean enabled() {
        return this != DISABLED;
    }
}
