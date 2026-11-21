// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static cc.jumpkick.http.HttpPutFileReleaseTest.assertNotOpen;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FileBody} sends the file's bytes and leaves it closed however its subscriber ends. */
class FileBodyTest {

    @Test
    void a_full_read_sends_every_byte_then_completes_with_the_file_closed(@TempDir Path tmp) throws Exception {
        byte[] bytes = new byte[200_000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        Path file = Files.write(tmp.resolve("blob"), bytes);
        FileBody body = FileBody.of(file);
        Recorder recorder = new Recorder(Long.MAX_VALUE);

        body.subscribe(recorder);

        assertThat(body.contentLength()).isEqualTo(bytes.length);
        assertThat(recorder.received.toByteArray()).isEqualTo(bytes);
        assertThat(recorder.completed).isTrue();
        assertNotOpen(file);
    }

    @Test
    void a_cancel_part_way_closes_the_file(@TempDir Path tmp) throws Exception {
        Path file = Files.write(tmp.resolve("blob"), new byte[1024 * 1024]);
        Recorder recorder = new Recorder(1);

        FileBody.of(file).subscribe(recorder);
        assertThat(recorder.received.size()).isPositive().isLessThan(1024 * 1024);
        Objects.requireNonNull(recorder.subscription).cancel();

        assertThat(recorder.completed).isFalse();
        assertNotOpen(file);
    }

    @Test
    void an_empty_file_completes_with_nothing_sent(@TempDir Path tmp) throws Exception {
        Path file = Files.write(tmp.resolve("empty"), new byte[0]);
        Recorder recorder = new Recorder(1);

        FileBody.of(file).subscribe(recorder);

        assertThat(recorder.received.size()).isZero();
        assertThat(recorder.completed).isTrue();
        assertNotOpen(file);
    }

    /** Asks for {@code ask} chunks up front, collects them, and asks for nothing more. */
    private static final class Recorder implements Flow.Subscriber<ByteBuffer> {

        private final long ask;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private Flow.@Nullable Subscription subscription;
        private boolean completed;

        Recorder(long ask) {
            this.ask = ask;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(ask);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            received.writeBytes(chunk);
        }

        @Override
        public void onError(Throwable throwable) {
            throw new AssertionError(throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }
}
