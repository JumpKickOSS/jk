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

    /**
     * Toolchain provisioning (a Maven or Gradle distribution, a tool lookup): a live plan that acts
     * on the machine, not on the project's sources, so it writes nothing under {@code target/}.
     */
    record Toolchain(String verb) implements JobKind {}

    default boolean joinsActivePlans() {
        return !(this instanceof Maintenance);
    }

    /**
     * Whether the job writes the Chrome timeline into the project's {@code target/}. Maintenance
     * jobs delete outputs — a clean that leaves a fresh {@code target/jk-profile.json} behind
     * un-cleans itself — and a toolchain job precedes the run that owns {@code target/}: the
     * timeline a reader finds after {@code jk mvn} must be the Maven run's, not the provisioning's.
     */
    default boolean writesTimeline() {
        return !(this instanceof Maintenance) && !(this instanceof Toolchain);
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

    static JobKind toolchain(String verb) {
        return new Toolchain(verb);
    }
}
