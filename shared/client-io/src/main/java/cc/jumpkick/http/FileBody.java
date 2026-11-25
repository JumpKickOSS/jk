// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * A request body streamed from a file that is open only while it is being read: the file is closed
 * when its last byte is sent, when the read fails and when the request cancels the body. {@link
 * HttpRequest.BodyPublishers#ofFile} closes its file only at end of file, so a request that fails
 * before then holds it open, and Windows refuses to delete or overwrite a file another handle holds.
 * Each subscription, including retries, starts at the same file offset.
 */
public final class FileBody implements HttpRequest.BodyPublisher {

    private static final int CHUNK = 64 * 1024;

    private final Path file;
    private final long offset;
    private final long length;

    private FileBody(Path file, long offset, long length) {
        this.file = file;
        this.offset = offset;
        this.length = length;
    }

    /** A body of {@code file}'s bytes, its {@code Content-Length} the file's size now. */
    public static FileBody of(Path file) throws IOException {
        return range(file, 0, Files.size(file));
    }

    /** A fixed-length region of a file, streamed from {@code offset} on each subscription. */
    public static FileBody range(Path file, long offset, long length) {
        return new FileBody(file, offset, length);
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        Reading reading = new Reading(subscriber);
        subscriber.onSubscribe(reading);
        reading.drain();
    }

    /**
     * One pass over the file. Only the thread that wins {@link #drain} touches the channel, so a
     * cancel from another thread closes it through the same loop that reads it.
     */
    private final class Reading implements Flow.Subscription {

        private final Flow.Subscriber<? super ByteBuffer> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicInteger pending = new AtomicInteger();
        private volatile boolean cancelled;
        private volatile @Nullable IllegalArgumentException badRequest;
        private @Nullable FileChannel channel;
        private long sent;
        private boolean finished;

        Reading(Flow.Subscriber<? super ByteBuffer> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                badRequest = new IllegalArgumentException("non-positive request: " + n);
            } else {
                demand.getAndAccumulate(n, (have, more) -> have + more < 0 ? Long.MAX_VALUE : have + more);
            }
            drain();
        }

        @Override
        public void cancel() {
            cancelled = true;
            drain();
        }

        void drain() {
            if (pending.getAndIncrement() != 0) return;
            int missed = 1;
            do {
                step();
                missed = pending.addAndGet(-missed);
            } while (missed != 0);
        }

        private void step() {
            if (finished) return;
            if (cancelled) {
                finish();
                return;
            }
            IllegalArgumentException bad = badRequest;
            if (bad != null) {
                finish();
                subscriber.onError(bad);
                return;
            }
            try {
                while (!cancelled && !finished && (sent == length || demand.get() > 0)) {
                    if (sent == length) {
                        finish();
                        subscriber.onComplete();
                        return;
                    }
                    if (channel == null) {
                        channel = FileChannel.open(file, StandardOpenOption.READ);
                        channel.position(offset);
                    }
                    ByteBuffer chunk = ByteBuffer.allocate((int) Math.min(CHUNK, length - sent));
                    if (channel.read(chunk) < 0) {
                        throw new IOException(file + " shrank while it was sent: " + sent + " of " + length + " bytes");
                    }
                    sent += chunk.flip().remaining();
                    demand.decrementAndGet();
                    subscriber.onNext(chunk);
                }
                if (cancelled) finish();
            } catch (IOException e) {
                finish();
                subscriber.onError(e);
            } catch (RuntimeException e) {
                finish();
                throw e;
            }
        }

        private void finish() {
            finished = true;
            FileChannel open = channel;
            channel = null;
            if (open == null) return;
            try {
                open.close();
            } catch (IOException ignored) {
                // Closing a file opened only for reading loses nothing.
            }
        }
    }
}
