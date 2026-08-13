// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

/** How {@code serveConnection} handles a request. Four arms, forever. */
public sealed interface VerbShape {

    /** hello, ping, status, shutdown — stay on the process. */
    record Lifecycle() implements VerbShape {}

    /** explain, tree, why — inline, connection continues. */
    record SyncRead() implements VerbShape {}

    /** Fork + watch; joins {@code activeBuildPlans}; cache read lock. */
    record AsyncPlan() implements VerbShape {}

    /** Idle-boundary; takes the cache write lock itself. */
    record CacheMaint() implements VerbShape {}
}
