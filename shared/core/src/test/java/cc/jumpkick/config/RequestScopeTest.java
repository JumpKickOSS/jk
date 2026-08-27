// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.IoLedger;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Facts derived once per request, and never carried into the next one.
 *
 * <p>Every cache JK-1027 wanted was blocked on the same question — when may it answer? — and the
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
