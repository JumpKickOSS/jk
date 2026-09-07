// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.task.IoLedger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Engine-process resources a job runs against: ids, clock, ledgers, gates and the idle cycle. */
public interface JobRuntime {
    long nextRequestId();

    long nowMillis();

    /** The cache-maintenance gate; a plan holds its read side for the runner's life. */
    ReentrantReadWriteLock cacheGate();

    IoLedger runIo(long id);

    InFlightBuilds inFlight();

    void maybeIdleBoundary();

    void maybeIdleGc();

    void log(String message);

    String version();
}
