// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Mid-flight job shapes for history enrichment and SSE connect rehydrate. */
public final class HttpLive {
    private HttpLive() {}

    /**
     * One in-flight job. {@code progress} is NaN when unknown. {@code lastEventAt} is the wall
     * clock of the newest progress/remaining-work signal ({@code 0} when the job has emitted
     * none) — stall detection keys on this, never on {@code startedAt}. {@code remainingMs}/
     * {@code r0Ms} are {@code -1} when unknown. {@code modules}/{@code tasks} carry finished +
     * currently-running phase chains so a hard refresh can paint the same strip as a tab that
     * was open from the start.
     */
    public record Run(
            long requestId,
            long buildNumber,
            String kind,
            String dir,
            @Nullable String coord,
            long startedAt,
            long lastEventAt,
            double progress,
            @Nullable String journalId,
            long remainingMs,
            long r0Ms,
            long numerator,
            long denominator,
            List<Module> modules,
            List<Task> tasks) {

        public Run {
            modules = modules == null ? List.of() : List.copyOf(modules);
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }

        /** Compact constructor for tests that only need identity + progress rebind. */
        public Run(
                long requestId,
                long buildNumber,
                String kind,
                String dir,
                String coord,
                long startedAt,
                double progress,
                String journalId) {
            this(
                    requestId,
                    buildNumber,
                    kind,
                    dir,
                    coord,
                    startedAt,
                    0L,
                    progress,
                    journalId,
                    -1L,
                    -1L,
                    0L,
                    0L,
                    List.of(),
                    List.of());
        }
    }

    /** One module's mid-flight chain (finished modules + in-progress ones with steps so far). */
    public record Module(
            String dir,
            @Nullable String coord,
            boolean finished,
            boolean success,
            long millis,
            boolean didWork,
            List<Task> tasks) {
        public Module {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    /**
     * One task: {@code status} is wire {@code SUCCESS}/{@code FAIL}/{@code RUN}/… (same as
     * journal {@code tasks[].status}).
     */
    public record Task(String name, String stage, String status, long millis) {}
}
