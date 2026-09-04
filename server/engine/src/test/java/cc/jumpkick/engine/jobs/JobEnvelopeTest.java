// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.InFlightBuilds;
import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fake-host envelope: refuse-when-draining, runner finishes, request-finish published. */
class JobEnvelopeTest {

    @Test
    void draining_plan_is_refused_without_running() {
        FakeHost host = new FakeHost();
        host.tryStart = false;
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        AtomicBoolean ran = new AtomicBoolean();
        StringWriter out = new StringWriter();
        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/p\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    ran.set(true);
                    return JobOutcome.declined();
                }),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        assertThat(ran).isFalse();
        assertThat(out.toString()).contains("shutting down");
        assertThat(host.abandoned).isZero();
    }

    @Test
    void runner_completes_and_publishes_request_finish() {
        FakeHost host = new FakeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        AtomicBoolean ran = new AtomicBoolean();
        StringWriter out = new StringWriter();
        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("lock", "jk-test-", (line, tok, w) -> {
                    ran.set(true);
                    return JobOutcome.declined();
                }),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        assertThat(ran).isTrue();
        assertThat(host.events.stream().anyMatch(e -> e.contains("request-finish")))
                .isTrue();
        assertThat(host.finished).isEqualTo(1);
        assertThat(host.cleared).containsExactly(1L);
        // writeJournal must run before clearProgress: the real host retires the session on
        // clear, and takeAccumulator then returns null — permanent "Building" in jk jobs.
        assertThat(host.teardownOrder).containsExactly("writeJournal", "clearProgress");
    }

    @Test
    void maintenance_kind_never_writes_a_timeline() {
        FakeHost host = new FakeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        env.submit(
                "{\"type\":\"cache-prune-request\",\"op\":\"clear\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.maintenance("cache", "jk-test-", (line, tok, w) -> JobOutcome.declined()),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        // A clean that leaves a fresh target/jk-profile.json behind un-cleans itself.
        assertThat(host.lastNoTimeline).isTrue();

        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.declined()),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        assertThat(host.lastNoTimeline).isFalse();
    }

    @Test
    void fire_and_forget_returns_jid_and_finishes_detached() throws Exception {
        FakeHost host = new FakeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        CountDownLatch ran = new CountDownLatch(1);
        long jid = env.submit(
                "{\"type\":\"lock-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("lock", "jk-test-", (line, tok, w) -> {
                    ran.countDown();
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        assertThat(jid).isPositive();
        assertThat(ran.await(30, TimeUnit.SECONDS)).isTrue();
        // Await the WHOLE teardown, not just the finish flag: the detached worker appends
        // teardownOrder entries after finished++ lands, and containsExactly iterating the live
        // synchronizedList mid-append flaked under parallel suite load (the "expected X to
        // contain exactly X" failure). Snapshot before asserting.
        long deadline = System.currentTimeMillis() + 30_000;
        while ((host.finished == 0 || host.teardownOrder.size() < 2) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(host.finished).isEqualTo(1);
        assertThat(List.copyOf(host.teardownOrder)).containsExactly("writeJournal", "clearProgress");
        assertThat(host.events.stream().anyMatch(e -> e.contains("request-finish")))
                .isTrue();
    }

    @Test
    void duplicate_detached_build_is_refused_with_typed_already_running(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String line = "{\"type\":\"build-request\",\"dir\":" + Jsonl.quote(dir.toString()) + "}";
        env.submit(
                line,
                JobRequest.workspace("build", "jk-test-", (l, tok, w) -> {
                    started.countDown();
                    try {
                        // Generous: the first build must still be in flight when the duplicate is
                        // submitted, and the main thread always releases it in its finally.
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> env.submit(
                            line,
                            JobRequest.workspace("build", "jk-test-", (l, tok, w) -> JobOutcome.declined()),
                            new JobTransport.FireAndForget()))
                    .isInstanceOf(JobEnvelope.AlreadyRunning.class);
        } finally {
            release.countDown();
        }
    }

    @Test
    void deadline_kill_records_the_reason_on_the_accumulator() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/p", null, "web");
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        new JobWatchdog(new JobLimits(0L, 1234L, 0L, 500L), () -> 1_000L, id -> host.accumulator)
                .enforceDeadline(7L, Session.CancelToken.live(), null, null);
        assertThat(host.accumulator.wasCancelled()).isTrue();
        assertThat(host.accumulator.cancelReason()).contains("1234ms");
    }

    /**
     * The wall deadline is the one cancel the engine raises on its own, and it is driven here with a
     * millisecond {@link JobLimits} — nothing in this test touches the environment, and nothing
     * waits longer than the job takes to be killed.
     */
    @Test
    void a_wall_deadline_cancels_a_running_job_and_names_the_deadline() throws Exception {
        FakeHost host = new FakeHost();
        host.clock = System::currentTimeMillis;
        host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "web");
        JobEnvelope env = new JobEnvelope(host, new JobLimits(0L, 50L, 100L, 500L));
        CountDownLatch release = new CountDownLatch(1);

        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    try {
                        // The deadline kill interrupts this wait long before it expires.
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());

        long deadline = System.currentTimeMillis() + 10_000;
        while (!host.journalWritten && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        release.countDown();
        assertThat(host.journalWritten).isTrue();
        assertThat(host.journalCancelled).isTrue();
        assertThat(host.accumulator.cancelReason()).contains("50ms wall deadline");
        assertThat(host.events)
                .as("request-finish carries the deadline as its cancel reason")
                .anyMatch(e -> e.contains("request-finish")
                        && e.contains("\"cancelled\":true")
                        && e.contains("wall deadline"));
    }

    /**
     * The row a Ctrl-C leaves behind. The CLI has exited {@code 130} since while the
     * journal wrote {@code 1} for the same run, and no cancelled row said who stopped it — a user
     * interrupt and a wall deadline both read as a bare {@code cancelled=true}. Asserted on the
     * persisted record, because the process exit was already right; the journal is what lied.
     */
    @Test
    void a_user_cancel_journals_the_interrupt_code_and_names_the_user() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/p", null, "cli");
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);

        env.live().beginUserCancel(11L, Session.CancelToken.live(), null, 0L, true);

        BuildRecord record = host.journalRecord();
        assertThat(record.cancelled()).isTrue();
        assertThat(record.exitCode()).isEqualTo(Exit.INTERRUPTED);
        assertThat(host.accumulator.cancelReason()).contains("Ctrl-C");
        assertThat(record.diagnostics())
                .as("the reason rides the record, so a job with no wire writer still says who")
                .anyMatch(d -> "cancelled".equals(d.code()) && d.message().contains("Ctrl-C"));
    }

    /**
     * Two cancels, one flag: both rows say {@code cancelled} and both carry the interrupt code, so
     * the only thing that can tell a user's Ctrl-C from a wall deadline is the reason.
     */
    @Test
    void a_wall_deadline_is_distinguishable_in_the_record_from_a_user_cancel() {
        FakeHost byUser = new FakeHost();
        byUser.accumulator = new BuildAccumulator("build", "/p", null, "cli");
        new JobEnvelope(byUser, JobLimits.DEFAULTS)
                .live()
                .beginUserCancel(1L, Session.CancelToken.live(), null, 0L, true);

        FakeHost byDeadline = new FakeHost();
        byDeadline.accumulator = new BuildAccumulator("build", "/p", null, "web");
        new JobWatchdog(new JobLimits(0L, 1234L, 0L, 500L), () -> 1_000L, id -> byDeadline.accumulator)
                .enforceDeadline(2L, Session.CancelToken.live(), null, null);

        assertThat(byUser.journalRecord().exitCode()).isEqualTo(Exit.INTERRUPTED);
        assertThat(byDeadline.journalRecord().exitCode()).isEqualTo(Exit.INTERRUPTED);
        assertThat(byUser.accumulator.cancelReason())
                .isNotEqualTo(byDeadline.accumulator.cancelReason())
                .doesNotContain("deadline");
        assertThat(byDeadline.accumulator.cancelReason()).contains("wall deadline");
    }

    /**
     * A client that vanished mid-job is a cancel, but it is not something the user asked for — the
     * reason must not claim a Ctrl-C nobody pressed.
     */
    @Test
    void a_client_that_disconnects_mid_job_is_not_recorded_as_a_deliberate_cancel() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/p", null, "cli");

        new JobEnvelope(host, JobLimits.DEFAULTS)
                .live()
                .beginUserCancel(3L, Session.CancelToken.live(), null, 0L, false);

        assertThat(host.accumulator.wasCancelled()).isTrue();
        assertThat(host.accumulator.cancelReason()).contains("disconnected").doesNotContain("Ctrl-C");
    }

    @Test
    void cancelled_terminal_shape_matches_stream() {
        assertThat(EngineProtocol.typeOf(LiveJobRegistry.cancelledTerminalLine(true, "/ws")))
                .isEqualTo(EngineProtocol.WORKSPACE_FINISH);
        assertThat(EngineProtocol.typeOf(LiveJobRegistry.cancelledTerminalLine(false, "/p")))
                .isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
    }

    /**
     * The end-of-request EOF is not a cancel. The engine half-closes the client's read to wake its
     * own connection thread the moment the runner is done, and that EOF arrives on the same path a
     * real hang-up does — so a body that has already ruled success must not be re-labelled.
     */
    @Test
    void a_body_that_ruled_success_is_not_relabelled_cancelled_by_the_end_of_request_eof() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("cache", "/tmp/job-env", null, "cli");
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();

        env.submit(
                "{\"type\":\"cache-prune-request\",\"op\":\"prune\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.maintenance("cache", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));

        assertThat(host.journalWritten).isTrue();
        BuildRecord record = host.journalRecord();
        assertThat(record.cancelled()).isFalse();
        assertThat(record.success()).isTrue();
        assertThat(record.exitCode()).isZero();
    }

    /**
     * A runner that dies without ruling leaves no rows behind, and no rows is not evidence of
     * success — the journal records a failure the user can act on rather than a green run.
     *
     * <p>Detached on purpose: a socket job's dead runner is caught by the cancel stamps, because
     * the connection thread reads EOF. A dashboard/MCP job has no reader and no cancel, so only
     * the envelope's catch (and, beneath it, the no-verdict derivation) holds this row red.
     */
    @Test
    void a_detached_runner_that_dies_without_ruling_journals_a_failure() throws Exception {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "cli");
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);

        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    throw new IllegalStateException("runner died");
                }),
                new JobTransport.FireAndForget());

        long deadline = System.currentTimeMillis() + 30_000;
        while (!host.journalWritten && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(host.journalWritten).isTrue();
        assertThat(host.journalCancelled)
                .as("no reader, no cancel — nothing but the derivation is holding this row")
                .isFalse();
        BuildRecord record = host.journalRecord();
        assertThat(record.success()).isFalse();
        assertThat(record.exitCode()).isNotZero();
    }

    /**
     * The run-notice sink joins the request before the body runs and leaves in the finally — a
     * leaked sink would attribute a later run's notices to this request's stream.
     */
    @Test
    void run_notices_ride_the_wire_during_the_run_and_stderr_after_it() {
        RunNotices.clear();
        FakeHost host = new FakeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        var errDuring = new ByteArrayOutputStream();
        var originalErr = System.err;
        System.setErr(new PrintStream(errDuring, true, StandardCharsets.UTF_8));
        try {
            env.submit(
                    "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                    JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                        // The way a real caller stands: a Session derived inside the request
                        // adopts the run's open ledger, and RunNotices claims against it.
                        SessionContext.runWhere(
                                Session.defaults(),
                                () -> RunNotices.warnOnce("test-notice", () -> "a run-scoped notice"));
                        return JobOutcome.declined();
                    }),
                    new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        } finally {
            System.setErr(originalErr);
        }
        assertThat(out.toString()).contains("\"code\":\"notice\"").contains("a run-scoped notice");
        assertThat(errDuring.toString(StandardCharsets.UTF_8)).doesNotContain("a run-scoped notice");

        // After the finally the sink is gone: the same ledger's next note is stderr-only.
        var errAfter = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errAfter, true, StandardCharsets.UTF_8));
        try {
            SessionContext.runWhere(
                    Session.defaults().withIo(host.io),
                    () -> RunNotices.warnOnce("late-notice", () -> "a note after the run"));
        } finally {
            System.setErr(originalErr);
        }
        assertThat(errAfter.toString(StandardCharsets.UTF_8)).contains("a note after the run");
        assertThat(out.toString()).doesNotContain("a note after the run");
        RunNotices.clear();
    }

    /**
     * The sharper failure: a body that throws <em>after</em> recording clean rows. Without the
     * envelope's catch the no-verdict derivation never fires — one success row and no failure row
     * derives green — so the escaped throw must be stamped as a failure, with the exception named
     * on the record.
     */
    @Test
    void a_runner_that_throws_after_clean_rows_journals_a_failure_not_green() throws Exception {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "cli");
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);

        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    host.accumulator.addModule(new ModuleOutcome("g:a", Path.of("/w/a"), true, 0, 10, true));
                    throw new IllegalStateException("threw past its own handler");
                }),
                new JobTransport.FireAndForget());

        long deadline = System.currentTimeMillis() + 30_000;
        while (!host.journalWritten && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(host.journalWritten).isTrue();
        BuildRecord record = host.journalRecord();
        assertThat(record.success()).isFalse();
        assertThat(record.exitCode()).isNotZero();
        assertThat(record.diagnostics())
                .as("the record names the escape, not just a bare failure bit")
                .anyMatch(d -> "escaped-throw".equals(d.code())
                        && "java.lang.IllegalStateException".equals(d.exceptionClass()));
        assertThat(host.events.stream().anyMatch(e -> e.contains("request-finish") && e.contains("\"success\":false")))
                .isTrue();
    }

    /**
     * A body killed by an {@link Error} is invisible to the {@code catch (Exception)} arms verbs
     * wrap themselves in, so the envelope owns both halves of making it observable: the throwable
     * and its stack reach the engine log, and a terminal reaches the client. Without the terminal
     * the stream just ends, and a bare EOF is all the CLI has to report.
     */
    @Test
    void a_body_killed_by_an_error_is_logged_and_settles_a_terminal_for_the_client() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "cli");
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();

        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    throw new NoClassDefFoundError("cc/example/Missing");
                }),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));

        assertThat(host.logs)
                .as("the log carries the throwable and its stack, not just that something failed")
                .anyMatch(l -> l.contains("java.lang.NoClassDefFoundError")
                        && l.contains("cc/example/Missing")
                        && l.contains("at cc.jumpkick.engine.jobs.JobEnvelopeTest"));
        // request-failed is the one terminal both the single-plan and the workspace decoder end on.
        assertThat(out.toString().lines().map(EngineProtocol::typeOf))
                .as("the client's stream ends on a terminal, never on a bare EOF")
                .contains(EngineProtocol.ERROR);
        assertThat(out.toString()).contains("NoClassDefFoundError");
    }

    /**
     * The contract the catch must not break: {@link JobOutcome.Declined} with clean rows is the
     * documented derive-from-facts path and still reads green.
     */
    @Test
    void a_runner_that_declines_with_clean_rows_still_derives_green() throws Exception {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "cli");
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);

        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    host.accumulator.addModule(new ModuleOutcome("g:a", Path.of("/w/a"), true, 0, 10, true));
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());

        long deadline = System.currentTimeMillis() + 30_000;
        while (!host.journalWritten && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(host.journalWritten).isTrue();
        BuildRecord record = host.journalRecord();
        assertThat(record.success()).isTrue();
        assertThat(record.exitCode()).isZero();
    }

    /**
     * The order one successful plan job's observable effects leave the envelope in. The plan slot is
     * released before request-finish so the event carries the post-finish plan count; the journal is
     * written before clearProgress retires the session; the idle boundary comes last.
     */
    @Test
    void a_successful_job_emits_its_effects_in_one_order_with_the_slot_released_first() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "cli");
        // Detached: a socket whose reader is already at EOF reads as a client disconnect, which
        // would race the body's own verdict. The wire terminal is pinned by the journal test below.
        new JobEnvelope(host, JobLimits.DEFAULTS)
                .submit(
                        "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                        JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                        new JobTransport.FireAndForget());
        awaitTail(host);

        assertThat(host.sequence)
                .containsExactly(
                        "request-start",
                        "progress-pinned:100.0",
                        "plan-slot-released",
                        "request-finish",
                        "writeJournal",
                        "clearProgress",
                        "idle-boundary");
        String finish = requestFinish(host);
        assertThat(keysOf(finish))
                .containsExactly(
                        "schema",
                        "type",
                        "jid",
                        "kind",
                        "dir",
                        "projectId",
                        "success",
                        "cancelled",
                        "millis",
                        "activeBuildPlans");
        assertThat(Jsonl.intValue(finish, "activeBuildPlans", -1))
                .as("the count after the release, so the dashboard settles in the same frame")
                .isZero();
        assertThat(Jsonl.intValue(finish, "schema", -1)).isEqualTo(1);
    }

    /** A failed and a thrown body share the success path's order and never pin the bar at 100%. */
    @Test
    void a_failed_and_a_thrown_job_emit_the_same_order_without_the_progress_pin() {
        for (JobBody body : List.<JobBody>of((line, tok, w) -> JobOutcome.failed(Exit.SOFTWARE), (line, tok, w) -> {
            throw new IllegalStateException("boom");
        })) {
            FakeHost host = new FakeHost();
            host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "cli");
            new JobEnvelope(host, JobLimits.DEFAULTS)
                    .submit(
                            "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                            JobRequest.plan("build", "jk-test-", body),
                            new JobTransport.FireAndForget());
            awaitTail(host);
            assertThat(host.sequence)
                    .containsExactly(
                            "request-start",
                            "plan-slot-released",
                            "request-finish",
                            "writeJournal",
                            "clearProgress",
                            "idle-boundary");
            String finish = requestFinish(host);
            assertThat(Jsonl.bool(finish, "success", true)).isFalse();
            assertThat(Jsonl.has(finish, "cancelReason")).isFalse();
        }
    }

    /** A cancelled job adds exactly one key, cancelReason, and still ends on the same tail. */
    @Test
    void a_cancelled_job_adds_cancel_reason_and_nothing_else() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "cli");
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    env.live().beginUserCancel(1L, tok, null, 0L, true);
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        awaitTail(host);

        String finish = requestFinish(host);
        assertThat(Jsonl.bool(finish, "cancelled", false)).isTrue();
        assertThat(keysOf(finish)).endsWith("activeBuildPlans", "cancelReason");
        assertThat(Jsonl.str(finish, "cancelReason")).contains("cancelled by the user");
        assertThat(host.sequence)
                .endsWith("plan-slot-released", "request-finish", "writeJournal", "clearProgress", "idle-boundary");
    }

    /** job-finish is written in a finally, so a throwing journal cannot strand the client on EOF. */
    @Test
    void a_throwing_journal_still_delivers_job_finish() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "cli");
        host.journalThrows = true;
        StringWriter out = new StringWriter();
        assertThatThrownBy(() -> new JobEnvelope(host, JobLimits.DEFAULTS)
                        .submit(
                                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                                JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                                new JobTransport.SocketWatch(
                                        new BufferedReader(new StringReader("")), new BufferedWriter(out))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("journal disk full");
        assertThat(out.toString().lines().map(EngineProtocol::typeOf))
                .as("the client reads its terminal, not EOF")
                .contains(EngineProtocol.JOB_FINISH);
        assertThat(host.sequence).containsSubsequence("request-finish", "writeJournal");
    }

    /** The detached tail runs on its own thread; the idle boundary is its last effect. */
    private static void awaitTail(FakeHost host) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!host.sequence.contains("idle-boundary") && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String requestFinish(FakeHost host) {
        return host.events.stream()
                .filter(e -> e.startsWith("request-finish:"))
                .map(e -> e.substring("request-finish:".length()))
                .findFirst()
                .orElseThrow();
    }

    /** Top-level keys of a flat JSON object, in document order. */
    private static List<String> keysOf(String json) {
        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile("\"(\\w+)\":").matcher(json);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    private static final class FakeHost implements JobEnvelope.Host {
        boolean tryStart = true;
        volatile int abandoned;
        volatile int finished;
        volatile BuildAccumulator accumulator;
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<Long> cleared = Collections.synchronizedList(new ArrayList<>());
        final List<String> teardownOrder = Collections.synchronizedList(new ArrayList<>());
        final InFlightBuilds inFlight = new InFlightBuilds();
        final AtomicLong ids = new AtomicLong();
        final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

        @Override
        public boolean tryStartBuildPlan() {
            if (tryStart) activePlans++;
            return tryStart;
        }

        @Override
        public void abandonBuildPlanSlot() {
            abandoned++;
        }

        @Override
        public void noteBuildPlanFinished() {
            finished++;
            activePlans--;
            sequence.add("plan-slot-released");
        }

        /** Every host call the envelope makes that the wire or the dashboard can observe, in order. */
        final List<String> sequence = Collections.synchronizedList(new ArrayList<>());

        volatile int activePlans;

        @Override
        public boolean draining() {
            return !tryStart;
        }

        @Override
        public long nextRequestId() {
            return ids.incrementAndGet();
        }

        /** Frozen by default; a deadline test swaps in the wall clock. */
        LongSupplier clock = () -> 1_000L;

        @Override
        public long nowMillis() {
            return clock.getAsLong();
        }

        @Override
        public void putMode(long id, ProgressBarMode mode) {}

        @Override
        public void publishRequestStart(long id, String kind, String dir, long buildNumber) {
            sequence.add("request-start");
        }

        Boolean lastNoTimeline;

        @Override
        public void registerAccumulator(
                long id,
                String kind,
                String dir,
                String trigger,
                boolean noTimeline,
                boolean rebuild,
                long buildNumber,
                String journalId) {
            lastNoTimeline = noTimeline;
        }

        @Override
        public ReentrantReadWriteLock cacheGate() {
            return gate;
        }

        @Override
        public void bindEventRequestId(long id) {}

        @Override
        public void unbindEventRequestId() {}

        final IoLedger io = new IoLedger();

        @Override
        public IoLedger runIo(long id) {
            return io;
        }

        @Override
        public InFlightBuilds inFlight() {
            return inFlight;
        }

        @Override
        public BuildAccumulator accumulatorOf(long id) {
            return accumulator;
        }

        @Override
        public void putLastProgress(long id, double percent) {
            sequence.add("progress-pinned:" + percent);
        }

        @Override
        public int activeBuildPlans() {
            return activePlans;
        }

        @Override
        public JsonOut withProgress(JsonOut payload, long id) {
            return payload;
        }

        @Override
        public JsonOut withIo(JsonOut payload, long id) {
            return payload;
        }

        @Override
        public void publishEvent(String type, JsonOut payload) {
            events.add(type + ":" + payload);
            sequence.add(type);
        }

        @Override
        public void clearProgress(long id) {
            teardownOrder.add("clearProgress");
            sequence.add("clearProgress");
            cleared.add(id);
        }

        volatile boolean journalWritten;
        volatile boolean journalCancelled;
        volatile long journalMillis;

        volatile boolean journalThrows;

        @Override
        public void writeJournal(long id, boolean cancelled, long millis, BufferedWriter writer) {
            teardownOrder.add("writeJournal");
            sequence.add("writeJournal");
            journalCancelled = cancelled;
            journalMillis = millis;
            journalWritten = true;
            if (journalThrows) throw new IllegalStateException("journal disk full");
        }

        /** The row JournalWriter would persist for this run, built the same way it builds it. */
        BuildRecord journalRecord() {
            return accumulator.toRecord(
                    2_000L, journalCancelled || accumulator.wasCancelled(), journalMillis, version(), null);
        }

        @Override
        public void maybeIdleBoundary() {
            sequence.add("idle-boundary");
        }

        @Override
        public void maybeIdleGc() {
            sequence.add("idle-gc");
        }

        final List<String> logs = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void log(String message) {
            logs.add(message);
        }

        @Override
        public String version() {
            return "0.0.0-test";
        }

        @Override
        public JkHistoryConfig historyConfig() {
            return new JkHistoryConfig(false, 0, 0); // never write real journal stubs from a unit test
        }

        @Override
        public BuildJournal journal() {
            return BuildJournal.current();
        }

        @Override
        public String coordOf(String dir) {
            return "test:job";
        }
    }
}
