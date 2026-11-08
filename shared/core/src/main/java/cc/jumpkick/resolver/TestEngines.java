// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.version.Versions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The JUnit Platform launcher and engines {@code jk lock} adds to a test classpath. {@code jk
 * test} discovers and runs tests only through the Platform launcher, so a framework with no engine
 * of its own on the classpath is a suite the launcher cannot see. As Maven Surefire does, the
 * solved test classpath decides — the test graph and the module's main and provided dependencies
 * alike: a Jupiter API it reaches without an engine gets the engine at the API's version, and
 * the launcher rides the resolved Jupiter's Platform line. Unlike Surefire, the Vintage engine
 * follows a declared {@code junit:junit} only, never one a test library brings in; each {@link
 * Row} names such a declared framework and the engine that runs it.
 *
 * <p>An engine the JUnit team releases with the Platform (Vintage) is injected on the resolved
 * Jupiter's version line — the line the launcher rides too ({@link JupiterLine}) — and {@code
 * latest} when the graph holds no Jupiter, so launcher and engines land on one Platform line. An
 * engine released on a line of its own (the TestNG engine) is injected at its row's own selector
 * and rides the launcher's Platform through its own {@code junit-platform-engine} edge. The framework's declared version stays the framework version: an exact pin on the
 * trigger mediates the engine's own edge onto it ({@link #declaredTriggerPins}), as a direct
 * dependency mediates a transitive one in Maven.
 */
public final class TestEngines {

    private TestEngines() {}

    /**
     * The JUnit Platform launcher: always on the test graph, since {@code jk test} runs every suite
     * through the launcher API whatever framework the module chose.
     */
    public static final Dependency LAUNCHER = new Dependency(JupiterLine.LAUNCHER, VersionSelector.parse("latest"));

