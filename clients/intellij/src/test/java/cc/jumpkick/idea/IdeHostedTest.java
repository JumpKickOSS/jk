// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import junit.framework.TestCase;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * The suites that run inside a headless IDE, on the JUnit Platform. The platform's heavy test base
 * is a JUnit 3 {@link TestCase}; each {@code test*} method of each suite here is one dynamic test,
 * run through {@link TestCase#runBare()} so the base's own setUp, EDT dispatch and tearDown apply.
 * Integration tier: every test here starts an IDE; {@link IdeHostedJkTest} runs the ones in {@link
 * #DRIVE_JK}.
 */
@Tag("integration")
class IdeHostedTest {

    private static final List<Class<? extends TestCase>> SUITES =
            List.of(JkManifestWatcherVfsTest.class, JkWorkspaceImportTest.class, JkDebugBreakpointTest.class);

    /** {@code Suite#method} of the tests that drive the jk on PATH rather than a stub. */
    static final Set<String> DRIVE_JK = Set.of(
            "JkWorkspaceImportTest#test_jk_workspace_imports_with_roots_libraries_sdks_and_no_iml",
            "JkDebugBreakpointTest#test_a_breakpoint_in_a_jk_run_test_stops_the_debugger");

    @TestFactory
    Stream<DynamicTest> ide_hosted_suites() {
        return tests(id -> !DRIVE_JK.contains(id));
    }

    /** One dynamic test per {@code test*} method of every suite whose {@code Suite#method} id passes. */
    static Stream<DynamicTest> tests(Predicate<String> selected) {
        return SUITES.stream().flatMap(suite -> testMethods(suite)
                .map(method -> suite.getSimpleName() + "#" + method)
                .filter(selected)
                .map(id -> dynamicTest(id, () -> run(suite, id.substring(id.indexOf('#') + 1)))));
    }

    /** Every id in {@link #DRIVE_JK} that names no test method. */
    static List<String> unknownDriveJk() {
        Set<String> all = SUITES.stream()
                .flatMap(suite -> testMethods(suite).map(method -> suite.getSimpleName() + "#" + method))
                .collect(Collectors.toSet());
        return DRIVE_JK.stream().filter(id -> !all.contains(id)).sorted().toList();
    }

    private static Stream<String> testMethods(Class<? extends TestCase> suite) {
        return Arrays.stream(suite.getMethods())
                .filter(m -> m.getName().startsWith("test") && m.getParameterCount() == 0)
                .filter(m -> !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class)
                .map(Method::getName)
                .sorted(Comparator.naturalOrder());
    }

    private static void run(Class<? extends TestCase> suite, String method) throws Throwable {
        TestCase test = suite.getDeclaredConstructor().newInstance();
        test.setName(method);
        test.runBare();
    }
}
