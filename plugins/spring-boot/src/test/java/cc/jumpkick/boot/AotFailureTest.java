// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The step's account of a processor run that exited non-zero: which half failed, the root cause
 * first, the invocation, the fix, then the output's tail.
 */
class AotFailureTest {

    /** A library the application boots with needs an --add-opens; the failure is inside the AOT refresh. */
    private static final String APPLICATION_REFRESH_FAILED = """
            Exception in thread "main" org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'apolloConfig': Failed to instantiate
            \tat org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:539)
            \tat org.springframework.context.support.AbstractApplicationContext.refreshForAotProcessing(AbstractApplicationContext.java:640)
            \tat org.springframework.context.aot.ApplicationContextAotGenerator.lambda$processAheadOfTime$0(ApplicationContextAotGenerator.java:58)
            \tat org.springframework.context.aot.ApplicationContextAotGenerator.withCglibClassHandler(ApplicationContextAotGenerator.java:67)
            \tat org.springframework.context.aot.ApplicationContextAotGenerator.processAheadOfTime(ApplicationContextAotGenerator.java:53)
            \tat org.springframework.context.aot.ContextAotProcessor.performAotProcessing(ContextAotProcessor.java:106)
            \tat org.springframework.context.aot.ContextAotProcessor.doProcess(ContextAotProcessor.java:84)
            \tat org.springframework.boot.SpringApplicationAotProcessor.main(SpringApplicationAotProcessor.java:82)
            Caused by: java.lang.ExceptionInInitializerError
            \tat com.google.inject.internal.cglib.reflect.$FastClass$Generator.create(FastClass.java:65)
            \tat com.ctrip.framework.apollo.spring.util.SpringInjector.getInjector(SpringInjector.java:22)
            \t... 29 more
            Caused by: java.lang.reflect.InaccessibleObjectException: Unable to make protected final java.lang.Class java.lang.ClassLoader.defineClass(java.lang.String,byte[],int,int,java.security.ProtectionDomain) throws java.lang.ClassFormatError accessible: module java.base does not "opens java.lang" to unnamed module @1b083826
            \tat java.base/java.lang.reflect.AccessibleObject.throwInaccessibleObjectException(AccessibleObject.java:353)
            \tat com.google.inject.internal.cglib.core.$ReflectUtils.<clinit>(ReflectUtils.java:42)
            \t... 61 more
            """;

    /** The context started; code generation choked on a bean definition. */
    private static final String GENERATION_FAILED = """
            Exception in thread "main" java.lang.IllegalStateException: Failed to generate code for bean with name 'legacyFactory'
            \tat org.springframework.beans.factory.aot.BeanDefinitionMethodGenerator.generateBeanDefinitionMethod(BeanDefinitionMethodGenerator.java:105)
            \tat org.springframework.beans.factory.aot.BeanRegistrationsAotContribution.applyTo(BeanRegistrationsAotContribution.java:78)
            \tat org.springframework.context.aot.ApplicationContextAotGenerator.processAheadOfTime(ApplicationContextAotGenerator.java:61)
            \tat org.springframework.context.aot.ContextAotProcessor.performAotProcessing(ContextAotProcessor.java:106)
            \tat org.springframework.boot.SpringApplicationAotProcessor.main(SpringApplicationAotProcessor.java:82)
            Caused by: java.lang.UnsupportedOperationException: instance supplier without a factory method
            \tat org.springframework.beans.factory.aot.InstanceSupplierCodeGenerator.generateCode(InstanceSupplierCodeGenerator.java:120)
            """;

    private static final AotFailure.Invocation RUN = new AotFailure.Invocation(
            "org.springframework.boot.SpringApplicationAotProcessor", List.of(), "com.acme.Application", 143);

