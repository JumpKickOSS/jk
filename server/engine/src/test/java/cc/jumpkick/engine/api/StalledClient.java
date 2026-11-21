// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A client that reads nothing until told to, on a stream that is never dropped and has no room to
 * spill, for tests outside this package that need a stream whose in-memory queue is past its bound.
 */
public final class StalledClient implements AutoCloseable {

    private final Pipe pipe;
    private final BufferedWriter writer;
    private final List<String> read = Collections.synchronizedList(new ArrayList<>());

    private StalledClient(Pipe pipe, BufferedWriter writer) {
        this.pipe = pipe;
        this.writer = writer;
    }

    /** A stalled client whose stream would spill under {@code spillDir}, with a spill cap of zero. */
    public static StalledClient open(Path spillDir) throws IOException {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        WireWriter.bind(writer, 0, spillDir, 0, bytes -> true);
        return new StalledClient(pipe, writer);
    }

    public BufferedWriter writer() {
        return writer;
    }

    /** Queue unpaced lines until the stream holds more than its in-memory bound. */
    public void fillPastBound() {
        String line = "{\"type\":\"log\",\"text\":\"" + "x".repeat(64 * 1024) + "\"}";
        while (WireWriter.queuedBytes(writer) <= WireWriter.MAX_QUEUED_BYTES) WireWriter.sendNoWait(writer, line);
    }

    /** Start reading: every line that reaches the client from now on lands in {@link #read}. */
    public void startReading() {
        Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(Channels.newInputStream(pipe.source()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) read.add(line);
            } catch (IOException e) {
                // closed under the reader: the test is over
            }
        });
    }

    /** The lines read so far. */
    public List<String> read() {
        synchronized (read) {
            return List.copyOf(read);
        }
    }

    @Override
    public void close() throws IOException {
        pipe.sink().close();
        pipe.source().close();
    }
}
