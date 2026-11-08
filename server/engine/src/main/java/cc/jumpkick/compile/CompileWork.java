// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * One compile or PLAN on a {@link CompilerPool}: the request, the id every frame about it carries,
 * and what the worker answered. Its futures are what the submitter waits on.
 */
final class CompileWork {

    /** Ids are engine-wide, so a frame can never be taken for another item's, whatever the worker. */
    private static final AtomicLong IDS = new AtomicLong();

    final ForkedJavac.Request req;
    final boolean plan;

    /** What the worker's frames about this item carry; fixed for the item's life, retries included. */
    final long id = IDS.incrementAndGet();

    /**
     * The job that asked for this item and the session it asked under: a worker outlives jobs, so
     * it writes the spec under these, and the item is cancelled when that job ends.
     */
    long job;

    Session session = SessionContext.current();

    /**
     * How many workers died with this item in flight and were charged for it; a second fails it. A
     * worker stopped because another job's compile would not answer its cancel is not charged.
     */
    int deaths;

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

    /**
     * The shell of the request that asked for this item, captured on the request's thread. The
     * worker's dispatcher writes the spec on its own thread, where only the session is rebound; a
     * diagnostic the caller's shell set ({@code JK_COMPILE_PHASES}) is read from this.
     */
    @Nullable
    Map<String, String> shell;

    private CompileWork(ForkedJavac.Request req, boolean plan) {
        this.req = req;
        this.plan = plan;
    }

    static CompileWork compile(ForkedJavac.Request req) {
        return new CompileWork(req, false);
    }

    static CompileWork plan(ForkedJavac.Request req) {
        return new CompileWork(req, true);
    }

    /** This item is {@code job}'s, asked for under {@code session} by the shell bound now. */
    CompileWork forJob(long job, Session session) {
        this.job = job;
        this.session = session;
        RequestShell bound = RequestShell.current();
        this.shell = bound == null ? null : bound.vars();
        return this;
    }

    /** Whether this item has its answer, or its failure. */
    boolean done() {
        return compile.isDone() && forecast.isDone();
    }

    /** Fail both futures with {@code cause}; an item already answered keeps its answer. */
    void fail(Throwable cause) {
        compile.completeExceptionally(cause);
        forecast.completeExceptionally(cause);
    }

    /** Drop a dead worker's partial answer so the next worker compiles this item clean. */
    void clearAttempt() {
        diagnostics.clear();
        generated.clear();
        compiledSources.clear();
        whys.clear();
        spec = null;
        status = null;
        outcome = null;
        reason = null;
    }
}
