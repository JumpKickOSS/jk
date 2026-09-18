// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A subprocess used by {@link KtsSessionCarrierTest} to show what the host's stdout reader costs
 * the JVM around it. Two modes:
 *
 * <ul>
 *   <li>{@code silent} — sleep for a minute writing nothing, the way a resident host sits between
 *       scripts. Stands in for the host.
 *   <li>{@code probe <java> <classpath>} — start a {@code silent} child, attach a {@link KtsSession}
 *       to it, then start one virtual thread and report whether it ran: {@code vthread-ran} or
 *       {@code vthread-starved}. Run under one carrier with compensation denied ({@code
 *       -XX:ActiveProcessorCount=1 -Djdk.virtualThreadScheduler.maxPoolSize=1}), a reader that
 *       holds a carrier while it waits on the pipe is the only carrier there is.
 * </ul>
 */
public final class KtsReaderCarrierProbe {

    private KtsReaderCarrierProbe() {}

    public static void main(String[] args) throws Exception {
        if (args[0].equals("silent")) {
            Thread.sleep(60_000);
            return;
        }
        Process host = new ProcessBuilder(args[1], "-cp", args[2], KtsReaderCarrierProbe.class.getName(), "silent")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            KtsSession.attachForTests(host);
            // The reader is running by now if it ever will: its first read parks on the pipe.
            Thread.sleep(500);
            CountDownLatch ran = new CountDownLatch(1);
            Thread.ofVirtual().start(ran::countDown);
            System.out.println(ran.await(5, TimeUnit.SECONDS) ? "vthread-ran" : "vthread-starved");
            System.out.flush();
        } finally {
            host.destroyForcibly();
        }
        System.exit(0);
    }
}
