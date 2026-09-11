// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where one compile's wall time went, when the job names a file to append to.
 *
 * <p>A module's {@code compile-java} is a Zinc invocation wrapped in setup: loading annotation
 * processors, converting sources and classpath to Zinc's virtual files, reading the previous
 * analysis, sometimes clearing the class output, then the compile itself, then reconciling generated
 * files and persisting the analysis. From outside the worker all of that is one duration, which is
 * why a platform difference in it cannot be attributed to anything.
 *
 * <p>Off unless the job carries a sink: the timing costs a handful of clock reads, but the file it
 * writes is a measurement artifact and no build should produce one it was not asked for. The sink
 * comes from the shell that ran {@code jk} ({@code JK_COMPILE_PHASES}), forwarded by the engine in
 * the compile spec; the worker's own environment is the engine's and is not consulted.
 */
final class CompilePhases {

    private final @Nullable Path sink;
    private final Clock clock;
    private final List<String> phases = new ArrayList<>();
    private long mark;

    private CompilePhases(@Nullable Path sink, Clock clock) {
        this.sink = sink;
        this.clock = clock;
        this.mark = clock.nanos();
    }

    /** A recorder that appends to {@code sink}, or one that does nothing when there is none. */
    static CompilePhases open(@Nullable Path sink) {
        return new CompilePhases(sink, Clock.SYSTEM);
    }

    /** Close the phase named {@code name} and open the next. */
    void mark(String name) {
        if (sink == null) return;
        long now = clock.nanos();
        phases.add(name + "=" + (now - mark) / 1_000_000);
        mark = now;
    }

    /**
     * Append one line for the module compiled into {@code classOutput}. Best-effort: a measurement must never fail a compile, so a
     * write error is swallowed rather than surfaced as a compile diagnostic.
     */
    void write(Path classOutput, int sourceCount) {
        if (sink == null || phases.isEmpty()) return;
        String line = "module=" + classOutput + " sources=" + sourceCount + " " + String.join(" ", phases) + "\n";
        try {
            Path parent = sink.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(sink, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // A measurement artifact is never worth failing a build for.
        }
    }
}
