// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.jobs.JobOrigin;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * The engine's half of a graph question's compile step: a {@code compile} job for the modules whose
 * main sources moved since their classes, submitted as any other job is (admission, the checkout's
 * build slot, memory leases, a row in the history, a graph refresh when it passes) and awaited.
 */
public final class GraphCompiles implements GraphRunner.Compiler {

    /** What the history shows started it. */
    static final JobOrigin ORIGIN = new JobOrigin("graph", null);

    /** How long a question waits for a build of the same checkout to finish before it answers without. */
    static final Duration BUSY_WAIT = Duration.ofSeconds(5);

    /** How long a question waits for its compile before answering from the classes it has. */
    static final Duration COMPILE_WAIT = Duration.ofSeconds(90);

    private static final Duration POLL = Duration.ofMillis(100);

    /** Build-like kinds another job of the same checkout would be compiling under. */
    private static final List<String> BUILDING =
            List.of("build", "compile", "test", "install", "snapshot", "native", "publish", "image");

    private final ToLongFunction<JobSpec> submit;
    private final Supplier<List<HttpLive.Run>> live;
    private final LongFunction<Optional<String>> records;
    private final Clock clock;

    /**
     * @param submit starts a job as {@code POST /api/build} does, answering its jid
     * @param live the engine's running jobs
     * @param records a finished job's journal record, by jid
     */
    public GraphCompiles(
            ToLongFunction<JobSpec> submit,
            Supplier<List<HttpLive.Run>> live,
            LongFunction<Optional<String>> records,
            Clock clock) {
        this.submit = submit;
        this.live = live;
        this.records = records;
        this.clock = clock;
    }

    @Override
    public @Nullable String compile(Path root, List<String> modules) throws InterruptedException {
        if (!waitWhileBuilding(root)) return "a build of this checkout is running";
        long jid;
        try {
            jid = submit.applyAsLong(new JobSpec(
                    "compile",
                    root.toString(),
                    modules,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    false,
                    null,
                    ORIGIN));
        } catch (RuntimeException refused) {
            return "the compile was refused: " + refused.getMessage();
        }
        long deadline = clock.nanos() + COMPILE_WAIT.toNanos();
        while (clock.nanos() < deadline) {
            Optional<String> record = records.apply(jid);
            if (record.isPresent()) return verdict(record.get());
            Thread.sleep(POLL.toMillis());
        }
        return "the compile took longer than " + COMPILE_WAIT.toSeconds() + " s; it goes on as job " + jid;
    }

    /** False when a build-like job of {@code root} is still running after {@link #BUSY_WAIT}. */
    private boolean waitWhileBuilding(Path root) throws InterruptedException {
        String dir = root.toAbsolutePath().normalize().toString();
        long deadline = clock.nanos() + BUSY_WAIT.toNanos();
        while (building(dir)) {
            if (clock.nanos() >= deadline) return false;
            Thread.sleep(POLL.toMillis());
        }
        return true;
    }

    private boolean building(String dir) {
        for (HttpLive.Run run : live.get()) {
            if (dir.equals(run.dir()) && BUILDING.contains(run.kind())) return true;
        }
        return false;
    }

    /** {@code null} for a compile that passed, else its first error, one line. */
    static @Nullable String verdict(String record) {
        Object rec = MiniJson.parse(record);
        if (Boolean.TRUE.equals(MiniJson.get(rec, "success"))) return null;
        for (Object d : MiniJson.list(rec, "diagnostics")) {
            if (d instanceof Map<?, ?> m && "error".equals(m.get("severity")) && m.get("message") != null) {
                String message = String.valueOf(m.get("message"))
                        .lines()
                        .findFirst()
                        .orElse("")
                        .strip();
                Object file = m.get("file");
                Object line = m.get("line");
                String number = line instanceof Number n ? Long.toString(n.longValue()) : null;
                String at = file == null ? "" : file + (number == null ? "" : ":" + number) + ": ";
                return "the code does not compile (" + at + message + ")";
            }
        }
        return "the code does not compile";
    }
}
