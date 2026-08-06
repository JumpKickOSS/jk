// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.run.BuildPlanResult;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Always-on plan-event recorder. Writes the same JSONL shape as {@link JsonlListener} but to
 * {@code <cacheRoot>/runs/<ts>-<plan>.jsonl} — one file per invocation. The cache prune sweep
 * collects files older than 7 days (see {@link cc.jumpkick.task.RunLogGc}).
 *
 * <p>Purpose: post-hoc debugging ({@code jk debug last}), usage analytics, CI traces, future "what
 * was slow in this build" tooling.
 *
 * <p>Writes are best-effort: an IO failure during a single event silently drops that event. A
 * failure opening the file makes the whole listener a no-op for the plan's lifetime.
 */
public final class EventLogListener extends JsonlEmittingListener {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path file;
    private final PrintStream stream;

    private EventLogListener(Path file, PrintStream stream) {
        super(false);
        this.file = file;
        this.stream = stream;
    }

    /**
     * Open a fresh log under {@code cacheRoot/runs/}. {@code planName} is folded into the filename
     * for human scanning. Returns {@code null} on failure — caller can simply skip adding the
     * listener.
     */
    public static EventLogListener open(Path cacheRoot, String planName) {
        try {
            Path dir = cacheRoot.resolve("runs");
            Files.createDirectories(dir);
            String safeBuildPlan = planName.replaceAll("[^A-Za-z0-9_.-]", "_");
            Path file = dir.resolve(TS_FORMAT.format(Instant.now()) + "-" + safeBuildPlan + ".jsonl");
            PrintStream stream = new PrintStream(
                    Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    /* autoFlush= */ true,
                    StandardCharsets.UTF_8);
            return new EventLogListener(file, stream);
        } catch (IOException e) {
            return null;
        }
    }

    public Path file() {
        return file;
    }

    @Override
    protected void emit(String line, boolean immediate) {
        try {
            stream.println(line);
        } catch (RuntimeException ignored) {
            // Best-effort logging.
        }
    }

    @Override
    public void planFinish(BuildPlanResult r) {
        super.planFinish(r);
        try {
            stream.close();
        } catch (RuntimeException ignored) {
        }
    }
}
