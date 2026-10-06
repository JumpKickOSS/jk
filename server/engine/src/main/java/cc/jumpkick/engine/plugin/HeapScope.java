// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * Which worker the current thread is about to fork, so its GC log can be filed under one key:
 * module coordinate, task kind, JDK major.
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

    private static final ThreadLocal<Key> CURRENT = new ThreadLocal<>();

    private HeapScope() {}

    /** The worker this thread is forking, or {@code null}. */
    public static @Nullable Key get() {
        return CURRENT.get();
    }

    /** Bind {@code key} and return the previous one, which {@link #restore} puts back. */
    public static @Nullable Key bind(Key key) {
        Key previous = CURRENT.get();
        if (key == null) CURRENT.remove();
        else CURRENT.set(key);
        return previous;
    }

    /** Put back what {@link #bind} returned. {@code null} clears the binding. */
    public static void restore(@Nullable Key key) {
        if (key == null) CURRENT.remove();
        else CURRENT.set(key);
    }

    /** Run {@code body} with {@code key} bound; the previous binding is restored. */
    public static void call(Key key, Runnable body) {
        Key previous = CURRENT.get();
        CURRENT.set(key);
        try {
            body.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** As {@link #call(Key, Runnable)} for a body that returns a value. */
    public static <T> T call(Key key, Callable<T> body) throws Exception {
        Key previous = CURRENT.get();
        CURRENT.set(key);
        try {
            return body.call();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
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
