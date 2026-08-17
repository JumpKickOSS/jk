// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

/**
 * How {@code serveConnection} handles a request. Three arms; process lifecycle
 * (hello, ping, status, shutdown, auth, cancel) stays on the process, never the registry.
 */
public sealed interface VerbShape {

    /** explain, tree, why — inline, connection continues. */
    record SyncRead() implements VerbShape {}

    /** Fork + watch; joins {@code activeBuildPlans}; cache read lock. */
    record AsyncPlan() implements VerbShape {}

    /** Idle-boundary; takes the cache write lock itself. */
    record CacheMaint() implements VerbShape {}
}
