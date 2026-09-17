// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.TestLauncherFailure;
import java.util.List;
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

    @Test
    void a_jvm_refused_its_heap_names_the_refusal_and_the_flags_that_size_the_fork() {
        String out = "Error occurred during initialization of VM\n"
                + "Could not reserve enough space for 1048576000000 KB object heap";
        String message = TestLauncherReport.message(TestLauncherFailure.runner("g:app", 1, out), null);

        assertThat(message)
                .startsWith("test runner exited 1 before any test ran"
                        + " — Could not reserve enough space for 1048576000000 KB object heap");
        assertThat(message)
                .contains("Fix: the test runner JVM refused to start")
                .contains("`[test] jvm-args`")
                .doesNotContain("the runner's full output is above");
    }

    /** A fork that printed nothing is diagnosed from how it was started; one with last words is not repeated its argv. */
    @Test
    void a_fork_that_printed_nothing_names_the_command_it_was_started_with() {
        List<String> command =
                List.of("/jdk/bin/java", "-Xmx256m", "-cp", "/lib/a.jar", "cc.jumpkick.plugin.Worker", "--list-only");

        String silent = TestLauncherReport.message(TestLauncherFailure.discovery("g:app", 1, "", command), null);
        assertThat(silent)
                .startsWith("test discovery exited 1 before any test ran — the fork printed nothing\n"
                        + "command: /jdk/bin/java -Xmx256m -cp <1 entry> cc.jumpkick.plugin.Worker --list-only")
                .contains("Fix: the fork printed nothing and named no reason");

        String killed = TestLauncherReport.message(TestLauncherFailure.runner("g:app", 137, "", command), null);
        assertThat(killed)
                .contains("\ncommand: /jdk/bin/java -Xmx256m -cp <1 entry> cc.jumpkick.plugin.Worker --list-only")
                .contains("Fix: the test runner JVM was killed by SIGKILL");

        String spoke = TestLauncherReport.message(TestLauncherFailure.runner("g:app", 1, "a last line", command), null);
        assertThat(spoke).contains("the fork's last output:").doesNotContain("command: ");
        assertThat(TestLauncherReport.message(TestLauncherFailure.runner("g:app", 1, ""), null))
                .as("no command recorded, none shown")
                .doesNotContain("command: ");
    }

    @Test
    void a_signal_exit_names_the_signal_and_what_sends_it() {
        String message = TestLauncherReport.message(TestLauncherFailure.runner("g:app", 137, ""), null);

        assertThat(message)
                .startsWith("test runner exited 137 (SIGKILL) before any test ran — the fork printed nothing");
        assertThat(message)
                .contains("Fix: the test runner JVM was killed by SIGKILL before it ran a test")
                .contains("out-of-memory killer")
                .doesNotContain("the runner's full output is above");
    }
}
