// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.version.Versions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The JUnit Platform engines {@code jk lock} adds to a test classpath for the test frameworks a
 * module declares. {@code jk test} discovers and runs tests only through the Platform launcher, so
 * a framework with no engine of its own on the classpath is a suite the launcher cannot see; each
 * row here names such a framework and the engine that runs it.
 *
 * <p>An engine the JUnit team releases with the Platform (Vintage) is injected on the declared
 * Jupiter's version line — the line the launcher rides too ({@link JupiterLine}) — and {@code
 * latest} when no Jupiter is declared, so PubGrub aligns launcher and engines on one Platform
 * line. An engine released on a line of its own (the TestNG engine) is injected at its row's
 * own selector and rides the launcher's Platform through its own {@code junit-platform-engine}
 * edge. The framework's declared version stays the framework version: an exact pin on the
 * trigger mediates the engine's own edge onto it ({@link #declaredTriggerPins}), as a direct
 * dependency mediates a transitive one in Maven.
 */
public final class TestEngines {

    private TestEngines() {}

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
         * engine, not the fix, and surfaces only after a compile.
         */
        void checkFloor(Dependency declared) {
            if (!(declared.version() instanceof VersionSelector.Exact exact)) return;
            if (Versions.compare(exact.version(), floor) >= 0) return;
            throw new IllegalArgumentException("[test-dependencies] "
                    + trigger
                    + " "
                    + exact.version()
                    + " cannot run under jk: its suites run through "
                    + engine.module()
                    + ", which needs "
                    + trigger
                    + " "
                    + floor
                    + " or later — raise the pin to "
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
     * Platform-line engine on the declared Jupiter's line, another at its own selector. Refuses a
     * declared exact framework pin below its engine's floor.
     */
    static List<Dependency> injected(JkBuild project) {
        VersionSelector line = JupiterLine.engineSelector(project);
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
     * the declared pin.
     */
    static Map<String, String> declaredTriggerPins(JkBuild project) {
        Map<String, String> pins = new LinkedHashMap<>();
        for (Row row : ROWS) {
            Dependency declared = row.declaredIn(project);
            if (declared != null && declared.version() instanceof VersionSelector.Exact exact) {
                pins.put(row.trigger(), exact.version());
            }
        }
        return pins;
    }
}
