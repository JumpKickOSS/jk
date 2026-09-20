// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.compile.KotlincSnapshots;
import cc.jumpkick.compile.Recording;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Warms the Kotlin ABI memo for a module's consumers the moment its classes tree is whole. A
 * Kotlin consumer compiles against the sibling's {@code classes/main} and its compile key needs
 * each classpath entry's snapshot digest; on a cold memo the first consumer to meet a freshly
 * compiled sibling forks the Kotlin worker's {@code snapshot} op before its own compile can start.
 * The producer knows the tree as soon as its compile and resource copy have run, so the workspace
 * snapshots it then — after the classes steps finish, before the consumers are admitted — and every
 * consumer's key computation is a memo lookup.
 *
 * <p>Only a module with a Kotlin consumer is snapshotted, through that consumer's compiler
 * version: the snapshot belongs to the consumer's toolchain, and a module nobody compiles Kotlin
 * against has no snapshot to warm. A failure here costs one snapshot on the consumer's side and is
 * logged, never raised — a build must not fail over a cache key.
 */
public final class KotlinAbiWarmup {

    /** A test's window onto the warmed trees; empty in production, where a warm-up records nothing. */
    private static final AtomicReference<@Nullable Recording<Path>> RECORDING = new AtomicReference<>();

    private KotlinAbiWarmup() {}

    /**
     * A producer's warm-up: {@link #publish} is what the run phase hands the scheduler in place
     * of the bare admission signal, and {@link #await} is what module completion waits for — the
     * scheduler admits a module's dependents on completion when the signal never fired, so a
     * warm-up still in flight at that moment would let a consumer in ahead of it.
     */
    public static final class Warmup {
        private final Runnable publish;
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        private Warmup(Runnable publish) {
            this.publish = publish;
        }

        /** The signal to hand the scheduler. */
        public Runnable publish() {
            return publish;
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
     * The warm-up for {@code producer}: its signal snapshots the producer's classes tree for its
     * Kotlin consumers first — on the I/O pool, so the producer's own remaining steps (its
     * packaging and tests) are not held — then publishes; a module with no Kotlin consumer gets a
     * signal that publishes at once and nothing to await.
     */
    public static Warmup before(
            BuildGraph.Result graph, BuildGraph.BuildUnit producer, Path cache, Cas cas, Runnable publish) {
        BuildGraph.BuildUnit consumer = kotlinConsumer(graph, producer);
        if (consumer == null) {
            Warmup none = new Warmup(publish);
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
                publish.run();
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
        Path classes = BuildLayout.of(producer.dir(), producer.manifest()).classesDir();
        if (!Files.isDirectory(classes)) return;
        JkBuild project = consumer.manifest();
        String kotlinVersion =
                CompileToolchain.kotlinVersionFor(LockfileReader.read(LockPaths.lockFile(consumer.dir())), project);
        RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
        var kt = KotlinPluginSetup.prepare(repos, cas, kotlinVersion);
        Path snapshotDir = CacheTree.KOTLIN_CP_SNAPSHOTS.under(cache);
        // The stdlib rides along; the snapshot itself only reads the entries asked for.
        KotlincRequest request = KotlincRequest.builder()
                .classpath(List.of(classes, kt.stdlib()))
                .outputDir(snapshotDir)
                .workerClasspath(kt.workerClasspath())
                .javaHome(JavaHomes.resolveJavaHome(consumer.dir()))
                .snapshotDir(snapshotDir)
                .extraArgs(List.of("-no-stdlib"))
                .build();
        WorkerEnv env = WorkerEnv.forModule(project.build().env(), consumer.dir(), null);
        KotlinClasspathAbi.tokens(List.of(classes), KotlincSnapshots.snapshotter(request, env));
        Recording.note(RECORDING, classes.toAbsolutePath().normalize());
    }

    /** Records every classes tree warmed until closed — how a test proves who snapshotted a tree. */
    public static Recording<Path> record() {
        return Recording.open(RECORDING);
    }
}
