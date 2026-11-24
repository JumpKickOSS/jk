// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.testrunner.env.ClassUses;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.jspecify.annotations.Nullable;

/**
 * The window each top-level test class ran in, as a flight-recorder event in the recording the
 * engine starts for the suite: begun when the class's container starts, committed when it
 * finishes. The engine joins every other event of the recording (a file read, a process started, a
 * native library loaded) to the classes whose window holds its start time, and so attributes what
 * the suite did to the class that did it.
 *
 * <p>A JVM without the flight recorder's API runs the suite unrecorded: nothing here is touched
 * then. A JVM recording nothing pays one enabled-check per class.
 */
final class ClassWindows {

    /** Whether the flight recorder's API is there to emit through. */
    private static final boolean AVAILABLE = available();

    private final Map<String, Object> open = new ConcurrentHashMap<>();

    /** A top-level test class started: its window opens. */
    void started(String uniqueId, String className) {
        if (!AVAILABLE) return;
        Object window = Recorded.begin(uniqueId, className);
        if (window == null) return;
        open.put(uniqueId, window);
        ClassUses.opened();
    }

    /** The class whose container is {@code uniqueId} finished: its window closes. */
    void finished(String uniqueId) {
        if (!AVAILABLE) return;
        Object window = open.remove(uniqueId);
        if (window != null) Recorded.commit(window);
    }

    /** True for a class name with no enclosing class: a nested class runs inside its outer class's window. */
    static boolean topLevel(String className) {
        return className.indexOf('$') < 0;
    }

    private static boolean available() {
        try {
            Class.forName("jdk.jfr.Event", false, ClassWindows.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /** The only code that names the flight recorder's types, loaded once {@link #AVAILABLE} holds. */
    private static final class Recorded {
        static @Nullable Object begin(String uniqueId, String className) {
            TestClassEvent e = new TestClassEvent();
            if (!e.isEnabled()) return null;
            e.testClass = className;
            e.uniqueId = uniqueId;
            e.begin();
            return e;
        }

        static void commit(Object window) {
            ((TestClassEvent) window).commit();
        }
    }

    /** One test class's run, from its container's start to its finish. */
    @Name(TestClassEvent.NAME)
    @Label("Test class")
    @Category("JumpKick")
    @Description("A top-level test class ran, from its container's start to its finish")
    @StackTrace(false)
    static final class TestClassEvent extends Event {
        /** The event's name, which the engine's recording settings enable and its reader joins on. */
        static final String NAME = "jk.TestClass";

        @Label("Test class")
        @Nullable
        String testClass;

        @Label("Unique id")
        @Nullable
        String uniqueId;
    }
}
