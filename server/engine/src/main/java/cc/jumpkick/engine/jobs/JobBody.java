// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.Session;
import java.io.BufferedWriter;

/** Decode the request, run it, stream events to {@code writer} ({@code null} = detached job). */
@FunctionalInterface
public interface JobBody {
    void run(
            String requestLine,
            Session.CancelToken cancelToken,
            @org.jspecify.annotations.Nullable BufferedWriter writer);
}
