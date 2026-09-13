// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.compile.KotlincSnapshots;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.KotlinPluginSetup;
import cc.jumpkick.task.KotlinClasspathAbi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * Warms the Kotlin ABI memo for a module's consumers the moment its jar is packaged. A Kotlin
 * consumer's compile key needs each classpath entry's snapshot digest; on a cold memo the first
 * consumer to meet a freshly packaged sibling forks the Kotlin worker's {@code snapshot} op
 * before its own compile can start. The producer knows the jar as soon as {@code package-jar}
 * writes it, so the workspace snapshots it then — after the artifact steps finish, before the
 * consumers are admitted — and every consumer's key computation is a memo lookup.
 *
 * <p>Only a module with a Kotlin consumer is snapshotted, through that consumer's compiler
 * version: the snapshot belongs to the consumer's toolchain, and a module nobody compiles Kotlin
 * against has no snapshot to warm. A failure here costs one snapshot on the consumer's side and is
 * logged, never raised — a build must not fail over a cache key.
 */
public final class KotlinAbiWarmup {

    /** The entries this process warmed, in order — for tests that prove who snapshotted a jar. */
    private static final List<Path> WARMED = Collections.synchronizedList(new ArrayList<>());

    private KotlinAbiWarmup() {}

    /**
     * A producer's warm-up: {@link #artifactsReady} is what the run phase hands the scheduler in
     * place of the bare signal, and {@link #await} is what module completion waits for — the
     * scheduler publishes a module's artifacts on completion when the signal never fired, so a
     * warm-up still in flight at that moment would let a consumer in ahead of it.
     */
    public static final class Warmup {
        private final Runnable artifactsReady;
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        private Warmup(Runnable artifactsReady) {
            this.artifactsReady = artifactsReady;
        }

        /** The signal to hand the scheduler. */
        public Runnable artifactsReady() {
            return artifactsReady;
        }

        /** Blocks until the warm-up has finished, if one was started. */
        public void await() {
            try {
                done.get(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                Log.debug("KotlinAbiWarmup: the consumer snapshots it instead", e);
            }
        }
    }

    /**
     * The warm-up for {@code producer}: its signal snapshots the producer's jar for its Kotlin
     * consumers first — on the I/O pool, so the producer's own remaining steps (its tests) are
     * not held — then publishes; a module with no Kotlin consumer gets a signal that publishes
     * at once and nothing to await.
     */
    public static Warmup before(
            BuildGraph.Result graph, BuildGraph.BuildUnit producer, Path cache, Cas cas, Runnable artifactsReady) {
        BuildGraph.BuildUnit consumer = kotlinConsumer(graph, producer);
        if (consumer == null) {
            Warmup none = new Warmup(artifactsReady);
            none.done.complete(null);
            return none;
        }
        Warmup[] holder = new Warmup[1];
        Warmup warmup = new Warmup(() -> JkThreads.io().execute(() -> {
            try {
                warm(producer, consumer, cache, cas);
            } catch (IOException | RuntimeException e) {
                Log.debug("KotlinAbiWarmup: the consumer snapshots it instead", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                artifactsReady.run();
                holder[0].done.complete(null);
            }
        }));
        holder[0] = warmup;
        return warmup;
    }

    /** A module that depends on {@code producer} and compiles Kotlin, or null. */
    static BuildGraph.@Nullable BuildUnit kotlinConsumer(BuildGraph.Result graph, BuildGraph.BuildUnit producer) {
        Path dir = BuildGraph.canonicalPath(producer.dir());
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            Set<Path> prereqs = graph.edges().getOrDefault(unit.dir(), Set.of());
            boolean depends = false;
            for (Path prereq : prereqs) {
                if (BuildGraph.canonicalPath(prereq).equals(dir)) {
                    depends = true;
                    break;
                }
            }
            if (!depends) continue;
            if (CompileSupport.resolveLanguages(unit.manifest().project(), unit.dir())
                    .kotlin()) return unit;
        }
        return null;
    }

    private static void warm(BuildGraph.BuildUnit producer, BuildGraph.BuildUnit consumer, Path cache, Cas cas)
            throws IOException, InterruptedException {
        Path jar = BuildLayout.of(producer.dir(), producer.manifest()).mainJar();
        if (!Files.isRegularFile(jar)) return;
        JkBuild project = consumer.manifest();
        String kotlinVersion =
                CompileToolchain.kotlinVersionFor(LockfileReader.read(LockPaths.lockFile(consumer.dir())), project);
        RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
        var kt = KotlinPluginSetup.prepare(repos, cas, kotlinVersion);
        Path snapshotDir = CacheTree.KOTLIN_CP_SNAPSHOTS.under(cache);
        // The trainer behind an AOT miss compiles a hello-world against this classpath, so the
        // stdlib rides along; the snapshot itself only reads the entries asked for.
        KotlincRequest request = KotlincRequest.builder()
                .classpath(List.of(jar, kt.stdlib()))
                .outputDir(snapshotDir)
                .workerClasspath(kt.workerClasspath())
                .javaHome(JavaHomes.resolveJavaHome(consumer.dir()))
                .snapshotDir(snapshotDir)
                .extraArgs(List.of("-no-stdlib"))
                .build();
        WorkerEnv env = WorkerEnv.forModule(project.build().env(), consumer.dir(), null);
        KotlinClasspathAbi.tokens(List.of(jar), KotlincSnapshots.snapshotter(request, env));
        WARMED.add(jar.toAbsolutePath().normalize());
    }

    /** The jars this process warmed so far. */
    public static List<Path> warmed() {
        synchronized (WARMED) {
            return List.copyOf(WARMED);
        }
    }
}
