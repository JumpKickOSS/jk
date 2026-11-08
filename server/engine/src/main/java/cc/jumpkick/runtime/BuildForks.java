// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.layout.Languages;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.repo.ArtifactFanOut;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.DokkaResolver;
import cc.jumpkick.runtime.base.GroovyPluginSetup;
import cc.jumpkick.runtime.base.KotlinPluginSetup;
import cc.jumpkick.task.WorkerClosure;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a build of some modules forks and loads besides their dependencies: jk's workers (the Java
 * compiler, the test runner, each built-in plugin's worker) and the language toolchains its sources
 * need (Kotlin's, Groovy's, Scala's compiler and bridge, and Dokka for a Kotlin library's javadoc),
 * at the versions the build itself picks.
 * Each is decided here by the planner's own reading of the module ({@link
 * CompileSupport#resolveLanguages}, {@link CompileToolchain}, {@link ActivePlugins}), never a second
 * opinion of it.
 *
 * <p>The sync stages them all at once, so a fresh home's first build downloads them beside the
 * lock's jars and priced with them, not one at a time behind its first compile, and a {@code jk
 * sync} leaves a build nothing to fetch.
 */
public final class BuildForks {

    private BuildForks() {}

    /** One thing a build forks or loads, by what it is and its version; staging it puts it on disk. */
    public record Fork(String key, ArtifactFanOut.Fetch<?> stage) {}

    /** Forks this engine staged, by store and fork; see {@link #stage}. */
    private static final Set<String> STAGED = ConcurrentHashMap.newKeySet();

    /** Forget what this engine staged: the store's GC and wipes call it, and tests. */
    public static void forgetStaged() {
        STAGED.clear();
    }

    /** The workers every JVM module's build forks: its compiler, and the runner its tests run in. */
    static final List<PluginJar> JVM_WORKERS = List.of(PluginJar.JAVA_COMPILER, PluginJar.TEST_RUNNER);

    /**
     * The forks of {@code modules} (module dir to manifest), once each however many modules share
     * them, in the order the modules name them. {@code lock} is the workspace's, read by each
     * module as its own build reads it; {@code lockFile} is where it lives.
     */
    public static List<Fork> of(Map<Path, JkBuild> modules, Lockfile lock, Path lockFile, Cas cas) {
        Map<String, Fork> forks = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> module : modules.entrySet()) {
            Path dir = module.getKey();
            JkBuild build = module.getValue();
            if (CompileSupport.coordinatorOnly(build, dir)) continue;
            Languages langs = CompileSupport.resolveLanguages(build.project(), dir);
            if (!(langs.java() || langs.kotlin() || langs.groovy() || langs.scala())) continue;
            Lockfile view = MemberRows.view(lock, lockFile, dir);
            for (PluginJar worker : JVM_WORKERS) worker(forks, worker);
            if (langs.kotlin()) {
                // The worker's own launch closure, and the compiler it loads at the module's version.
                worker(forks, PluginJar.KOTLIN_COMPILER);
                String version = CompileToolchain.kotlinVersionFor(view, build);
                forks.putIfAbsent(
                        "kotlin:" + version,
                        new Fork(
                                "kotlin:" + version, () -> KotlinPluginSetup.prepare(repos(build, cas), cas, version)));
            }
            // A Kotlin library's javadoc jar is Dokka's, at the release its [dokka] table selects.
            if (langs.kotlin() && PackagingKeys.javadocJar(build, dir, SessionContext.current())) {
                var dokka = build.build().dokka();
                String key = "dokka:" + dokka.format().wireName() + ":"
                        + dokka.version().raw();
                forks.putIfAbsent(key, new Fork(key, () -> {
                    RepoGroup repos = repos(build, cas);
                    return DokkaResolver.resolve(repos, cas, DokkaResolver.version(build, repos), dokka.format());
                }));
            }
            if (langs.groovy()) {
                worker(forks, PluginJar.GROOVY_COMPILER);
                String version = CompileToolchain.groovyVersionFor(view, build);
                forks.putIfAbsent(
                        "groovy:" + version,
                        new Fork(
                                "groovy:" + version, () -> GroovyPluginSetup.prepare(repos(build, cas), cas, version)));
            }
            if (langs.scala()) {
                String version = CompileToolchain.scalaVersionFor(view, build);
                forks.putIfAbsent(
                        "scala:" + version, new Fork("scala:" + version, () -> ScalaCompile.prepare(build, view, cas)));
            }
            for (ActivePlugin active : ActivePlugins.of(build, dir)) {
                if (!PluginTableRegistry.isBuiltIn(active.manifest().id())) continue;
                PluginJar.byArtifactId(PluginBuild.code(active).worker()).ifPresent(w -> worker(forks, w));
            }
        }
        return List.copyOf(forks.values());
    }

    /**
     * Put every fork on disk at once, each on its own: one that cannot be staged (offline, a
     * repository refusing) stops none of the others. Returns a line per fork that failed, naming
     * it; staging is ahead-of-time only, so the step that needs a fork still fetches it, or fails
     * with its own message, and a build that never needs it is not stopped by it.
     */
    public static List<String> stage(List<Fork> forks) throws InterruptedException {
        String store = JkStores.storeCas().root().toString();
        // --force revalidates what it stages, as every fetch under it does.
        boolean force = SessionContext.current().config().forceOr(false);
        List<ArtifactFanOut.Fetch<String>> stages = new ArrayList<>(forks.size());
        for (Fork fork : forks) {
            // Staged once in this engine, a fork is not walked again on every warm build: staging is
            // ahead of time only, so a store cleaned since is the needing step's to refetch.
            String memo = store + "|" + fork.key();
            if (!force && STAGED.contains(memo)) continue;
            stages.add(() -> {
                try {
                    fork.stage().fetch();
                    STAGED.add(memo);
                    return "";
                } catch (IOException | RuntimeException e) {
                    return fork.key() + ": " + e.getMessage();
                }
            });
        }
        List<String> failures = new ArrayList<>();
        try {
            for (String failure : ArtifactFanOut.concurrently(stages)) {
                if (!failure.isEmpty()) failures.add(failure);
            }
        } catch (IOException e) {
            failures.add(e.getMessage()); // every stage catches its own; unreachable in practice
        }
        return failures;
    }

    private static void worker(Map<String, Fork> forks, PluginJar worker) {
        String key = "worker:" + worker.artifactId();
        forks.putIfAbsent(key, new Fork(key, () -> WorkerClosure.paths(worker)));
    }

    private static RepoGroup repos(JkBuild build, Cas cas) {
        return RepoGroupBuilder.buildFor(build, null, cas);
    }
}
