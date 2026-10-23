// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The goal of a build-like verb on the one lifecycle they all share. Every goal runs the same
 * phases, by the same steps, up to its {@link #through()} phase; a goal past {@link Phase#PACKAGE}
 * then appends its own tail (the engine's {@code Tail} for it). Whether tests run is this table's
 * {@link #tests()} policy, never a different plan. See {@code docs/contributors/lifecycle.md}.
 */
public enum WorkspaceTarget {
    /** {@code jk compile}. */
    COMPILE(Phase.COMPILE, Tests.NOT_RUN),
    /** {@code jk test}: packages only the modules a sibling's tests consume. */
    TEST(Phase.TEST, Tests.ALWAYS),
    /** {@code jk build}: the module's jar and the deliveries its manifest declares. */
    PACKAGE(Phase.PACKAGE, Tests.UNLESS_SKIPPED),
    /** {@code jk install}: then {@code cache-install}; the launcher and PATH placement are client-side. */
    INSTALL(Phase.PACKAGE, Tests.UNLESS_SKIPPED),
    /** {@code jk native}: then {@code native-image} for the selected modules. */
    NATIVE(Phase.PACKAGE, Tests.UNLESS_SKIPPED),
    /** {@code jk image}: then {@code image-plan} and {@code write-image} for the selected module. */
    IMAGE(Phase.PACKAGE, Tests.UNLESS_SKIPPED),
    /** {@code jk publish}: then the upload of the selected modules' artifacts. */
    PUBLISH(Phase.PACKAGE, Tests.UNLESS_SKIPPED),
    /** {@code jk train}: then the training run of the selected module. */
    TRAIN(Phase.PACKAGE, Tests.NOT_RUN),
    /** {@code jk run}, {@code jk dev}: the app launches client-side from what PACKAGE left. */
    RUN(Phase.PACKAGE, Tests.NOT_RUN),
    /**
     * Install's second pass once the engine has changed hands: {@code cache-install} against jars
     * the first pass left on disk. The one goal outside the lifecycle: re-running the phases under a
     * new engine would rebuild every plugin step for nothing the shelf needs.
     */
    RESHELVE(null, Tests.NOT_RUN);

    /** The lifecycle phases, in order. Every goal runs every phase up to its own. */
    public enum Phase {
        COMPILE,
        TEST,
        PACKAGE
    }

    /** Whether a goal runs tests. */
    public enum Tests {
        /** Tests are the goal; they cannot be skipped. */
        ALWAYS,
        /** Tests run; {@code --skip-tests} opts out. */
        UNLESS_SKIPPED,
        /** The goal runs no tests; {@code jk test} is how to ask for them. */
        NOT_RUN
    }

    private final @Nullable Phase through;
    private final Tests tests;

    WorkspaceTarget(@Nullable Phase through, Tests tests) {
        this.through = through;
        this.tests = tests;
    }

    /** The last phase this goal runs; null only for {@link #RESHELVE}. */
    public @Nullable Phase through() {
        return through;
    }

    public Tests tests() {
        return tests;
    }

    /**
     * The engine job kind a run of this goal is journaled under: {@code jk history}'s kind, the
     * build number it takes and its exclusive admission. A goal that only builds, or builds and
     * then acts client-side ({@code jk run}), is a {@code build}; install's re-shelve pass is
     * part of the install.
     */
    public String jobKind() {
        return switch (this) {
            case PACKAGE, RUN -> "build";
            case RESHELVE -> "install";
            default -> name().toLowerCase(Locale.ROOT);
        };
    }

    /** True when the goal runs PACKAGE and so may append a tail. */
    public boolean packages() {
        return through == Phase.PACKAGE;
    }

    public boolean testOnly() {
        return this == TEST;
    }

    public boolean compileOnly() {
        return this == COMPILE;
    }
}
