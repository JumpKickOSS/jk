// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * One item on a {@link JavaCompilerHost} pool's queue: the compile or PLAN request, the heap of the
 * worker it is for, and what the worker answered. Its futures are what the submitter waits on.
 */
final class CompileWork {
    final ForkedJavac.@Nullable Request req;
    final boolean plan;
    /** The heap of the worker this item is for; null when the user pinned worker memory. */
    final @Nullable Long heapBytes;
    /** Set on a retry: the heap the first worker ran out of. */
    @Nullable
    Long previousHeapBytes;

    final CompletableFuture<ForkedJavac.Result> compile = new CompletableFuture<>();
    final CompletableFuture<ForkedJavac.Plan> forecast = new CompletableFuture<>();
    final List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
    final Map<Path, Set<Path>> generated = new TreeMap<>();
    final List<Path> compiledSources = new ArrayList<>();
    final List<String> whys = new ArrayList<>();

    @Nullable
    Path spec;

    @Nullable
    String status;

    @Nullable
    String outcome;

    @Nullable
    String reason;
    /** nanoTime at enqueue and the queue wait measured at dispatch — the step's wait, not its work. */
    long enqueuedNanos;

    long waitNanos;

    private CompileWork(ForkedJavac.@Nullable Request req, boolean plan, @Nullable Long heapBytes) {
        this.req = req;
        this.plan = plan;
        this.heapBytes = heapBytes;
    }

    static CompileWork compile(ForkedJavac.Request req) {
        return new CompileWork(req, false, null);
    }

    static CompileWork compile(ForkedJavac.Request req, @Nullable Long heapBytes) {
        return new CompileWork(req, false, heapBytes);
    }

    static CompileWork plan(ForkedJavac.Request req) {
        return new CompileWork(req, true, null);
    }

    static CompileWork plan(ForkedJavac.Request req, @Nullable Long heapBytes) {
        return new CompileWork(req, true, heapBytes);
    }

    static final CompileWork POISON = new CompileWork(null, false, null);
}
