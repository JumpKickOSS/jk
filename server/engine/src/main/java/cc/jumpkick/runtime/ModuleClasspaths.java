// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.KspProcessors;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Every classpath a module's compiles and tests read, derived once from its manifest, shape, lock
 * and workspace: the build's classpath step publishes it and the forecast prices each step against
 * it, so a request built on one side names the paths the other keys.
 *
 * <p>Siblings enter the compile classpaths and processor paths through their classes trees, the
 * test runtime through their jars, always as the declared closure rather than what is on disk now,
 * so {@code jk explain} reproduces the build's keys after a clean. The derivation is pure: waiting
 * for or requiring a sibling, failing on an unresolved processor and fetching a plugin's jars are
 * the caller's ({@link Contributions}).
 *
 * @param main compile-main's classpath: the module's lock rows and those its siblings pass on, the
 *     siblings' classes trees, then the plugin-provided compile jars
 * @param processor {@code [processor-dependencies]}' closure, then the sibling processors' trees: what
 *     a KSP round and joint groovyc run
 * @param javacProcessor the path javac runs over the main sources: {@code processor} with the KSP
 *     jars split off in a module that plans a KSP round
 * @param javacTestProcessor compile-test's javac processor path ({@link ProcessorPaths#forTest})
 * @param kspTestProcessor the path compile-test's KSP round splits its KSP jars from
 * @param testProcessorDiscovery compile-test discovers processors on its classpath when its path
 *     declares none ({@link ProcessorPaths#discovers})
 * @param contributedProcessor the processors the module's plugins append to compile-main's path
 * @param contributedTestProcessor the processors the module's plugins append to compile-test's path
 * @param testCompile the test compile classpath: the lock's and siblings' test scopes, then the
 *     provided compile and test-only jars
 * @param testRuntime the test runtime classpath: the lock's test rows and sibling jars, then the
 *     provided jars the test JVM runs with
 * @param providedRuntime the provided jars the test JVM also gets last
 */
record ModuleClasspaths(
        List<Path> main,
        List<Path> processor,
        List<Path> javacProcessor,
        List<Path> javacTestProcessor,
        List<Path> kspTestProcessor,
        boolean testProcessorDiscovery,
        List<Path> contributedProcessor,
        List<Path> contributedTestProcessor,
        List<Path> testCompile,
        List<Path> testRuntime,
        List<Path> providedRuntime) {

    ModuleClasspaths {
        main = List.copyOf(main);
        processor = List.copyOf(processor);
        javacProcessor = List.copyOf(javacProcessor);
        javacTestProcessor = List.copyOf(javacTestProcessor);
        kspTestProcessor = List.copyOf(kspTestProcessor);
        contributedProcessor = List.copyOf(contributedProcessor);
        contributedTestProcessor = List.copyOf(contributedTestProcessor);
        testCompile = List.copyOf(testCompile);
        testRuntime = List.copyOf(testRuntime);
        providedRuntime = List.copyOf(providedRuntime);
    }

    /**
     * Who reads the classpaths. The build runs after the sync phase, so every checksummed lock row
     * must be on disk; the forecast runs before it and names a row's jar whether or not it is.
     */
    enum Mode {
        BUILD,
        FORECAST;

        boolean requirePresent() {
            return this == BUILD;
        }
    }

    /**
     * What the module's plugins add, resolved by the caller: the build fetches and fails on a jar
     * it cannot get, the forecast leaves out what does not resolve.
     */
    record Contributions(ProvidedClasspath.Provided provided, ContributedProcessors.Paths processors) {
        static final Contributions NONE =
                new Contributions(ProvidedClasspath.Provided.NONE, ContributedProcessors.Paths.NONE);
    }

    /** {@code project}'s classpaths at {@code dir} under {@code lock}, as {@code mode} reads them. */
    static ModuleClasspaths derive(
            Path dir,
            JkBuild project,
            ModuleShape shape,
            Lockfile lock,
            ClasspathResolver resolver,
            Contributions contributions,
            Mode mode)
            throws IOException {
        boolean present = mode.requirePresent();
        ProvidedClasspath.Provided provided = contributions.provided();

        List<Path> main = new ArrayList<>(mainCompile(
                dir,
                project,
                lock,
                resolver,
                WorkspaceClasspath.resolve(dir, project, WorkspaceClasspath.COMPILE_SCOPES),
                present));
        main.addAll(provided.compile());

        Set<Scope> mainProcessor = Set.of(Scope.PROCESSOR);
        List<Path> processor = processorPath(
                dir,
                project,
                lock,
                resolver,
                WorkspaceClasspath.resolve(dir, project, mainProcessor),
                mainProcessor,
                present);
        List<Path> javacProcessor = shape.ksp() ? KspProcessors.split(processor).javac() : processor;
        Set<Scope> testProcessorScopes = ProcessorPaths.testScopes(project);
        List<Path> testClosure = processorPath(
                dir,
                project,
                lock,
                resolver,
                WorkspaceClasspath.resolve(dir, project, testProcessorScopes),
                testProcessorScopes,
                present);

        // Both test classpaths name the declared closure, built or not: a sibling's test classes
        // and jar may still be on their way when the build reads them, and a list filtered to
        // what is on disk now would silently drop them from the tests.
        WorkspaceClasspath.Result testSiblings =
                WorkspaceClasspath.resolve(dir, project, WorkspaceClasspath.TEST_SCOPES);
        List<Path> testCompile =
                new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST, present, project, dir));
        testCompile.addAll(testSiblings.siblingClosureClasses());
        // A plugin's provided classpath is provided scope by another door: on both test classpaths,
        // as a [provided-dependencies] row is, and in no artifact. Its test-only entries (a
        // platform's test framework) join here and nowhere else; a compile-only entry (a platform
        // the test JVM gets whole from elsewhere) is compiled against and never run with.
        testCompile.addAll(provided.compile());
        testCompile.addAll(provided.testOnly());
        List<Path> testRuntime = new ArrayList<>(resolver.testRuntimeClasspathFor(lock, present, project, dir));
        testRuntime.addAll(testSiblings.siblingClosureJars());

        return new ModuleClasspaths(
                main,
                processor,
                javacProcessor,
                ProcessorPaths.forTest(project, processor, javacProcessor, testClosure),
                ProcessorPaths.forTest(project, processor, processor, testClosure),
                ProcessorPaths.discovers(project),
                contributions.processors().main(),
                contributions.processors().test(),
                testCompile,
                provided.onTestRuntime(testRuntime),
                provided.runtime());
    }

    /**
     * The module's compile classpath from the lock — its own rows, then those its siblings pass on
     * — then the siblings' classes trees: the declared closure (deterministic paths), not the built
     * set, so a key is stable whether or not target/ is populated. A tree is whole once its module
     * has compiled, which is what admits this module to the schedule: javac compiles against these
     * paths while the sibling may still be packaging.
     */
    static List<Path> mainCompile(
            Path dir,
            JkBuild project,
            Lockfile lock,
            ClasspathResolver resolver,
            WorkspaceClasspath.Result siblings,
            boolean requirePresent) {
        List<Path> cp = new ArrayList<>(
                resolver.classpathFor(lock, ClasspathResolver.COMPILE_MAIN, requirePresent, project, dir));
        cp.addAll(siblings.siblingClosureClasses());
        return cp;
    }

    /**
     * A {@code -processorpath} / KSP processor classpath: the module's classpath over {@code
     * scopes}, then the classes trees of the workspace siblings declared in those tables.
     *
     * <p>A processor runs as a program, so a sibling processor's own runtime closure rides with it
     * ({@link ClasspathResolver#classpathFor(Lockfile, Set, boolean, JkBuild, Path)}). A sibling
     * processor enters through its classes tree, like every compile-time sibling: the tree carries
     * the service registration once the sibling's resources are copied, and is whole before the
     * sibling packages.
     */
    static List<Path> processorPath(
            Path dir,
            JkBuild project,
            Lockfile lock,
            ClasspathResolver resolver,
            WorkspaceClasspath.Result siblings,
            Set<Scope> scopes,
            boolean requirePresent) {
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, scopes, requirePresent, project, dir));
        for (Path classes : siblings.siblingClosureClasses()) {
            if (!cp.contains(classes)) cp.add(classes);
        }
        return cp;
    }
}
