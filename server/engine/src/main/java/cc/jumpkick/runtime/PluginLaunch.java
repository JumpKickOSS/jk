// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Builds the plugin worker command line every plugin fork uses. Workers are <strong>thin
 * jars</strong> launched as {@code java [jvmFlags] -cp <worker>:<deps…>
 * cc.jumpkick.plugin.process.PluginMain <spec>}. Heap sizing goes through {@link
 * JvmOptions}' shared plan.
 *
 * <p>Classpath resolution: sibling POM + {@code repos/jk-local} (and the other store repos).
 *
 * <p>This is also where the job's <strong>network policy</strong> joins the spec
 * ({@link PluginLoader#sealNetworkPolicy}). The invariant is the seal, not this launcher: every
 * spec a worker decodes states its policy, and the worker reads the answer off the spec instead
 * of off its own environment. Stamping at the fork rather than at each of the ten {@link
 * SpecWriter} plan-builder sites is what makes it impossible to omit here; the compiler and
 * formatter fork paths, which build their command lines with {@code PluginLoader.command}
 * directly, seal at their spec producers instead.
 */
final class PluginLaunch {

    private PluginLaunch() {}

    /** {@code java [extraJvmArgs] -cp … PluginMain spec}, heap-sized for one requested JVM. */
    static List<String> javaCommand(Path workerJar, List<String> extraJvmArgs, Path spec) throws IOException {
        PluginLoader.sealNetworkPolicy(spec);
        Path javaExe = JdkFingerprint.java(JavaHomes.runningJavaHome());
        String cp = WorkerLaunchClasspath.resolve(workerJar);
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
        afterMem.add(mainClassOf(workerJar));
        afterMem.add(spec.toAbsolutePath().toString());
        return JvmOptions.javaCommand(javaExe.toString(), 1, afterMem);
    }

    /**
     * The jar's own {@code Main-Class} when it declares one, else the SDK host. First-party workers
     * declare {@link PluginLoader#WORKER_MAIN} and are unaffected; a third-party plugin that
     * hand-rolls its entry point must not be launched under the SDK host, which would
     * {@code ServiceLoader}-look for a {@code Plugin} the jar never registers and exit 70.
     */
    private static String mainClassOf(Path workerJar) {
        try (var jar = new JarFile(workerJar.toFile())) {
            var manifest = jar.getManifest();
            if (manifest != null) {
                String declared = manifest.getMainAttributes().getValue("Main-Class");
                if (declared != null && !declared.isBlank()) return declared.strip();
            }
        } catch (IOException ignored) {
            // unreadable jar — the launch itself will surface the real error
        }
        return PluginLoader.WORKER_MAIN;
    }

    /** {@code java -cp … PluginMain spec} with no extra JVM args. */
    static List<String> javaCommand(Path workerJar, Path spec) throws IOException {
        return javaCommand(workerJar, List.of(), spec);
    }

    /**
     * As {@link #javaCommand(Path, Path)}, naming the intended plugin by protocol prefix. Needed
     * when the worker lib dir carries a sibling plugin jar as a plain dependency (grails ships the
     * spring-boot plugin for Boot packaging) and ServiceLoader would otherwise see two plugins.
     */
    static List<String> javaCommand(Path workerJar, Path spec, String protocolPrefix) throws IOException {
        List<String> extra = protocolPrefix == null || protocolPrefix.isBlank()
                ? List.of()
                : List.of("-Djk.plugin.prefix=" + protocolPrefix);
        return javaCommand(workerJar, extra, spec);
    }
}
