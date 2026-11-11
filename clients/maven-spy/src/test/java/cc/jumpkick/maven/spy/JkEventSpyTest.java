// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.maven.spy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Writer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Events from {@code mvn -T}'s builder threads each land as one whole line. */
class JkEventSpyTest {

    @Test
    void two_threads_events_never_interleave_within_a_line() throws Exception {
        CountDownLatch firstWriting = new CountDownLatch(1);
        CountDownLatch otherWrote = new CountDownLatch(1);
        StringBuilder file = new StringBuilder();
        Thread first = Thread.currentThread();
        Writer slow = new Writer() {
            private boolean waited;

            // Writer's own write(String) and write(int) hold the writer's lock for the call; a
            // buffered file writer holds it per call too, so lines can interleave between calls.
            @Override
            public void write(String str, int off, int len) {
                write(str.toCharArray(), off, len);
            }

            @Override
            public void write(int c) {
                write(new char[] {(char) c}, 0, 1);
            }

            @Override
            public void write(char[] buf, int off, int len) {
                synchronized (file) {
                    file.append(buf, off, len);
                }
                if (Thread.currentThread() != first) {
                    otherWrote.countDown();
                } else if (!waited) {
                    // The first thread's first write gives the other thread its chance to cut in.
                    waited = true;
                    firstWriting.countDown();
                    try {
                        otherWrote.await(300, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        JkEventSpy spy = new JkEventSpy();
        spy.writeTo(slow);
        ExecutionEvent ended = event(ExecutionEvent.Type.SessionEnded);
        Thread other = new Thread(() -> {
            try {
                firstWriting.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            spy.onEvent(event(ExecutionEvent.Type.SessionStarted));
        });

        other.start();
        spy.onEvent(ended);
        other.join(5_000);

        String text;
        synchronized (file) {
            text = file.toString();
        }
        List<String> lines = List.of(text.split("\n"));
        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line -> assertThat(line.split("\t", -1)).hasSize(EventLine.FIELDS));
    }

    private static ExecutionEvent event(ExecutionEvent.Type type) {
        return new ExecutionEvent() {
            @Override
            public Type getType() {
                return type;
            }

            @Override
            public @Nullable MavenSession getSession() {
                return null;
            }

            @Override
            public @Nullable MavenProject getProject() {
                return null;
            }

            @Override
            public @Nullable MojoExecution getMojoExecution() {
                return null;
            }

            @Override
            public @Nullable Exception getException() {
                return null;
            }
        };
    }
}
