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

    /**
     * The shape a Quarkus project leaves: the framework starts the application while classes are
     * still being listed, every start fails, the discovery JVM runs out of metaspace and HotSpot
     * exits 3 with its own last word on stdout. The runner never printed a header; the failure is
     * read off the JVM's line and the first trace's cause chain.
     */
    private static final String JVM_OUT_OF_METASPACE = """
            12:14:31.677 WARN  The "quarkus.native.resources.excludes" config property is deprecated.
            java.lang.RuntimeException: io.quarkus.builder.BuildException: Build failure: Build failed due to errors
            \t[error]: Build step io.quarkus.arc.deployment.ArcProcessor#registerSyntheticObservers threw an exception
            \tat io.quarkus.arc.processor.BeanDeployment.addSyntheticBean(BeanDeployment.java:1618)
            \tat io.quarkus.test.junit.classloading.FacadeClassLoader.loadClass(FacadeClassLoader.java:366)
            \t... 59 more
            Caused by: io.quarkus.builder.BuildException: Build failure: Build failed due to errors
            \tat io.quarkus.builder.Execution.run(Execution.java:139)
            Caused by: java.lang.IllegalStateException: A synthetic bean with identifier t7Ka is already registered: SYNTHETIC bean [types=[com.acme.EmulatorConfig, java.lang.Object]]
            \tat io.quarkus.arc.processor.BeanDeployment.addSyntheticBean(BeanDeployment.java:1618)
            java.lang.RuntimeException: io.quarkus.builder.BuildException: Build failure: Build failed due to errors
            Caused by: java.lang.IllegalStateException: a later start, same story
            Terminating due to java.lang.OutOfMemoryError: Metaspace
            """;

    @Test
    void a_jvm_that_ran_out_of_memory_names_the_error_and_the_first_traces_root_cause() {
        TestLauncherFailure f = TestLauncherFailure.discovery("g:app", 3, JVM_OUT_OF_METASPACE);

        assertThat(f.getMessage())
                .isEqualTo("test discovery exited 3 before any test ran — java.lang.OutOfMemoryError: Metaspace");
        assertThat(f.exceptionClass()).isEqualTo("java.lang.OutOfMemoryError");
        assertThat(f.headline()).isEqualTo("Metaspace");
        assertThat(f.outOfMemory()).isTrue();
        assertThat(f.engineId()).isNull();
        assertThat(f.causes())
                .containsExactly(
                        "io.quarkus.builder.BuildException: Build failure: Build failed due to errors",
                        "java.lang.IllegalStateException: A synthetic bean with identifier t7Ka is already registered:"
                                + " SYNTHETIC bean [types=[com.acme.EmulatorConfig, java.lang.Object]]");
        assertThat(f.rootCause()).startsWith("java.lang.IllegalStateException: A synthetic bean");
    }

    /** A framework trace with no runner header and no JVM exit line: the trace's first line is the failure. */
    @Test
    void a_bare_stack_trace_names_its_first_exception() {
        String out = "com.acme.Boot: could not start\n\tat com.acme.Boot.run(Boot.java:3)\n"
                + "Caused by: java.io.IOException: port 8080 in use\n\tat java.base/sun.nio.ch.Net.bind(Net.java:1)";
        TestLauncherFailure f = TestLauncherFailure.runner("g:app", 1, out);

        assertThat(f.getMessage())
                .isEqualTo("test runner exited 1 before any test ran — com.acme.Boot: could not start");
        assertThat(f.exceptionClass()).isEqualTo("com.acme.Boot");
        assertThat(f.outOfMemory()).isFalse();
        assertThat(f.rootCause()).isEqualTo("java.io.IOException: port 8080 in use");
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
