// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;

/** The dashboard event bus and the per-request progress state a job's events are decorated with. */
public interface JobEvents {
    void publishRequestStart(long id, String kind, String dir, long buildNumber);

    void publishEvent(String type, JsonOut payload);

    JsonOut withProgress(JsonOut payload, long id);

    JsonOut withIo(JsonOut payload, long id);

    void putLastProgress(long id, double percent);

    /** Retire the request's session; the journal must already be written. */
    void clearProgress(long id);

    void putMode(long id, ProgressBarMode mode);

    /** Bind the current thread's events to {@code id} for the runner's life. */
    void bindEventRequestId(long id);

    void unbindEventRequestId();
}
