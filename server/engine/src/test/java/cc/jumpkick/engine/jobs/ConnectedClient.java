// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * A socket client for envelope tests that stays connected until the job has ended, as the CLI does
 * until it reads {@code job-finish}. A reader already at EOF is a client that hung up: the envelope
 * cancels a job whose body has not finished, so a body the scheduler starts late never runs.
 */
public final class ConnectedClient {

    private ConnectedClient() {}

    public static long submit(JobEnvelope env, String requestLine, JobRequest job, Writer out) {
        try (PipedWriter clientEnd = new PipedWriter()) {
            BufferedReader reader = new BufferedReader(new PipedReader(clientEnd));
            return env.submit(requestLine, job, new JobTransport.SocketWatch(reader, new BufferedWriter(out)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
