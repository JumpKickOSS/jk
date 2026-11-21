// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import java.util.List;

/**
 * One thing a session did, as episode inference reads it: a finished run or a non-run action.
 *
 * @param at when it started, epoch millis
 * @param millis how long it took
 * @param project the checkout it acted on (absolute dir)
 * @param module what {@code Working in <module>} names: a run's module, else the project's name
 * @param kind what it was
 * @param ok a green run, or an action that succeeded
 * @param failingTests a red run's failing tests as {@code SimpleClass#method}, in report order
 * @param target the coordinate a dependency change named, or the address a rename moved
 * @param count the coordinates a dependency change named, or the pins an update moved
 * @param edited the project-relative files a run saw changed since the session's run before it
 */
public record Fact(
        long at,
        long millis,
        String project,
        String module,
        Kind kind,
        boolean ok,
        List<String> failingTests,
        String target,
        int count,
        List<String> edited) {

    /** The fact kinds inference tells apart. */
    public enum Kind {
        RUN,
        /** A code-graph question: {@code query}, {@code usages}, {@code read}, {@code find}. */
        GRAPH,
        RENAME,
        /** {@code deps add}. */
        ADD,
        /** {@code deps remove}. */
        REMOVE,
        /** {@code deps pin}. */
        PIN,
        UPDATE,
        /** Anything else the session did ({@code workspace}). */
        OTHER
    }

    public Fact {
        project = project == null ? "" : project;
        module = module == null ? "" : module;
        failingTests = failingTests == null ? List.of() : List.copyOf(failingTests);
        target = target == null ? "" : target;
        edited = edited == null ? List.of() : List.copyOf(edited);
        millis = Math.max(0, millis);
    }

    /** A finished run. */
    public static Fact run(long at, long millis, String project, String module, boolean ok, List<String> failingTests) {
        return run(at, millis, project, module, ok, failingTests, List.of());
    }

    /** A finished run that saw {@code edited} changed. */
    public static Fact run(
            long at,
            long millis,
            String project,
            String module,
            boolean ok,
            List<String> failingTests,
            List<String> edited) {
        return new Fact(at, millis, project, module, Kind.RUN, ok, failingTests, "", 0, edited);
    }

    /** A non-run action. */
    public static Fact action(long at, long millis, String project, Kind kind, boolean ok, String target, int count) {
        return new Fact(at, millis, project, "", kind, ok, List.of(), target, count, List.of());
    }

    /** When it finished. */
    public long end() {
        return at + millis;
    }
}
