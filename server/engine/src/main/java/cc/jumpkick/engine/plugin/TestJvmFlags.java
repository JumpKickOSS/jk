// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The step's {@code test JVM flags: …} line: the JVM flags a test JVM was started with, after the
 * worker budget and the GC log had their say. One line per distinct flag set a step forks; flags
 * that only name a fork's own paths (temp dir, jqwik database, GC log) do not make a set distinct.
 */
final class TestJvmFlags {

    static final String PREFIX = "test JVM flags: ";

    private static final ConcurrentHashMap<TaskContext, Set<List<String>>> NOTED = new ConcurrentHashMap<>();

    static {
        StepScope.onClose(NOTED::remove);
    }

    private TestJvmFlags() {}

    /** Write {@code command}'s JVM flags on {@code ctx} unless the step already named the same set. */
    static void note(@Nullable TaskContext ctx, List<String> command) {
        if (ctx == null) return;
        List<String> flags = jvmFlags(command);
        Set<List<String>> seen = NOTED.computeIfAbsent(ctx, k -> ConcurrentHashMap.newKeySet());
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
