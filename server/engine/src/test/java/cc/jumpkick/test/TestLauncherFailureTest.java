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

    /**
     * The shape the runner prints when the Platform's scan dropped a class it could not load: the
     * header counts and names them, each {@code class:} line is followed by its cause chain, and
     * the first frames sit under each cause.
     */
    private static final String CLASS_NOT_LOADABLE = """
            jk-test-runner: test discovery failed: 1 class could not be loaded during discovery: com.acme.OrdersIT
              under /ws/app/target/classes/test
              class: com.acme.OrdersIT
              caused by: java.lang.ExceptionInInitializerError: null
                at com.acme.OrdersIT.boot(OrdersIT.java:31)
                at com.acme.OrdersIT.<clinit>(OrdersIT.java:12)
                at java.base/java.lang.Class.forName0(Native Method)
              caused by: java.lang.IllegalStateException: application bootstrap failed
                at com.acme.OrdersIT.boot(OrdersIT.java:31)
            """;

    @Test
    void a_class_discovery_could_not_load_is_named_with_its_causes_and_first_frames() {
        TestLauncherFailure f = TestLauncherFailure.discovery("com.acme:app", 70, CLASS_NOT_LOADABLE);

        assertThat(f.getMessage())
                .isEqualTo("test discovery exited 70 before any test ran"
                        + " — 1 class could not be loaded during discovery: com.acme.OrdersIT");
        assertThat(f.exceptionClass()).isEmpty();
        assertThat(f.droppedClasses()).containsExactly("com.acme.OrdersIT");
        assertThat(f.causes())
                .containsExactly(
                        "java.lang.ExceptionInInitializerError: null",
                        "java.lang.IllegalStateException: application bootstrap failed");
        assertThat(f.rootCause()).isEqualTo("java.lang.IllegalStateException: application bootstrap failed");
        assertThat(f.frames())
                .containsExactly(
                        "com.acme.OrdersIT.boot(OrdersIT.java:31)",
                        "com.acme.OrdersIT.<clinit>(OrdersIT.java:12)",
                        "java.base/java.lang.Class.forName0(Native Method)");
    }

    @Test
    void frames_belong_to_the_first_cause_not_to_a_trace_printed_after_it() {
        TestLauncherFailure f = TestLauncherFailure.discovery("com.example:app", 70, ENGINE_COULD_NOT_START);
        assertThat(f.droppedClasses()).isEmpty();
        assertThat(f.frames())
                .as("the cause line has no frames under it; the trailing trace is not its")
                .isEmpty();
    }

    /** A line the failure cannot classify is still the fork's last word, so it rides in the message. */
    @Test
    void a_jvm_that_never_reached_the_runner_has_an_exit_and_its_last_output() {
        TestLauncherFailure f = TestLauncherFailure.runner("m", 1, "Error: could not create the Java Virtual Machine");

        assertThat(f.getMessage())
                .isEqualTo("test runner exited 1 before any test ran — the fork's last output:\n"
                        + "    Error: could not create the Java Virtual Machine");
        assertThat(f.exceptionClass()).isEmpty();
        assertThat(f.headline()).isEmpty();
        assertThat(f.engineId()).isNull();
        assertThat(f.causes()).isEmpty();
        assertThat(f.signal()).isNull();
        assertThat(f.lastLines()).containsExactly("Error: could not create the Java Virtual Machine");
        assertThat(f.output()).contains("could not create");
    }

    /** HotSpot's two lines when it cannot reserve the heap it was asked for; the second one is the reason. */
    @Test
    void a_jvm_refused_its_heap_reservation_names_the_refusal() {
        String out = "Error occurred during initialization of VM\n"
                + "Could not reserve enough space for 1048576000000 KB object heap";
        TestLauncherFailure f = TestLauncherFailure.runner("guard root", 1, out);

        assertThat(f.getMessage())
                .isEqualTo("test runner exited 1 before any test ran"
                        + " — Could not reserve enough space for 1048576000000 KB object heap");
        assertThat(f.headline()).isEqualTo("Could not reserve enough space for 1048576000000 KB object heap");
        assertThat(f.jvmRefused()).isTrue();
        assertThat(f.exceptionClass()).isEmpty();
        assertThat(f.outOfMemory()).isFalse();
    }

    /** The launcher's own refusal of a flag: the specific line wins over the generic could-not-create line. */
    @Test
    void a_jvm_that_refused_a_flag_names_the_flag_line_not_the_generic_one() {
        String out = "Invalid initial heap size: -Xms4g -Xmx1g\n"
                + "Error: Could not create the Java Virtual Machine.\n"
                + "Error: A fatal exception has occurred. Program will exit.";
        TestLauncherFailure f = TestLauncherFailure.discovery("g:app", 1, out);

        assertThat(f.getMessage())
                .isEqualTo("test discovery exited 1 before any test ran — Invalid initial heap size: -Xms4g -Xmx1g");
        assertThat(f.jvmRefused()).isTrue();
    }

    /** HotSpot's native out-of-memory: the {@code #} report lines, the reason without its marker. */
    @Test
    void a_jvm_whose_native_allocation_failed_names_the_insufficient_memory_line() {
        String out = "OpenJDK 64-Bit Server VM warning: INFO: os::commit_memory(0x0000000600000000, 2147483648, 0)"
                + " failed; error='Not enough space' (errno=12)\n"
                + "#\n"
                + "# There is insufficient memory for the Java Runtime Environment to continue.\n"
                + "# Native memory allocation (mmap) failed to map 2147483648 bytes. Error detail: G1 virtual space\n"
                + "# An error report file with more information is saved as:\n"
                + "# /ws/app/hs_err_pid4242.log";
        TestLauncherFailure f = TestLauncherFailure.runner("g:app", 1, out);

        assertThat(f.getMessage())
                .isEqualTo("test runner exited 1 before any test ran"
                        + " — There is insufficient memory for the Java Runtime Environment to continue.");
        assertThat(f.jvmRefused()).isTrue();
    }

    /** A fork the kernel or a neighbour killed: the exit is 128 plus the signal, and the signal is named. */
    @Test
    void a_signal_exit_with_nothing_printed_names_the_signal_and_the_silence() {
        TestLauncherFailure f = TestLauncherFailure.runner("guard root", 137, "");

        assertThat(f.getMessage())
                .isEqualTo("test runner exited 137 (SIGKILL) before any test ran — the fork printed nothing");
        assertThat(f.signal()).isEqualTo("SIGKILL");
        assertThat(f.lastLines()).isEmpty();
        assertThat(f.jvmRefused()).isFalse();
        assertThat(TestLauncherFailure.runner("m", 134, "").signal()).isEqualTo("SIGABRT");
        assertThat(TestLauncherFailure.runner("m", 139, "").signal()).isEqualTo("SIGSEGV");
        assertThat(TestLauncherFailure.runner("m", 143, "").signal()).isEqualTo("SIGTERM");
        assertThat(TestLauncherFailure.runner("m", 128 + 27, "").signal()).isEqualTo("signal 27");
        assertThat(TestLauncherFailure.runner("m", 1, "").signal()).isNull();
        assertThat(TestLauncherFailure.runner("m", 1, "").getMessage())
                .isEqualTo("test runner exited 1 before any test ran — the fork printed nothing");
    }

    /** Output nothing classifies: the last lines, blank ones dropped, at most {@link TestLauncherFailure#LAST_LINES}. */
    @Test
    void unclassified_output_rides_along_as_the_forks_last_lines() {
        String out =
                "Picked up JAVA_TOOL_OPTIONS: -Dfoo=bar\n\nline two\n\nline three\nline four\nline five\nline six\nline seven";
        TestLauncherFailure f = TestLauncherFailure.runner("m", 2, out);

        assertThat(f.lastLines()).containsExactly("line three", "line four", "line five", "line six", "line seven");
        assertThat(f.getMessage())
                .isEqualTo("test runner exited 2 before any test ran — the fork's last output:\n"
                        + "    line three\n    line four\n    line five\n    line six\n    line seven");
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
