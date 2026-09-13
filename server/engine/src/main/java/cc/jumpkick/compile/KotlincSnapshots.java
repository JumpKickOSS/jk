// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Log;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.task.KotlinClasspathAbi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The engine's way to a Kotlin classpath-entry ABI snapshot: fork {@code jk-kotlin-compiler} with
 * its {@code snapshot} op and read the digests back. The compiler never loads in the engine; the
 * worker writes the snapshots into the compile's own snapshot directory, so the incremental compile
 * that may follow finds them already there and pays for no second pass.
 *
 * <p>Shaped for {@link KotlinClasspathAbi.Snapshotter}: entries the worker does not report (absent,
 * unreadable, or a worker that died) are simply left out and key on content. A worker failure is
 * logged, not thrown — losing avoidance for one build is the whole cost, and a compile must not
 * fail over a cache key.
 */
public final class KotlincSnapshots {

    /** How much non-protocol worker chatter to keep for a worker that dies before speaking. */
    private static final int CHATTER_TAIL = 20;

    private KotlincSnapshots() {}

    /**
     * A snapshotter over the worker {@code request} names: its Build Tools API closure, its
     * snapshot directory and — for the AOT trainer — its compile classpath and JDK.
     */
    public static KotlinClasspathAbi.Snapshotter snapshotter(KotlincRequest request, WorkerEnv env) {
        return entries -> snapshot(request, entries, env);
    }

    /**
     * Snapshot {@code entries} through the worker of {@code request}, returning entry path (absolute,
     * normalized) to snapshot digest for every entry the worker reported. Empty when the request
     * carries no snapshot directory: without one the compile runs no snapshot-based IC either.
     */
    public static Map<Path, String> snapshot(KotlincRequest request, List<Path> entries, WorkerEnv env)
            throws IOException {
        Path snapshotDir = request.snapshotDir();
        if (snapshotDir == null || entries.isEmpty()) return Map.of();
        Path hostJavaHome = JavaHomes.runningJavaHome();
        String classpath = Classpaths.join(request.workerClasspath());
        Path spec = writeSpec(request, entries);
        try {
            // The same worker classpath maps the same AOT cache the compile fork uses; a miss trains
            // in the background with the compile's own trainer.
            List<String> jvmFlags = new ArrayList<>(PluginAot.kotlincFlags(
                    hostJavaHome,
                    classpath,
                    (aotOutput, scratch) ->
                            KotlincSpec.trainerCommand(request, classpath, hostJavaHome, aotOutput, scratch)));
            jvmFlags.add("--enable-native-access=ALL-UNNAMED");
            Path javaExe = JdkFingerprint.java(hostJavaHome);
            List<String> assembled =
                    PluginLoader.command(javaExe, classpath, jvmFlags, List.of("@" + spec.toAbsolutePath()));
            List<String> cmd = JvmOptions.javaCommand(javaExe.toString(), 1, assembled.subList(1, assembled.size()));

            Map<Path, String> digests = new LinkedHashMap<>();
            ArrayDeque<String> chatter = new ArrayDeque<>();
            int exit = new PluginClient(WorkerCompileDriver.KOTLIN_PREFIX)
                    .on(PluginProtocol.CP_SNAPSHOT, json -> {
                        @Nullable String path = Jsonl.str(json, PluginProtocol.PATH);
                        @Nullable String sha = Jsonl.str(json, PluginProtocol.SHA256);
                        if (path != null && sha != null) {
                            digests.put(Path.of(path).toAbsolutePath().normalize(), sha);
                        }
                    })
                    .passthrough(line -> {
                        if (chatter.size() >= CHATTER_TAIL) chatter.removeFirst();
                        chatter.addLast(line);
                    })
                    .run(cmd, env);
            if (exit != 0) {
                Log.warn(
                        "kotlinc snapshot worker exited " + exit + "; keying " + (entries.size() - digests.size())
                                + " classpath entries on content",
                        String.join("\n", chatter));
            }
            return digests;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    /**
     * The {@code snapshot} op's spec: the snapshot directory and the entries as compile-classpath
     * lines. Package-private so a test can read back what the worker is told.
     */
    static Path writeSpec(KotlincRequest request, List<Path> entries) throws IOException {
        Path snapshotDir = request.snapshotDir();
        if (snapshotDir == null) throw new IllegalArgumentException("a snapshot request needs a snapshot dir");
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_SNAPSHOT, null, "jk-kotlin-compiler")
                .layout(Map.of("snapshotDir", snapshotDir));
        for (Path entry : entries) sw.cp(entry, PluginProtocol.ROLE_COMPILE);
        Path spec = Files.createTempFile("jk-kotlinc-snapshot-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(spec);
        return spec;
    }
}
