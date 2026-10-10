// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * The suites that run inside a headless IDE, on the JUnit Platform. The platform's heavy test base
 * is a JUnit 3 {@link TestCase}; each {@code test*} method of each suite here is one dynamic test,
 * run through {@link TestCase#runBare()} so the base's own setUp, EDT dispatch and tearDown apply.
 * Integration tier: every suite starts an IDE, and two drive the jk on PATH.
 */
@Tag("integration")
class IdeHostedTest {

    private static final List<Class<? extends TestCase>> SUITES =
            List.of(JkManifestWatcherVfsTest.class, JkWorkspaceImportTest.class, JkDebugBreakpointTest.class);

    @TestFactory
    Stream<DynamicTest> ide_hosted_suites() {
        return SUITES.stream().flatMap(suite -> testMethods(suite)
                .map(method -> dynamicTest(suite.getSimpleName() + "#" + method, () -> run(suite, method))));
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
