// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.WorkerClasspath;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.JavaHomes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the plugin worker command line every plugin fork uses. Workers are <strong>thin
 * jars</strong> launched as {@code java [jvmFlags] -cp <worker>:<deps…>
 * cc.jumpkick.plugin.process.PluginMain <spec>} (JK-1347). Heap sizing goes through {@link
 * JvmOptions}' shared plan.
 *
 * <p>Classpath resolution: prefer {@code $JK_LIB_DIR/&lt;id&gt;/} hardlinks (default {@code
 * store/lib/&lt;id&gt;/}, same tree as installed tools) when install materialised them (JK-1348);
 * else the optional {@code <worker>.jar.classpath} sidecar (JK-1347).
 */
final class PluginLaunch {

    private PluginLaunch() {}

    /** {@code java [extraJvmArgs] -cp … PluginMain spec}, heap-sized for one requested JVM. */
    static List<String> javaCommand(Path workerJar, List<String> extraJvmArgs, Path spec) {
        Path javaExe =
                JavaHomes.runningJavaHome().resolve("bin").resolve(HostPlatform.isWindows() ? "java.exe" : "java");
        String cp = WorkerClasspath.resolve(workerJar);
        List<String> jvmFlags = new ArrayList<>(extraJvmArgs);
        // batchFlags applied inside JvmOptions.javaCommand via concurrency=1 — but PluginLoader
        // expects raw flags only. Use the same heap plan as before.
        List<String> rest = new ArrayList<>();
        // Reuse PluginLoader.command shape: javaExe + jvmFlags + -cp + main + args
        // JvmOptions.javaCommand prepends java + memory flags around `rest`.
        List<String> afterMem = new ArrayList<>();
        afterMem.addAll(jvmFlags);
        afterMem.add("-cp");
        afterMem.add(cp);
        afterMem.add(PluginLoader.WORKER_MAIN);
        afterMem.add(spec.toAbsolutePath().toString());
        return JvmOptions.javaCommand(javaExe.toString(), 1, afterMem);
    }

    /** {@code java -cp … PluginMain spec} with no extra JVM args. */
    static List<String> javaCommand(Path workerJar, Path spec) {
        return javaCommand(workerJar, List.of(), spec);
    }
}
