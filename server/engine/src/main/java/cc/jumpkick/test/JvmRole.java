// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

/** Which JVM {@link JUnitLauncher} is forking; only {@link #SUITE} may carry the debug agent. */
enum JvmRole {
    /** {@code --list-only} discovery: names classes, runs nothing. */
    DISCOVERY,
    /** A pull-mode shard worker. */
    PULL_WORKER,
    /** The one JVM that runs the tests in single-worker mode. */
    SUITE
}
