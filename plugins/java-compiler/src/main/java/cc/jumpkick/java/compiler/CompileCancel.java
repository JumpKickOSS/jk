// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Cancelling one compile of a worker that runs many. The worker cannot stop a thread, and killing
 * the process would take every other compile with it, so each compile carries a flag the engine's
 * {@code cancel} sets, and a javac {@link TaskListener} looks at it on every event javac fires —
 * each file parsed, entered, attributed, generated — and throws {@link Cancelled} once it is set.
 * The compile ends at the next event, mid-way through, and its outputs are whatever it wrote by
 * then; the engine has stopped waiting for them.
 *
 * <p>The flag is bound to the thread that runs the compile, because javac fires its events on the
 * thread that called it. A compile with no flag bound (a one-shot worker, a test) is never
 * cancelled.
 */
final class CompileCancel {

    private static final ThreadLocal<@Nullable AtomicBoolean> FLAG = new ThreadLocal<>();

    private CompileCancel() {}

    /** Thrown out of javac once the compile running on this thread has been cancelled. */
    static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Cancelled() {
            super("compile cancelled", null, false, false);
        }
    }

    /** Make {@code flag} the cancel switch of every compile this thread runs until {@link #unbind}. */
    static void bind(AtomicBoolean flag) {
        FLAG.set(flag);
    }

    /** This thread runs no cancellable compile any more. */
    static void unbind() {
        FLAG.remove();
    }

    /** Install the check on {@code task} when a flag is bound to this thread. */
    static void listen(JavacTask task) {
        AtomicBoolean flag = FLAG.get();
        if (flag == null) return;
        task.addTaskListener(new TaskListener() {
            @Override
            public void started(TaskEvent e) {
                check(flag);
            }

            @Override
            public void finished(TaskEvent e) {
                check(flag);
            }
        });
    }

    /**
     * Throw {@link Cancelled} when the compile running on this thread has been cancelled: a compile
     * cancelled while it waited for a thread ends before it reads its spec.
     */
    static void checkpoint() {
        AtomicBoolean flag = FLAG.get();
        if (flag != null) check(flag);
    }

    /**
     * Throw the {@link Cancelled} behind {@code failure} when there is one: javac and Zinc wrap what
     * a listener throws, and a cancelled compile must answer {@code cancelled}, not a crash.
     */
    static void rethrowIfCancelled(Throwable failure) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof Cancelled cancelled) throw cancelled;
            if (c.getCause() == c) return;
        }
    }

    private static void check(AtomicBoolean flag) {
        if (flag.get()) throw new Cancelled();
    }
}
