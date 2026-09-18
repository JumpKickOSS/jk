// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * A javac plugin that fails the compile the way a crashing analysis plugin does: it writes its
 * report as raw text to javac's output writer and counts one error, but reports no diagnostic.
 * Discoverable as {@code JkTextOnlyFailure} once a processor-path entry registers it under
 * {@code META-INF/services}; its one option is the text to write.
 */
public final class TextOnlyFailurePlugin implements Plugin {

    public static final String NAME = "JkTextOnlyFailure";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        try {
            // Reflection, so this test source needs no export of jdk.compiler's internals at
            // compile time; the test JVM opens them.
            Object context = task.getClass().getMethod("getContext").invoke(task);
            Class<?> logClass = Class.forName("com.sun.tools.javac.util.Log");
            Class<?> contextClass = Class.forName("com.sun.tools.javac.util.Context");
            Object log = logClass.getMethod("instance", contextClass).invoke(null, context);
            Class<?> kind = Class.forName("com.sun.tools.javac.util.Log$WriterKind");
            Method print = logClass.getMethod("printRawLines", kind, String.class);
            print.invoke(log, Enum.valueOf(kind.asSubclass(Enum.class), "ERROR"), args[0]);
            Field errors = logClass.getField("nerrors");
            errors.setInt(log, errors.getInt(log) + 1);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