    /**
     * JUnit Jupiter, on the test graph of a module that declares no {@code [test-dependencies]} at
     * all. {@code latest} is pinned by {@code jk lock} and advanced by {@code jk update}.
     */
    public static final Dependency JUPITER =
            new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("latest"));

    /**
     * What {@code jk lock} first solves a module's test graph with beyond its declarations: the
     * launcher on the declared Jupiter's Platform line ({@link JupiterLine}), Jupiter on that
     * Jupiter's line when the module declares no test dependencies, and the engine of every
     * declared framework that has none of its own. The solved graph then decides what is added for
     * good ({@link #injectedRoots(JkBuild, Function, Function)}); these seeds only spare the second
     * solve when they already say the same.
     */
    public static List<Dependency> seedRoots(JkBuild project) {
        List<Dependency> roots = new ArrayList<>();
        roots.add(new Dependency(LAUNCHER.module(), JupiterLine.launcherSelector(project)));
        VersionSelector line = JupiterLine.engineSelector(project);
        roots.addAll(frameworkRoots(project, line, line));
        return roots;
    }

    /**
     * What jk adds to a module's test graph beyond its declarations, read off the solved graphs the
     * way Maven Surefire reads the resolved test classpath, which holds the module's main and
     * provided dependencies as well as its test ones. A Jupiter API reached without an engine gets
     * the Jupiter engine at the API's version; the launcher rides the Platform line of the resolved
     * Jupiter (engine, else API), else of the resolved {@code junit-platform-engine} a Vintage or
     * TestNG engine carries, else {@code latest}; Jupiter joins when the module declares no test
     * dependencies, on the line of a Jupiter its main or provided dependencies reach, else at
     * {@code latest}; a declared framework's engine joins on the resolved Jupiter's line. Anything
     * the module declares itself, in any scope on its test classpath, is its own and is not added.
     *
     * @param reach the package keys the module's main and test graphs reach from the given package keys
     * @param resolved the version the module's test classpath resolved for a {@code group:artifact}, or null
     */
    public static List<Dependency> injectedRoots(
            JkBuild project, Function<Set<String>, Set<String>> reach, Function<String, @Nullable String> resolved) {
        Set<String> declared = declaredTestKeys(project);
        Set<String> classpath = declaredClasspathKeys(project);
        Set<String> start = walkStart(project, declared);
        start.addAll(classpath);
        Set<String> reached = gas(reach.apply(start));
        String api = reached.contains(JupiterLine.API) ? resolved.apply(JupiterLine.API) : null;
        String engine = reached.contains(JupiterLine.ENGINE) ? resolved.apply(JupiterLine.ENGINE) : null;
        String jupiter = engine != null ? engine : api;
        List<Dependency> roots = new ArrayList<>();
        String platform =
                jupiter != null ? JupiterLine.platformVersion(jupiter) : resolved.apply(JupiterLine.PLATFORM_ENGINE);
        roots.add(new Dependency(LAUNCHER.module(), platform == null ? LAUNCHER.version() : exact(platform)));
        VersionSelector seeded = JupiterLine.engineSelector(project);
        String inherited = jupiterThrough(classpath, reach, resolved);
        roots.addAll(frameworkRoots(
                project, inherited == null ? seeded : exact(inherited), jupiter == null ? seeded : exact(jupiter)));
        if (api != null) roots.add(new Dependency(JupiterLine.ENGINE, exact(engine != null ? engine : api)));
        roots.removeIf(root -> declared.contains(root.packageKey()) || classpath.contains(root.packageKey()));
        return roots;
    }

    /**
     * The version of the Jupiter the module's main and provided declarations reach (engine, else
     * API), or null: the line the Jupiter jk adds for a module with no test dependencies rides.
     */
    private static @Nullable String jupiterThrough(
            Set<String> classpath,
            Function<Set<String>, Set<String>> reach,
            Function<String, @Nullable String> resolved) {
        if (classpath.isEmpty()) return null;
        Set<String> reached = gas(reach.apply(classpath));
        String engine = reached.contains(JupiterLine.ENGINE) ? resolved.apply(JupiterLine.ENGINE) : null;
        if (engine != null) return engine;
        return reached.contains(JupiterLine.API) ? resolved.apply(JupiterLine.API) : null;
    }

    /**
     * The package keys of the roots jk adds to a module's test graph ({@link #injectedRoots(JkBuild,
     * Function, Function)}), for a reader that walks the lock's rows rather than a solve: the
     * launcher, Jupiter when the module declares no test dependencies, each declared framework's
     * engine, and the Jupiter engine when the walk from the rest and from the module's main and
     * provided declarations reaches a Jupiter API. A key the module declares itself is not added.
     *
     * @param declared the package keys the module's {@code [test-dependencies]} and {@code
     *     [test-dev-dependencies]} stand for in the graph walked
     * @param classpath the package keys the module's other declarations on its test classpath
     *     ({@link #CLASSPATH_SCOPES}) stand for in the graph walked
     * @param reach the package keys the graph reaches from the given package keys
     */
    public static List<String> injectedKeys(
            JkBuild project, Set<String> declared, Set<String> classpath, Function<Set<String>, Set<String>> reach) {
        Set<String> start = walkStart(project, declared);
        List<String> keys = new ArrayList<>(start);
        keys.removeAll(declared);
        keys.removeAll(classpath);
        start.addAll(classpath);
        String engine = new Dependency(JupiterLine.ENGINE, LAUNCHER.version()).packageKey();
        if (gas(reach.apply(start)).contains(JupiterLine.API) && !classpath.contains(engine)) keys.add(engine);
        return keys;
    }

    /** The package keys of {@code project}'s declared test and test-dev dependencies. */
    public static Set<String> declaredTestKeys(JkBuild project) {
        return keysOf(project, Scope.TEST, Scope.TEST_DEV);
    }

    /**
     * The scopes besides test and test-dev a module's test classpath holds, as Maven's does: what
     * Surefire reads a Jupiter off as much as the test dependencies.
     */
    public static final List<Scope> CLASSPATH_SCOPES = List.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED, Scope.RUNTIME);

    /** The package keys of {@code project}'s declared dependencies in {@link #CLASSPATH_SCOPES}. */
    public static Set<String> declaredClasspathKeys(JkBuild project) {
        return keysOf(project, CLASSPATH_SCOPES.toArray(Scope[]::new));
    }

    private static Set<String> keysOf(JkBuild project, Scope... scopes) {
        Set<String> keys = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            for (Dependency d : project.dependencies().of(scope)) keys.add(d.packageKey());
        }
        return keys;
    }

    /**
     * Where the walk that asks whether the module reaches a Jupiter API starts: its declared test
     * roots plus every root jk adds whatever the graph holds (launcher, Jupiter, framework engines).
     */
    private static Set<String> walkStart(JkBuild project, Set<String> declared) {
        Set<String> start = new LinkedHashSet<>(declared);
        start.add(LAUNCHER.packageKey());
        for (Dependency d : frameworkRoots(project, LAUNCHER.version(), LAUNCHER.version())) {
            start.add(d.packageKey());
        }
        return start;
    }

    /**
     * Jupiter at {@code jupiter} when the module declares no test dependencies, then the engine of
     * each declared framework that has none of its own: a Platform-line engine at {@code line},
     * another at its own selector. Refuses a declared exact framework pin below its engine's floor.
     */
    private static List<Dependency> frameworkRoots(JkBuild project, VersionSelector jupiter, VersionSelector line) {
        List<Dependency> roots = new ArrayList<>();
        if (project.dependencies().of(Scope.TEST).isEmpty()) roots.add(new Dependency(JUPITER.module(), jupiter));
        roots.addAll(injected(project, line));
        return roots;
    }

    private static VersionSelector exact(String version) {
        return VersionSelector.parse("=" + version);
    }

    /** The {@code group:artifact} of every Maven package key among {@code keys}. */
    private static Set<String> gas(Set<String> keys) {
        Set<String> gas = new HashSet<>();
        for (String key : keys) {
            if (PackageId.isMavenPackageKey(key)) gas.add(PackageId.parse(key).ga());
        }
        return gas;
    }

    /**
     * One framework the launcher needs an engine for.
     *
     * @param trigger the framework's {@code group:artifact}; declaring it in a test scope injects
     *     {@code engine}
     * @param engine the Platform engine that runs the framework; with {@code platformLine} its
     *     selector is replaced by the declared Jupiter's line at injection, else it is the selector
     *     the lock solves
     * @param platformLine whether the engine is versioned with the JUnit Platform (Vintage) rather
     *     than on a line of its own (the TestNG engine)
     * @param floor the lowest framework version the engine accepts at runtime; an exact declared pin
     *     below it is refused at lock time
     * @param suggested the framework version the refusal and the importer point at
     */
    public record Row(String trigger, Dependency engine, boolean platformLine, String floor, String suggested) {

        /** The declared test-scope dependency on this row's framework, or null when none. */
        @Nullable
        Dependency declaredIn(JkBuild project) {
            for (Dependency d : project.dependencies().of(Scope.TEST)) {
                if (d.module().equals(trigger) && !d.isFile()) return d;
            }
            for (Dependency d : project.dependencies().of(Scope.TEST_DEV)) {
                if (d.module().equals(trigger) && !d.isFile()) return d;
            }
            return null;
        }

        /**
         * Refuse an exact pin the engine would reject at discovery time; that failure names the
         * engine, not the fix, and surfaces only after a compile. A platform-managed row carries no
         * version of its own: it is judged once the platform table says which one it supplies
         * ({@link #checkManagedFloor}).
         */
        void checkFloor(Dependency declared) {
            if (declared.isPlatformManaged()) return;
            if (!(declared.version() instanceof VersionSelector.Exact exact)) return;
            if (Versions.compare(exact.version(), floor) >= 0) return;
            throw new IllegalArgumentException("[test-dependencies] "
                    + trigger
                    + " "
                    + exact.version()
                    + " is too old for "
                    + engine.module()
                    + "; raise it to "
                    + suggested);
        }

        /**
         * Refuse a platform-managed row whose BOM supplies a version below the floor; {@code
         * managed} is the platform table, {@code group:artifact -> version}. A framework the table
         * does not manage is left to the solve, which names the missing version itself.
         */
        void checkManagedFloor(Dependency declared, Map<String, String> managed) {
            if (!declared.isPlatformManaged()) return;
            String version = managed.get(trigger);
            if (version == null || Versions.compare(version, floor) >= 0) return;
            throw new IllegalArgumentException("[test-dependencies] "
                    + trigger
                    + " "
                    + version
                    + " from the BOM is too old for "
                    + engine.module()
                    + "; declare "
                    + trigger
                    + " "
                    + suggested);
        }
    }

    /**
     * JUnit 4 (and the JUnit 3 {@code TestCase} style the JUnit 4 jar still runs) via the Vintage
     * engine. Vintage refuses a JUnit older than 4.12; 4.13.2 is the last release of the line.
     */
    public static final Row JUNIT4 = new Row(
            "junit:junit",
            new Dependency("org.junit.vintage:junit-vintage-engine", VersionSelector.parse("latest")),
            true,
            "4.12",
            "4.13.2");

    /**
     * TestNG via the JUnit team's {@code testng-engine}, which is released on its own line and
     * runs TestNG 6.14.3 and every 7.x; {@code @Test} classes are discovered from the test
     * classes directory the way Jupiter's are, and a suite XML is not read.
     */
    public static final Row TESTNG = new Row(
            "org.testng:testng",
            new Dependency("org.junit.support:testng-engine", VersionSelector.parse("latest")),
            false,
            "6.14.3",
            "7.12.0");

    /** Every framework the lock injects an engine for; a new framework is one more row. */
    public static final List<Row> ROWS = List.of(JUNIT4, TESTNG);

    /**
     * The engines {@code project}'s declared test dependencies call for, in row order — a
     * Platform-line engine at {@code line}, another at its own selector. Refuses a declared exact
     * framework pin below its engine's floor.
     */
    private static List<Dependency> injected(JkBuild project, VersionSelector line) {
        return ROWS.stream()
                .filter(row -> {
                    Dependency declared = row.declaredIn(project);
                    if (declared == null) return false;
                    row.checkFloor(declared);
                    return true;
                })
                .map(row -> new Dependency(
                        row.engine().module(),
                        row.platformLine() ? line : row.engine().version()))
                .toList();
    }

    /**
     * {@code group:artifact -> version} for every trigger the project pins exactly: the constraint
     * every edge onto that framework takes, so the injected engine's own edge cannot lift or fight
     * the declared pin. A platform-managed trigger pins nothing here — its BOM's version is already
     * in the table.
     */
    public static Map<String, String> declaredTriggerPins(JkBuild project) {
        Map<String, String> pins = new LinkedHashMap<>();
        for (Row row : ROWS) {
            Dependency declared = row.declaredIn(project);
            if (declared == null || declared.isPlatformManaged()) continue;
            if (declared.version() instanceof VersionSelector.Exact exact) pins.put(row.trigger(), exact.version());
        }
        return pins;
    }

    /**
     * Refuse every platform-managed trigger whose BOM supplies a version below its engine's floor;
     * {@code managed} is the platform table once every BOM has been read.
     */
    public static void checkManagedFloors(JkBuild project, Map<String, String> managed) {
        for (Row row : ROWS) {
            Dependency declared = row.declaredIn(project);
            if (declared != null) row.checkManagedFloor(declared, managed);
        }
    }
}
