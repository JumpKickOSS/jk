// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The step's {@code test JVM flags: …} line: the JVM flags a test JVM was started with, after the
 * worker budget and the GC log had their say. One line per distinct flag set a step forks; flags
 * that only name a fork's own paths (temp dir, jqwik database, GC log) do not make a set distinct.
 */
final class TestJvmFlags {

    static final String PREFIX = "test JVM flags: ";

    /** The flag sets this step has named. */
    private static final StepScope.Local<Set<List<String>>> NOTED = new StepScope.Local<>(ConcurrentHashMap::newKeySet);

    private TestJvmFlags() {}

    /** Write {@code command}'s JVM flags on this thread's step unless it already named the same set. */
    static void note(List<String> command) {
        TaskContext ctx = StepScope.current();
        Set<List<String>> seen = StepScope.local(NOTED);
        if (ctx == null || seen == null) return;
        List<String> flags = jvmFlags(command);
        if (seen.add(withoutForkPaths(flags))) ctx.output(PREFIX + String.join(" ", flags));
    }

    /** The arguments between the launcher and {@code -cp} / {@code -classpath}; all of them when neither appears. */
    static List<String> jvmFlags(List<String> command) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) break;
            out.add(arg);
        }
        return out;
    }

    private static List<String> withoutForkPaths(List<String> flags) {
        List<String> out = new ArrayList<>(flags.size());
        for (String f : flags) {
            if (f.startsWith("-Djava.io.tmpdir=")
                    || f.startsWith("-Djqwik.database=")
                    || f.startsWith(WorkerGc.FLAG_PREFIX)) {
                continue;
            }
            out.add(f);
        }
        return out;
    }
}
