// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Modules;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The test step after a rename runs a class outside the default suite with its suite named, as jk test needs. */
class EditedTestsTest {

    @Test
    void aDefaultSuiteClassNeedsNoSuite() {
        assertThat(EditedTests.next(Modules.NONE, List.of("src/test/java/demo/CalcTest.java"), true))
                .startsWith(
                        "Test the test classes it edited: build(kind=test, classes=CalcTest), or jk test -S CalcTest.");
    }

    @Test
    void anIntegrationSuiteClassNamesItsSuite() {
        assertThat(EditedTests.next(
                        Modules.NONE,
                        List.of("src/test/java/demo/CalcTest.java", "src/integration/java/demo/CalcIT.java"),
                        true))
                .startsWith("Test the test classes it edited: build(kind=test, suites=[integration,test],"
                        + " classes=CalcIT,CalcTest), or jk test -s integration -s test -S CalcIT -S CalcTest.");
    }

    @Test
    void aCompactModulesSuiteIsItsDirectory() {
        Modules modules = new Modules(List.of(new Modules.Module("core/", true)));
        assertThat(EditedTests.next(modules, List.of("core/integration/src/demo/CalcIT.java"), true))
                .startsWith("Test the test classes it edited: build(kind=test, modules=core, suites=[integration],"
                        + " classes=CalcIT), or jk test -m core -s integration -S CalcIT.");
    }
}
