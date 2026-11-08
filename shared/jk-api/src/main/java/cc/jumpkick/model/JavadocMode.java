// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * How a library is documented ({@code javadoc}): never; only when it is published or the docs are
 * asked for (the default); on every build with doclint off; or on every build with javadoc's own
 * recommended checks, which fail the step on a malformed comment.
 */
public enum JavadocMode {
    /** {@code javadoc = false}: no javadoc jar, not even for {@code jk publish}. */
    DISABLED,
    /**
     * No {@code javadoc} key: {@code jk build} writes no javadoc jar; {@code jk publish} and {@code
     * jk javadoc} write it, lenient, because Maven Central refuses a release without one. Javadoc
     * on a large workspace costs as much as its compile, for a jar nobody reads locally.
     */
    PUBLISH,
    /**
     * {@code -Xdoclint:none}: an imperfect comment is a warning in the results, never a failure —
     * a line javadoc still calls an error is a warning too, and the jar is written regardless.
     */
    LENIENT,
    /** javadoc's default doclint groups; a doclint error fails {@code package-javadoc}. */
    STRICT;

    /** Whether every {@code jk build} writes the javadoc jar: the module opted in. */
    public boolean enabled() {
        return this == LENIENT || this == STRICT;
    }

    /**
     * Whether a build that asked for docs ({@code jk publish}, {@code jk javadoc}) writes the
     * javadoc jar: anything but an explicit {@code javadoc = false}.
     */
    public boolean whenAsked() {
        return this != DISABLED;
    }
}
