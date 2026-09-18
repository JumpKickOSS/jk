// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.maven.spy;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

/**
 * How long each mojo ran: {@code MojoStarted} notes the clock, and the mojo's terminal event reads
 * the elapsed milliseconds. Maven's {@link ExecutionEvent} carries no timing of its own and its
 * build summary only times whole modules, so the spy keeps this clock itself. A terminal event
 * with no recorded start reads 0.
 */
final class MojoTimer {

    private final LongSupplier nanos;
    private final Map<String, Long> started = new ConcurrentHashMap<>();

    MojoTimer(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /** The timer on the JVM's monotonic clock, the one the spy runs under Maven. */
    static MojoTimer system() {
        return new MojoTimer(MojoTimer::systemNanos);
    }

    private static long systemNanos() {
        return System.nanoTime();
    }

    /**
     * Observe {@code e}: a mojo start is remembered and reads 0; a mojo's success, failure or skip
     * reads the milliseconds since its start; every other event reads 0.
     */
    long observe(ExecutionEvent e) {
        switch (e.getType()) {
            case MojoStarted -> {
                started.put(key(e), nanos.getAsLong());
                return 0;
            }
            case MojoSucceeded, MojoFailed, MojoSkipped -> {
                Long start = started.remove(key(e));
                return start == null ? 0 : Math.max(0, (nanos.getAsLong() - start) / 1_000_000L);
            }
            default -> {
                return 0;
            }
        }
    }

    /** One mojo execution within one module: its basedir, execution id and goal. */
    private static String key(ExecutionEvent e) {
        MavenProject p = e.getProject();
        File basedir = p == null ? null : p.getBasedir();
        MojoExecution mojo = e.getMojoExecution();
        return (basedir == null ? "" : basedir.getAbsolutePath())
                + '\t'
                + (mojo == null ? "" : mojo.getExecutionId() + '\t' + mojo.getArtifactId() + ':' + mojo.getGoal());
    }
}
