// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * How a {@code SpringApplicationAotProcessor} run that exited non-zero ended, read off its output.
 * The processor does two things in turn: it starts the application context in AOT mode ({@code
 * prepareApplicationContext}, then {@code refreshForAotProcessing}), and it generates code from
 * the refreshed context ({@code performAotProcessing}). A failure under the first is the
 * application's own startup failing — a bean that cannot be created, a library that needs a JVM
 * flag — and would fail a plain {@code java -jar} the same way; a failure under the second is the
 * processor itself crashing on a context that did start. The two need different fixes, so the
 * step's message says which happened, names the root cause first and describes the invocation,
 * before the tail of the output.
 */
record AotFailure(Kind kind, String headline, String rootCause) {

    enum Kind {
        /** The application context failed to start under AOT processing. */
        APPLICATION,
        /** The processor crashed after the context started, or could not run at all. */
        PROCESSOR,
        /** The output names no exception the processor can be blamed for. */
        UNKNOWN
    }

    private static final String REFRESH_FRAME = "refreshForAotProcessing";
    private static final String PREPARE_FRAME = "ContextAotProcessor.prepareApplicationContext";
    private static final String APPLICATION_RUN_FRAME = "org.springframework.boot.SpringApplication.run";
    private static final List<String> PROCESSOR_FRAMES = List.of(
            "ContextAotProcessor.performAotProcessing",
            "ApplicationContextAotGenerator.processAheadOfTime",
            "org.springframework.aot.generate.",
            "org.springframework.beans.factory.aot.",
            "org.springframework.context.aot.",
            "SpringApplicationAotProcessor.main");
    private static final String MAIN_CLASS_MISSING = "Could not find or load main class";
    private static final Pattern EXCEPTION_LINE = Pattern.compile(
            "^(?:Exception in thread \"[^\"]*\" )?((?:[A-Za-z_$][\\w$]*\\.)+[A-Z][\\w$]*)(?::\\s*(.*))?$");

    /** Lines of the output the message keeps: the cause is at the bottom of a refresh stack. */
    static final int TAIL_LINES = 40;

    static AotFailure of(String output) {
        String[] lines = output.split("\n");
        String headline = "";
        String lastCause = "";
        boolean refreshing = false;
        boolean generating = false;
        boolean mainMissing = false;
        for (String raw : lines) {
            String t = raw.strip();
            if (t.contains(MAIN_CLASS_MISSING)) mainMissing = true;
            if (t.startsWith("at ")) {
                if (t.contains(REFRESH_FRAME) || t.contains(PREPARE_FRAME) || t.contains(APPLICATION_RUN_FRAME)) {
                    refreshing = true;
                } else if (PROCESSOR_FRAMES.stream().anyMatch(t::contains)) {
                    generating = true;
                }
                continue;
            }
            if (t.startsWith("Caused by: ")) {
                lastCause = t.substring("Caused by: ".length()).strip();
                continue;
            }
            if (headline.isEmpty() && EXCEPTION_LINE.matcher(t).matches()) {
                headline = t.startsWith("Exception in thread")
                        ? t.substring(t.indexOf("\" ") + 2).strip()
                        : t;
            }
        }
        String root = !lastCause.isEmpty() ? lastCause : headline;
        Kind kind;
        if (mainMissing) {
            kind = Kind.PROCESSOR;
            if (root.isEmpty()) root = "the processor class is not on the module's runtime classpath";
        } else if (refreshing) {
            kind = Kind.APPLICATION;
        } else if (generating) {
            kind = Kind.PROCESSOR;
        } else {
            kind = Kind.UNKNOWN;
        }
        return new AotFailure(kind, headline, root);
    }

    /** What the step reports: the verdict, the root cause, the invocation, the fix, then the output's tail. */
    static String message(int exit, String output, Invocation run) {
        AotFailure f = of(output);
        StringBuilder sb = new StringBuilder("Spring AOT processing failed (exit ")
                .append(exit)
                .append("): ")
                .append(f.verdict());
        if (!f.rootCause().isEmpty()) sb.append("\n  root cause: ").append(f.rootCause());
        sb.append("\n  processor: ")
                .append(run.processor())
                .append(run.jvmArgs().isEmpty() ? "" : " with JVM args " + run.jvmArgs())
                .append("\n  application: ")
                .append(run.applicationClass())
                .append("\n  classpath: ")
                .append(run.classpathEntries())
                .append(" entries (the module's classes and its runtime closure)")
                .append("\n  ")
                .append(f.fix());
        sb.append("\n  --- last lines of the processor's output ---\n").append(tail(output));
        return sb.toString();
    }

    /** The one-line verdict after the exit. */
    String verdict() {
        return switch (kind) {
            case APPLICATION -> "the application failed to start under AOT processing";
            case PROCESSOR -> "the Spring AOT processor itself crashed";
            case UNKNOWN -> "the processor exited before it reported why";
        };
    }

    /** The repair the verdict points at. */
    String fix() {
        return switch (kind) {
            case APPLICATION ->
                "Fix: the application's own startup failed inside the AOT context refresh, so it"
                        + " fails the same way at run time unless its JVM carries a flag this run lacked;"
                        + " `[spring-boot] aot-jvm-args = [\"--add-opens=java.base/java.lang=ALL-UNNAMED\"]` hands"
                        + " JVM flags to the processor, `[spring-boot] aot-args` hands application arguments."
                        + " `[spring-boot] aot = false` packages the Boot jar without AOT.";
            case PROCESSOR ->
                "Fix: the application context started; the processor failed generating code from it"
                        + " — a Spring AOT limitation on one of the module's bean definitions, or a processor missing"
                        + " from this Boot line. `[spring-boot] aot = false` packages the Boot jar without AOT; a"
                        + " `[native]` image still needs the processor's output.";
            case UNKNOWN ->
                "Fix: the output above is the processor's whole account; `[spring-boot] aot-jvm-args`"
                        + " reaches its JVM, and `[spring-boot] aot = false` packages the Boot jar without AOT.";
        };
    }

    /** The last {@link #TAIL_LINES} of the output, each indented under the message. */
    static String tail(String output) {
        String[] lines = output.split("\n");
        int from = Math.max(0, lines.length - TAIL_LINES);
        List<String> kept = new ArrayList<>();
        for (String line : Arrays.copyOfRange(lines, from, lines.length)) kept.add("  " + line);
        return String.join("\n", kept);
    }

    /** What the step forked: the processor's main class, its JVM flags, the application class, the classpath's size. */
    record Invocation(String processor, List<String> jvmArgs, String applicationClass, int classpathEntries) {}
}
