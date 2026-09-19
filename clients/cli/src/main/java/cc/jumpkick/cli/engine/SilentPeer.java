// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.time.Clock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a client does about an engine that accepts its connection and answers no handshake.
 *
 * <p>An unanswered handshake is not proof of a wedged engine. A coordinator mid-build has its
 * CPU pool parked on wire writes and its virtual-thread carriers pinned by file I/O, and it
 * answers seconds or minutes later; displacing it kills every job it runs for every other
 * terminal. So the holder the pid file names is read for the life its process shows without a
 * protocol reply — its age, its worker children, whether its CPU time advances between readings —
 * and a peer that shows life is waited out for longer the more it has in hand, then left alone
 * with a refusal. Only a holder that shows none is displaced.
 */
public final class SilentPeer {

    private SilentPeer() {}

    /** CPU a busy engine burns between two readings that an idle one does not: below it, no life. */
    static final Duration CPU_STEP = Duration.ofMillis(50);

    /**
     * How long a silent peer is waited out. An engine younger than {@code startup} is loading and
     * is never displaced; a busy one earns {@code floor} plus {@code perWorker} for each worker
     * process it runs, up to {@code ceiling}.
     */
    public record Grace(Duration startup, Duration floor, Duration perWorker, Duration ceiling) {

        public static final Grace DEFAULT =
                new Grace(Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(15), Duration.ofMinutes(3));

        /** What {@code life} earns: the floor plus a share per worker, capped. */
        Duration patienceFor(Life life) {
            Duration earned = floor.plus(perWorker.multipliedBy(Math.max(0, life.workers())));
            return earned.compareTo(ceiling) > 0 ? ceiling : earned;
        }
    }

    /**
     * One reading of the holder: its age, the worker processes it has forked, and its cumulative
     * CPU time. An age or CPU figure the OS does not report reads as old and zero, so an unknown
     * never protects a peer on its own.
     */
    public record Life(long pid, Duration age, int workers, Duration cpu) {

        /** The live process {@code pid}, read at {@code clock}'s now; empty once it is gone. */
        public static Optional<Life> of(long pid, Clock clock) {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(h -> {
                ProcessHandle.Info info = h.info();
                Duration age = info.startInstant()
                        .map(start -> Duration.between(start, clock.instant()))
                        .orElse(Duration.ofDays(1));
                // Only JVM children count: Windows attaches conhost.exe under java.exe, which is
                // not a worker and must not protect a silent idle holder from displacement.
                int workers = (int) h.children().filter(Life::isJvm).count();
                Duration cpu = info.totalCpuDuration().orElse(Duration.ZERO);
                return new Life(pid, age, workers, cpu);
            });
        }

        private static boolean isJvm(ProcessHandle child) {
            return child.info()
                    .command()
                    .map(cmd -> {
                        String name = Path.of(cmd).getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.equals("java") || name.equals("java.exe");
                    })
                    .orElse(false);
        }

        public boolean youngerThan(Duration startup) {
            return age.compareTo(startup) < 0;
        }

        /** True when this reading's CPU time is a busy engine's step past {@code before}'s. */
        boolean cpuAdvancedSince(@Nullable Life before) {
            return before != null && cpu.minus(before.cpu).compareTo(CPU_STEP) >= 0;
        }

        /** {@code "up 3m 12s, 6 worker processes"}. */
        public String describe() {
            return "up " + human(age) + ", " + workers + (workers == 1 ? " worker process" : " worker processes");
        }
    }

    /** What to do after a probe round left the peer silent. */
    enum Verdict {
        /** The peer shows life and its patience is not spent: back off and probe again. */
        KEEP_WAITING,
        /** The peer shows life and its patience is spent: refuse, naming it; never kill it. */
        LEAVE_ALONE,
        /** Two readings show no life: displace it as before. */
        DISPLACE
    }

    /**
     * Judge the holder after {@code waited} of silence. Young, running workers, or burning CPU
     * since {@code before} (the previous reading; null on the first round) is a busy engine; a
     * first reading cannot show a CPU step, so no life is only ever called on the second.
     */
    static Verdict judge(@Nullable Life before, Life now, Duration waited, Grace grace) {
        boolean busy = now.youngerThan(grace.startup()) || now.workers() > 0 || now.cpuAdvancedSince(before);
        if (!busy) return before == null ? Verdict.KEEP_WAITING : Verdict.DISPLACE;
        return waited.compareTo(grace.patienceFor(now)) < 0 ? Verdict.KEEP_WAITING : Verdict.LEAVE_ALONE;
    }

    /** The refusal: who the peer is, what it holds, how long it was given, and the operator's outs. */
    static String refusal(Life life, Duration waited) {
        return "the build engine (pid " + life.pid() + ") is alive and busy — " + life.describe()
                + " — but has not answered a handshake in " + human(waited)
                + "; it is not displaced, since that would kill the jobs it runs for other terminals."
                + " Retry in a moment; `jk engine status` shows its jobs, and `jk engine stop --now` stops it"
                + " regardless";
    }

    /** {@code 45s}, {@code 3m 12s}, {@code 2h 05m}. */
    static String human(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + String.format("%02ds", s % 60);
        return (s / 3600) + "h " + String.format("%02dm", (s % 3600) / 60);
    }
}
