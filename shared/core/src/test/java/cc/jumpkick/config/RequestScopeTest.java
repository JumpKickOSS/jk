// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.JkThreads;
import cc.jumpkick.task.IoLedger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Facts derived once per request, and never carried into the next one.
 *
 * <p>Every cache wanted was blocked on the same question — when may it answer? — and the
 * answer here is structural rather than a policy: a scope is keyed on the request's {@code IoLedger},
 * which is created once per invocation and shared by every copy of the {@code Session}, so a scope
 * cannot outlive the request that made it. Nothing to invalidate, and a {@code jk watch} iteration
 * gets a new scope because it is a new request.
 */
class RequestScopeTest {

    @AfterEach
    void clear() {
        RequestScope.clearAll();
        SessionContext.reset();
    }

    @Test
    void a_fact_is_computed_once_within_one_request() {
        AtomicInteger computed = new AtomicInteger();

        inRequest(() -> {
            for (int i = 0; i < 20; i++) {
                String v = RequestScope.current().get("k", k -> {
                    computed.incrementAndGet();
                    return "value";
                });
                assertThat(v).isEqualTo("value");
            }
        });

        assertThat(computed.get()).isEqualTo(1);
    }

    @Test
    void a_second_request_does_not_see_the_first_ones_facts() {
        // The property that makes invalidation unnecessary: a new request is a new ledger, so it is a
        // new scope. This is what a `jk watch` iteration gets.
        AtomicInteger computed = new AtomicInteger();
        inRequest(() -> RequestScope.current().get("k", k -> computed.incrementAndGet()));
        inRequest(() -> RequestScope.current().get("k", k -> computed.incrementAndGet()));

        assertThat(computed.get()).as("each request computes its own").isEqualTo(2);
    }

    @Test
    void a_caller_with_no_request_still_gets_a_correct_answer() {
        // Off a request — a unit test, a CLI-side helper — every lookup misses. Correct, just not
        // cached; the alternative would be a process-lifetime cache with no invalidation story, which
        // is the thing this design exists to avoid.
        AtomicInteger computed = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            String v = RequestScope.current().get("k", k -> {
                computed.incrementAndGet();
                return "value";
            });
            assertThat(v).isEqualTo("value");
        }
        assertThat(computed.get()).isEqualTo(5);
        assertThat(RequestScope.current().size())
                .as("nothing is retained off a request")
                .isZero();
    }

    @Test
    void two_requests_do_not_share_a_scope_object() {
        RequestScope[] seen = new RequestScope[2];
        inRequest(() -> seen[0] = RequestScope.current());
        inRequest(() -> seen[1] = RequestScope.current());
        assertThat(seen[0]).isNotSameAs(seen[1]);
    }

    @Test
    void a_cpu_pool_task_runs_in_its_own_requests_scope() {
        // The step bodies that scan for sources are TaskKind.CPU, so they run on JkThreads.cpu() —
        // a process-wide ForkJoinPool whose workers outlive every request. The scope a task sees
        // there must be the scope of the request that submitted it, or the memo is answering for
        // somebody else's build.
        RequestScope[] onSubmitter = new RequestScope[1];
        RequestScope[] onWorker = new RequestScope[1];

        inRequest(() -> {
            onSubmitter[0] = RequestScope.current();
            onWorker[0] = onCpuPool(RequestScope::current);
        });

        assertThat(onWorker[0])
                .as("a CPU pool task shares the submitting request's scope")
                .isSameAs(onSubmitter[0]);
    }

    @Test
    void a_cpu_pool_worker_does_not_carry_one_requests_scope_into_the_next() {
        // . The pool grows lazily, so its workers are created inside whichever request first
        // needed them, and IoLedger's holder is an InheritableThreadLocal — the worker was born
        // holding that request's ledger and kept it for the engine's life. Every later build's CPU
        // steps then read the FIRST build's derived facts: a module with no src/test/java when the
        // pool warmed had that scan memoized empty, so `jk test` said "no test sources" — and exited
        // green — for every test written afterwards, until the engine restarted.
        AtomicInteger computed = new AtomicInteger();

        inRequest(() -> onCpuPool(() -> RequestScope.current().get("scan", k -> computed.incrementAndGet())));
        inRequest(() -> onCpuPool(() -> RequestScope.current().get("scan", k -> computed.incrementAndGet())));

        assertThat(computed.get())
                .as("the second request rescans rather than reusing the first request's answer")
                .isEqualTo(2);
    }

    @Test
    void a_cpu_pool_task_submitted_off_a_request_is_unscoped() {
        // The honest answer off a request is "no request" — uncached, never stale. A worker that
        // inherited some earlier request's ledger would instead report that request, and cache into
        // a scope nobody can invalidate.
        assertThat(onCpuPool(IoLedger::ambient))
                .as("no request submitted this, so the worker must be bound to none")
                .isNull();
    }

    @Test
    void release_ends_the_scope_while_a_thread_still_holds_the_ledger() {
        // The weak key is not what ends a scope: the ledger is an inheritable thread-local, and a
        // pooled thread that inherited it keeps it — and the scope, and every listing the job memoised —
        // for the engine's life. JobEnvelope releases explicitly, so the next request starts clean even
        // though this reference is still live.
        IoLedger ledger = new IoLedger();
        IoLedger.open(ledger);
        RequestScope before;
        try {
            before = RequestScope.current();
            before.get("k", k -> "heavy");
            assertThat(before.size()).isEqualTo(1);
            RequestScope.release();
            RequestScope after = RequestScope.current();
            assertThat(after).isNotSameAs(before);
            assertThat(after.size())
                    .as("a fresh scope: the released facts are not visible")
                    .isZero();
        } finally {
            IoLedger.close();
        }
        assertThat(ledger).isNotNull(); // still strongly held here, and the scope is gone regardless
    }

    /** Run {@code work} on the shared CPU pool and hand back its result. */
    private static <T> T onCpuPool(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, JkThreads.cpu()).join();
    }

    /**
     * Run {@code body} the way a real request runs: with an {@link IoLedger} opened around it.
     *
     * <p>Installing a {@code Session} is not enough, and that distinction is the design. The session
     * is never null — off a request it is {@code Session.defaults()} — so a scope keyed on it would
     * live for the process. {@code IoLedger.open} is called in exactly one place, {@code JobEnvelope},
     * which is what makes "a request exists" answerable at all.
     */
    private static void inRequest(Runnable body) {
        IoLedger ledger = new IoLedger();
        IoLedger.open(ledger);
        try {
            SessionContext.runWhere(Session.defaults().withIo(ledger), body);
        } finally {
            IoLedger.close();
        }
    }
}
