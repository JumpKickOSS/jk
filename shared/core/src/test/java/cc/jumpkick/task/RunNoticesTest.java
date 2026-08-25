// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** "Once" has to mean once per run: the engine is resident and serves build after build. */
class RunNoticesTest {

    @BeforeEach
    @AfterEach
    void forget() {
        RunNotices.clear();
    }

    /** One run, scoped the way the engine scopes one: a fresh session carries a fresh ledger. */
    private static String inOneRun(Runnable body) {
        var err = new ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            SessionContext.runWhere(Session.defaults(), body);
        } finally {
            System.setErr(original);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) n++;
        return n;
    }

    @Test
    void a_note_repeated_within_one_run_is_printed_once() {
        String out = inOneRun(() -> {
            for (int i = 0; i < 20; i++) RunNotices.warnOnce("k", () -> "the note");
        });
        assertThat(occurrences(out, "the note")).isEqualTo(1);
    }

    @Test
    void the_next_run_hears_it_again() {
        assertThat(occurrences(inOneRun(() -> RunNotices.warnOnce("k", () -> "the note")), "the note"))
                .isEqualTo(1);
        assertThat(occurrences(inOneRun(() -> RunNotices.warnOnce("k", () -> "the note")), "the note"))
                .as("a resident engine's second build is a second run")
                .isEqualTo(1);
    }

    /** Distinct keys are distinct facts; suppressing the second would be the real bug. */
    @Test
    void different_keys_are_different_notes() {
        String out = inOneRun(() -> {
            RunNotices.warnOnce("a", () -> "first note");
            RunNotices.warnOnce("b", () -> "second note");
            RunNotices.warnOnce("a", () -> "first note");
        });
        assertThat(occurrences(out, "first note")).isEqualTo(1);
        assertThat(occurrences(out, "second note")).isEqualTo(1);
    }

    /** A repeat must not pay for text nobody prints — that is why the message is a supplier. */
    @Test
    void a_suppressed_note_never_builds_its_message() {
        AtomicInteger built = new AtomicInteger();
        inOneRun(() -> {
            for (int i = 0; i < 5; i++) {
                RunNotices.warnOnce("k", () -> "note " + built.incrementAndGet());
            }
        });
        assertThat(built.get()).isEqualTo(1);
    }

    /**
     * Two concurrent runs are two scopes — the engine builds several projects at once. Ten calls
     * across two runs is two lines, not one and not ten.
     */
    @Test
    void two_concurrent_runs_each_say_it_once() throws Exception {
        Runnable body = () -> {
            for (int i = 0; i < 5; i++) RunNotices.warnOnce("k", () -> "the note");
        };
        var err = new ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            Thread ta = new Thread(() -> SessionContext.runWhere(Session.defaults(), body), "run-a");
            Thread tb = new Thread(() -> SessionContext.runWhere(Session.defaults(), body), "run-b");
            ta.start();
            tb.start();
            ta.join();
            tb.join();
        } finally {
            System.setErr(original);
        }
        assertThat(occurrences(err.toString(StandardCharsets.UTF_8), "the note"))
                .isEqualTo(2);
    }

    /** A diagnostic that fails a build is worse than one nobody reads. */
    @Test
    void a_throwing_message_supplier_is_swallowed() {
        String out = inOneRun(() -> {
            RunNotices.warnOnce("k", () -> {
                throw new IllegalStateException("boom");
            });
            RunNotices.warnOnce("j", () -> "still working");
        });
        assertThat(out).contains("still working").doesNotContain("boom");
    }
}