    @Test
    void a_failure_inside_the_aot_refresh_is_the_application_failing_to_start() {
        AotFailure f = AotFailure.of(APPLICATION_REFRESH_FAILED);

        assertThat(f.kind()).isEqualTo(AotFailure.Kind.APPLICATION);
        assertThat(f.headline())
                .startsWith("org.springframework.beans.factory.BeanCreationException: Error creating bean with name");
        assertThat(f.rootCause())
                .startsWith("java.lang.reflect.InaccessibleObjectException: Unable to make protected final")
                .contains("does not \"opens java.lang\"");
    }

    @Test
    void a_failure_after_the_refresh_is_the_processor_crashing() {
        AotFailure f = AotFailure.of(GENERATION_FAILED);

        assertThat(f.kind()).isEqualTo(AotFailure.Kind.PROCESSOR);
        assertThat(f.rootCause())
                .isEqualTo("java.lang.UnsupportedOperationException: instance supplier without a factory method");
    }

    @Test
    void a_processor_missing_from_the_classpath_is_the_processor_not_the_application() {
        AotFailure f = AotFailure.of(
                "Error: Could not find or load main class org.springframework.boot.SpringApplicationAotProcessor\n"
                        + "Caused by: java.lang.ClassNotFoundException: org.springframework.boot.SpringApplicationAotProcessor\n");

        assertThat(f.kind()).isEqualTo(AotFailure.Kind.PROCESSOR);
        assertThat(f.rootCause()).startsWith("java.lang.ClassNotFoundException");
    }

    @Test
    void output_without_an_exception_is_unknown_and_still_gets_the_escape_hatch() {
        AotFailure f = AotFailure.of("Killed\n");

        assertThat(f.kind()).isEqualTo(AotFailure.Kind.UNKNOWN);
        assertThat(f.rootCause()).isEmpty();
        assertThat(f.fix()).contains("aot = false");
    }

    @Test
    void the_message_puts_the_verdict_and_root_cause_before_the_invocation_and_the_tail() {
        String message = AotFailure.message(1, APPLICATION_REFRESH_FAILED, RUN);

        int verdict = message.indexOf("the application failed to start under AOT processing");
        int root = message.indexOf("root cause: java.lang.reflect.InaccessibleObjectException");
        int processor = message.indexOf("processor: org.springframework.boot.SpringApplicationAotProcessor");
        int application = message.indexOf("application: com.acme.Application");
        int classpath = message.indexOf("classpath: 143 entries");
        int fix = message.indexOf("Fix: the application's own startup failed inside the AOT context refresh");
        int tail = message.indexOf("--- last lines of the processor's output ---");
        assertThat(message).startsWith("Spring AOT processing failed (exit 1): the application failed to start");
        assertThat(List.of(verdict, root, processor, application, classpath, fix, tail))
                .doesNotContain(-1)
                .isSorted();
        assertThat(message).contains("aot-jvm-args = [\"--add-opens=java.base/java.lang=ALL-UNNAMED\"]");
        assertThat(message).contains("at com.ctrip.framework.apollo.spring.util.SpringInjector.getInjector");
    }

    @Test
    void jvm_args_the_step_passed_are_part_of_the_invocation() {
        String message = AotFailure.message(
                1,
                GENERATION_FAILED,
                new AotFailure.Invocation(
                        "org.springframework.boot.SpringApplicationAotProcessor",
                        List.of("-Xmx2g"),
                        "com.acme.Application",
                        12));

        assertThat(message)
                .contains("the Spring AOT processor itself crashed")
                .contains("processor: org.springframework.boot.SpringApplicationAotProcessor with JVM args [-Xmx2g]")
                .contains("Fix: the application context started; the processor failed generating code");
    }

    @Test
    void the_tail_keeps_the_last_forty_lines_indented() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) sb.append("line ").append(i).append('\n');

        String tail = AotFailure.tail(sb.toString());

        assertThat(tail).startsWith("  line 11\n").endsWith("  line 50").doesNotContain("line 10\n");
    }
}
