// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.jspecify.annotations.Nullable;

/**
 * Which workspace classes' code ran while each test class ran, as flight-recorder events: every
 * method of a class the probe marks ({@link EntryMarks}) calls {@link #used} first, and the first
 * call since the last window opened records a {@code jk.ClassUse} event naming the class. The engine
 * joins those events to the windows that hold them as it joins a file read, so a class's window
 * names every marked class whose code ran in it, wherever that class was loaded first.
 *
 * <p>A class the probe could not mark is named again each time a window opens: it counts as used
 * by every class. The runner opens a window, then calls {@link #opened}, before the class's first
 * test runs.
 */
public final class ClassUses {

    private ClassUses() {}

    /** The event's name, which the engine's recording settings enable and its reader joins on. */
    public static final String EVENT = "jk.ClassUse";

    private static final Object LOCK = new Object();

    /** Each marked class's number, by binary name. Guarded by {@link #LOCK}. */
    private static final Map<String, Integer> IDS = new HashMap<>();

    private static volatile String[] names = new String[256];

    /** By class number, the window count when it was last named. */
    private static volatile int[] named = new int[256];

    /** How many windows have opened, plus one: a class named under an older count is named again. */
    private static volatile int windows = 1;

    /** The classes no mark could be put in, named at every window. */
    private static volatile int[] unmarked = new int[0];

    /** The number of the class {@code binaryName}, taken on its first ask. */
    static int id(String binaryName) {
        synchronized (LOCK) {
            Integer known = IDS.get(binaryName);
            if (known != null) return known;
            int id = IDS.size();
            if (id == names.length) {
                names = Arrays.copyOf(names, id * 2);
                named = Arrays.copyOf(named, id * 2);
            }
            names[id] = binaryName;
            IDS.put(binaryName, id);
            return id;
        }
    }

    /** The class {@code binaryName} holds no mark: it is named in every window. */
    static void unmarked(String binaryName) {
        int id = id(binaryName);
        synchronized (LOCK) {
            int[] now = Arrays.copyOf(unmarked, unmarked.length + 1);
            now[now.length - 1] = id;
            unmarked = now;
        }
        used(id);
    }

    /** The marked class numbered {@code id} is running code: named unless already named in this window. */
    public static void used(int id) {
        int now = windows;
        int[] at = named;
        if (at[id] == now) return;
        at[id] = now;
        Recorded.name(names[id]);
    }

    /** A test class's window just opened: every class is named again when its code next runs. */
    public static void opened() {
        windows++;
        for (int id : unmarked) used(id);
    }

    /** Whether the class numbered {@code id} was named since the last window opened. */
    static boolean namedInWindow(int id) {
        return named[id] == windows;
    }

    /** The only code that names the flight recorder's types; the probe marks no class without them. */
    private static final class Recorded {
        static void name(@Nullable String binaryName) {
            UseEvent e = new UseEvent();
            if (!e.isEnabled()) return;
            e.className = binaryName;
            e.commit();
        }
    }

    /** A marked class's code ran, the first time since the last test class's window opened. */
    @Name(EVENT)
    @Label("Class used")
    @Category("JumpKick")
    @Description("A workspace class's code ran, the first time since a test class's window opened")
    @StackTrace(false)
    static final class UseEvent extends Event {
        @Label("Class")
        @Nullable
        String className;
    }
}
