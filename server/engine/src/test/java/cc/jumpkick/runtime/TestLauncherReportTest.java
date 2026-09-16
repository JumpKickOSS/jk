// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.TestLauncherFailure;
import org.junit.jupiter.api.Test;

class TestLauncherReportTest {

    @Test
    void a_jvm_out_of_memory_names_the_error_the_root_cause_and_the_jvm_args_that_raise_the_limit() {
        String out = "java.lang.RuntimeException: io.quarkus.builder.BuildException: Build failure\n"
                + "\tat io.quarkus.builder.Execution.run(Execution.java:139)\n"
                + "Caused by: java.lang.IllegalStateException: A synthetic bean is already registered\n"
                + "Terminating due to java.lang.OutOfMemoryError: Metaspace";
        String message = TestLauncherReport.message(TestLauncherFailure.discovery("g:app", 3, out), null);

        assertThat(message)
                .startsWith("test discovery exited 3 before any test ran — java.lang.OutOfMemoryError: Metaspace\n"
                        + "caused by: java.lang.IllegalStateException: A synthetic bean is already registered");
        assertThat(message).contains("Fix: the test discovery JVM ran out of memory (Metaspace)");
        assertThat(message).contains("-XX:MaxMetaspaceSize=1g");
        assertThat(message).doesNotContain("the runner's full output is above");
    }

    @Test
    void a_framework_failure_the_runner_never_reported_is_named_by_its_root_cause() {
        String out = "com.acme.Boot: could not start\nCaused by: java.io.IOException: port 8080 in use";
        String message = TestLauncherReport.message(TestLauncherFailure.runner("g:app", 1, out), null);

        assertThat(message).startsWith("test runner exited 1 before any test ran — com.acme.Boot: could not start");
        assertThat(message)
                .contains("Fix: the runner did not get to report; the failure is the framework's own — "
                        + "`java.io.IOException: port 8080 in use`");
    }

    @Test
    void a_class_discovery_could_not_load_names_the_class_the_root_cause_and_the_frames() {
        String out =
                "jk-test-runner: test discovery failed: 1 class could not be loaded during discovery: com.acme.OrdersIT\n"
                        + "  under /ws/app/target/classes/test\n"
                        + "  class: com.acme.OrdersIT\n"
                        + "  caused by: java.lang.NoClassDefFoundError: com/acme/Base\n"
                        + "    at java.base/java.lang.ClassLoader.defineClass1(Native Method)\n"
                        + "    at java.base/java.lang.ClassLoader.defineClass(ClassLoader.java:1027)\n"
                        + "  caused by: java.lang.ClassNotFoundException: com.acme.Base\n"
                        + "    at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)\n";
        String message = TestLauncherReport.message(TestLauncherFailure.discovery("g:app", 70, out), null);

        assertThat(message)
                .startsWith("test discovery exited 70 before any test ran"
                        + " — 1 class could not be loaded during discovery: com.acme.OrdersIT\n"
                        + "caused by: java.lang.NoClassDefFoundError: com/acme/Base\n"
                        + "caused by: java.lang.ClassNotFoundException: com.acme.Base\n"
                        + "    at java.base/java.lang.ClassLoader.defineClass1(Native Method)\n"
                        + "    at java.base/java.lang.ClassLoader.defineClass(ClassLoader.java:1027)");
        assertThat(message)
                .contains("Fix: the test JVM could not load the class com.acme.OrdersIT"
                        + " — `java.lang.ClassNotFoundException: com.acme.Base`")
                .contains("@QuarkusTest")
                .doesNotContain("the failure is the framework's own");
    }

    @Test
    void a_jvm_that_printed_nothing_usable_keeps_the_generic_fix() {
        String message = TestLauncherReport.message(
                TestLauncherFailure.runner("g:app", 1, "Error: could not create the Java Virtual Machine"), null);
        assertThat(message)
                .contains("Fix: the runner's full output is above; rerun with --verbose for the live stream.");
    }
}
