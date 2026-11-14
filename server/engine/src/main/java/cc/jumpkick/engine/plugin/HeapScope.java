// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Which worker the current thread is about to fork, so its GC log can be filed under one key:
 * module coordinate, task kind, JDK major. {@link ContextPropagator Carried}: a fork started from a
 * pool task files under the key of the step that submitted it.
 */
public final class HeapScope {

    /** Javac of main sources. */
    public static final String JAVA_COMPILE = "java-compile";

    /** Javac of test sources. A different heap from {@link #JAVA_COMPILE}. */
    public static final String JAVA_TEST_COMPILE = "java-test-compile";

    /** Kotlinc. */
    public static final String KOTLIN_COMPILE = "kotlin-compile";

    /** Groovyc. */
    public static final String GROOVY_COMPILE = "groovy-compile";

    /** A module's test suite, one-shot or pull. */
    public static final String TEST = "test";

    /** A GraalVM native-image build; its heap is the builder's. */
    public static final String NATIVE_IMAGE = TaskNames.NATIVE_IMAGE;

    /** The {@code jk format} worker. One key per project, not per module. */
    public static final String FORMAT = "format";

    /** Any other plugin worker. */
    public static final String PLUGIN = "plugin";

    /** The resident Kotlin script host. One engine-wide key, not a module compile. */
    public static final String KTS_HOST = "kts-host";

    private static final ScopedValue<Key> CURRENT = ScopedValue.newInstance();

    private static final ScopedValue<Boolean> FULL_LEASE = ScopedValue.newInstance();

    static {
        ContextPropagator.carry(CURRENT);
        ContextPropagator.carry(FULL_LEASE);
    }

    private HeapScope() {}

    /** The worker this thread is forking, or {@code null}. */
    public static @Nullable Key get() {
        return ContextPropagator.get(CURRENT);
    }

    /** Run {@code body} with {@code key} bound. */
    public static void call(Key key, Runnable body) {
        ScopedValue.where(CURRENT, key).run(body);
    }

    /** As {@link #call(Key, Runnable)} for a body that returns a value or throws. */
    public static <T extends @Nullable Object, X extends Throwable> T call(Key key, ScopedValue.CallableOp<T, X> body)
            throws X {
        return ScopedValue.where(CURRENT, key).call(body);
    }

    /** A worker fork: it does I/O and waits. */
    @FunctionalInterface
    public interface Fork<T extends @Nullable Object> {
        T run() throws IOException, InterruptedException;
    }

    /** As {@link #call(Key, ScopedValue.CallableOp)} for a fork. */
    public static <T extends @Nullable Object> T fork(Key key, Fork<T> body) throws IOException, InterruptedException {
        try {
            return call(key, body::run);
        } catch (IOException | InterruptedException | RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Run {@code body}'s forks leased at their whole {@code -Xmx}, not at the resident set they are
     * expected to hold: the rerun of a worker that was killed for memory or ran out of it.
     */
    public static <T extends @Nullable Object> T fullLease(Fork<T> body) throws IOException, InterruptedException {
        try {
            return ScopedValue.where(FULL_LEASE, Boolean.TRUE).call(body::run);
        } catch (IOException | InterruptedException | RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** As {@link #fullLease(Fork)} for a body that throws what it likes. */
    public static <T extends @Nullable Object, X extends Throwable> T fullLeaseCall(ScopedValue.CallableOp<T, X> body)
            throws X {
        return ScopedValue.where(FULL_LEASE, Boolean.TRUE).call(body);
    }

    /** True inside {@link #fullLease}. */
    public static boolean fullLease() {
        return Boolean.TRUE.equals(ContextPropagator.get(FULL_LEASE));
    }

    /**
     * One learned-heap key. {@code project} is the workspace or module directory the record is
     * filed under; {@code module} is {@code group:name}; {@code kind} is one of the constants
     * here; {@code jdk} is the feature major of the JVM that runs the worker.
     */
    public record Key(Path project, String module, String kind, int jdk) {
        public Key {
            module = module == null ? "" : module.trim();
            kind = kind == null || kind.isBlank() ? PLUGIN : kind.trim();
        }
    }
}
