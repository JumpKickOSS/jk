// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestLauncherFailureTest {

    private static final String ENGINE_COULD_NOT_START = """
            jk-test-runner: test discovery failed: org.junit.platform.commons.JUnitException: TestEngine with ID 'junit-jupiter' failed to discover tests
              under /ws/app/target/test-classes
              caused by: java.lang.NoSuchMethodError: 'void org.junit.jupiter.api.extension.ExtensionContext.publishReportEntry()'
            jk-test-runner: org.junit.platform.commons.JUnitException: TestEngine with ID 'junit-jupiter' failed to discover tests
            org.junit.platform.commons.JUnitException: TestEngine with ID 'junit-jupiter' failed to discover tests
            \tat org.junit.platform.launcher.core.EngineDiscoveryOrchestrator.discoverEngineRoot(...)
            """;

    @Test
    void reads_the_exception_the_engine_and_the_causes_off_the_runner_output() {
        TestLauncherFailure f = TestLauncherFailure.discovery("com.example:app", 70, ENGINE_COULD_NOT_START);

        assertThat(f.getMessage())
                .isEqualTo("test discovery exited 70 before any test ran"
                        + " — TestEngine with ID 'junit-jupiter' failed to discover tests");
        assertThat(f.exceptionClass()).isEqualTo("org.junit.platform.commons.JUnitException");
        assertThat(f.headline()).isEqualTo("TestEngine with ID 'junit-jupiter' failed to discover tests");
        assertThat(f.engineId()).isEqualTo("junit-jupiter");
        assertThat(f.causes())
                .containsExactly("java.lang.NoSuchMethodError:"
                        + " 'void org.junit.jupiter.api.extension.ExtensionContext.publishReportEntry()'");
        assertThat(f.moduleLabel()).isEqualTo("com.example:app");
        assertThat(f.exit()).isEqualTo(70);
    }

    @Test
    void a_jvm_that_never_reached_the_runner_has_an_exit_and_its_output_only() {
        TestLauncherFailure f = TestLauncherFailure.runner("m", 1, "Error: could not create the Java Virtual Machine");

        assertThat(f.getMessage()).isEqualTo("test runner exited 1 before any test ran");
        assertThat(f.exceptionClass()).isEmpty();
        assertThat(f.headline()).isEmpty();
        assertThat(f.engineId()).isNull();
        assertThat(f.causes()).isEmpty();
        assertThat(f.output()).contains("could not create");
    }

    @Test
    void the_launcher_missing_from_the_classpath_keeps_the_runner_sentence_as_headline() {
        String out = "jk-test-runner: the JUnit Platform Launcher is not on the test classpath — add"
                + " org.junit.platform:junit-platform-launcher (or re-run `jk lock`).";
        TestLauncherFailure f = TestLauncherFailure.runner("m", 2, out);

        assertThat(f.exceptionClass()).isEmpty();
        assertThat(f.headline()).startsWith("the JUnit Platform Launcher is not on the test classpath");
        assertThat(f.getMessage()).contains("exited 2").contains("Launcher is not on the test classpath");
    }
}
