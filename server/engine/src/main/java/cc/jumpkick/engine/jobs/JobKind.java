// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

/**
 * How a hosted job joins the engine's lifecycle. Replaces {@code plan}/{@code workspaceStream}
 * boolean flags on the submit path.
 */
public sealed interface JobKind {

    String verb();

    /** Workspace build: terminal is {@code workspace-finish}; joins {@code activeBuildPlans}. */
    record Workspace(String verb) implements JobKind {}

    /** Single plan (test, lock, native, …): terminal is {@code plan-finish}; joins active plans. */
    record Plan(String verb) implements JobKind {}

    /** Cache maintenance: does not join active plans; takes the cache write lock itself. */
    record Maintenance(String verb) implements JobKind {}

    default boolean joinsActivePlans() {
        return !(this instanceof Maintenance);
    }

    default boolean workspaceTerminal() {
        return this instanceof Workspace;
    }

    static JobKind workspace(String verb) {
        return new Workspace(verb);
    }

    static JobKind plan(String verb) {
        return new Plan(verb);
    }

    static JobKind maintenance(String verb) {
        return new Maintenance(verb);
    }
}
