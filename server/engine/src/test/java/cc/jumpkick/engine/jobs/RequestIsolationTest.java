// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.secret.RequestKeychain;
import cc.jumpkick.secret.SecretVault;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.test.RunResults;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two jobs running at once each see only their own request: its id, results sink, shell, keychain,
 * ledger and actor, on the runner thread, on every shared pool and on the threads it starts.
 */
class RequestIsolationTest {

    private static final String PROBE = "JK_ISOLATION_PROBE";
    private static final SecretVault.Pointer POINTER = new SecretVault.Pointer("fake", "repo/isolation");

    /** What one place in a job saw of its request. */
    private record Seen(
            String where,
            @Nullable Long requestId,
            @Nullable RunResults results,
            @Nullable String shell,
            @Nullable String secret,
            String actor,
            IoLedger io) {}

    @Test
    void two_concurrent_jobs_each_see_only_their_own_request(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        host.perRequest = true;
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        CyclicBarrier bothRunning = new CyclicBarrier(2);
        Map<String, List<Seen>> seen = new ConcurrentHashMap<>();
        Map<String, Long> ids = new ConcurrentHashMap<>();
        Queue<Throwable> failed = new ConcurrentLinkedQueue<>();

        List<Thread> clients = new ArrayList<>();
        for (String name : List.of("alpha", "beta")) {
            Path checkout = Files.createDirectories(dir.resolve(name));
            String line = ProtoSession.withKeychain(
                    ProtoSession.withShell(FakeEnvelopeHost.buildRequest(checkout), Map.of(PROBE, name)),
                    Map.of(POINTER.text(), "secret-" + name),
                    Map.of());
            ChangeActor actor = new ChangeActor(ChangeActor.AGENT, "agent " + name, Map.of());
            clients.add(Thread.ofPlatform().name("client-" + name).start(() -> {
                try {
                    long id = Request.actingAs(
                            actor,
                            () -> ConnectedClient.submit(
                                    env,
                                    line,
                                    JobRequest.plan("build", "jk-isolation-", (l, tok, w) -> {
                                        try {
                                            bothRunning.await(30, TimeUnit.SECONDS);
                                            seen.put(name, sampleEverywhere());
                                        } catch (Exception e) {
                                            failed.add(e);
                                        }
                                        return JobOutcome.ok();
                                    }),
                                    new StringWriter()));
                    ids.put(name, id);
                } catch (Throwable t) {
                    failed.add(t);
                }
            }));
        }
        for (Thread client : clients) client.join(TimeUnit.SECONDS.toMillis(60));
        host.awaitSettled();
        assertThat(failed).isEmpty();
        assertThat(seen).containsOnlyKeys("alpha", "beta");

        List<String> wrong = new ArrayList<>();
        for (String name : List.of("alpha", "beta")) {
            long id = Objects.requireNonNull(ids.get(name));
            RunResults sink = host.accumulatorOf(id).results();
            IoLedger io = host.runIo(id);
            for (Seen s : Objects.requireNonNull(seen.get(name))) {
                expect(wrong, name, s, "request id", s.requestId(), id);
                expect(wrong, name, s, "results sink", s.results(), sink);
                expect(wrong, name, s, "shell", s.shell(), name);
                expect(wrong, name, s, "keychain", s.secret(), "secret-" + name);
                expect(wrong, name, s, "actor", s.actor(), "agent " + name);
                expect(wrong, name, s, "ledger", s.io(), io);
            }
        }
        assertThat(wrong).isEmpty();
    }

    private static void expect(List<String> wrong, String job, Seen s, String what, @Nullable Object got, Object want) {
        boolean same = got instanceof String || got instanceof Long ? Objects.equals(got, want) : got == want;
        if (!same) wrong.add(job + " on " + s.where() + ": " + what + " was " + got + ", not " + want);
    }

    /** The request as seen on the calling thread, on each shared pool and on each started thread. */
    private static List<Seen> sampleEverywhere() throws Exception {
        List<Seen> out = new ArrayList<>();
        out.add(sample("the runner"));
        out.add(JkThreads.cpu().submit(task("the cpu pool")).get(30, TimeUnit.SECONDS));
        out.add(JkThreads.io().submit(task("the io pool")).get(30, TimeUnit.SECONDS));
        out.add(JkThreads.platform().submit(task("the platform pool")).get(30, TimeUnit.SECONDS));
        out.add(started("a started virtual thread", r -> SessionContext.startVirtual("jk-isolation-v", r)));
        out.add(started("a started platform thread", r -> SessionContext.startPlatform("jk-isolation-p", r)));
        return out;
    }

    private static Callable<Seen> task(String where) {
        return () -> sample(where);
    }

    private static Seen started(String where, Function<Runnable, Thread> start) throws InterruptedException {
        AtomicReference<Seen> got = new AtomicReference<>();
        start.apply(() -> got.set(sample(where))).join(TimeUnit.SECONDS.toMillis(30));
        return Objects.requireNonNull(got.get(), where);
    }

    private static Seen sample(String where) {
        RequestShell shell = RequestShell.current();
        RequestKeychain keychain = RequestKeychain.current();
        return new Seen(
                where,
                JobWorkers.currentRequestId(),
                RunResults.ambient(),
                shell == null ? null : shell.get(PROBE),
                keychain == null ? null : keychain.get(POINTER),
                ChangeActor.current().label(),
                Session.defaults().io());
    }
}
