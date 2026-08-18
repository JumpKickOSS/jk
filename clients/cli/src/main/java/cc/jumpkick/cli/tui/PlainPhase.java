// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.run.BuildStage;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Status verbs for plain ({@code --no-ansi}) plan lines. Wire stage {@code test} becomes {@code
 * running tests}; unknown work is {@code prepare}.
 */
public final class PlainPhase {

    public static final String PREPARE = "prepare";
    public static final String START = "start";
    public static final String BUILT = "built";
    public static final String DONE = "done";
    public static final String INITIALIZING = "initializing...";
    public static final String RUNNING_TESTS = "running tests";

    private static final Pattern RUNNING_TESTS_COUNT = Pattern.compile("(?i)^running (\\d+) tests$");
    private static final Pattern COMPILE_SOURCES = Pattern.compile("(?i)^compiling (\\d+)\\b.*\\bsources$");

    private PlainPhase() {}

    /** Status for a wire phase or step key ({@code compile} / {@code compile-java} → {@code compiling}). */
    public static String status(String phaseOrStep) {
        if (phaseOrStep == null || phaseOrStep.isBlank()) return PREPARE;
        String key = phaseOrStep.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "resolve", "resolving" -> "resolving";
            case "generate", "generating" -> "generating";
            case "compile", "compiling" -> "compiling";
            case "test", "testing" -> RUNNING_TESTS;
            case "package", "packaging" -> "packaging";
            case "train", "training" -> "training";
            case "native" -> "native compiling";
            case "image" -> "building image";
            case "publish", "publishing" -> "publishing";
            case "checking" -> "checking";
            case "lock", "locking" -> "locking";
            case "graph" -> "resolving";
            case "prepare", "work" -> PREPARE;
            default -> status(BuildStage.fromWireExact(key).orElseGet(() -> BuildStage.ofTaskName(key)));
        };
    }

    public static String status(BuildStage stage) {
        if (stage == null) return PREPARE;
        return switch (stage) {
            case RESOLVE -> "resolving";
            case GENERATE -> "generating";
            case COMPILE -> "compiling";
            case TEST -> RUNNING_TESTS;
            case PACKAGE -> "packaging";
            case TRAIN -> "training";
            case NATIVE -> "native compiling";
            case IMAGE -> "building image";
            case PUBLISH -> "publishing";
            case OTHER -> PREPARE;
        };
    }

    /** {@code "g:a :: running 80 tests"} → {@code running 80 tests}. */
    public static String stripModulePrefix(String message) {
        if (message == null || message.isBlank()) return "";
        String s = message.trim();
        int sep = s.indexOf(" :: ");
        return sep > 0 ? s.substring(sep + 4).trim() : s;
    }

    /** {@code running 80 tests} when the label carries a remaining-test count. */
    public static Optional<Integer> runningTestsCount(String message) {
        String body = stripModulePrefix(message);
        Matcher m = RUNNING_TESTS_COUNT.matcher(body);
        if (!m.matches()) return Optional.empty();
        return Optional.of(Integer.parseInt(m.group(1)));
    }

    public static String runningTests(int remaining) {
        return "running " + Math.max(0, remaining) + " tests";
    }

    public static boolean isRunningTests(String status) {
        if (status == null || status.isBlank()) return false;
        return RUNNING_TESTS.equals(status)
                || RUNNING_TESTS_COUNT.matcher(status).matches();
    }

    /**
     * Compile source-count labels ({@code compiling 12 sources}, {@code compiling 12 Kotlin
     * sources}). Empty when the message is not a source count.
     */
    public static Optional<String> compileSourcesStatus(String message) {
        String body = stripModulePrefix(message);
        if (COMPILE_SOURCES.matcher(body).matches()) return Optional.of(body);
        return Optional.empty();
    }

    public static boolean isCompileSources(String status) {
        return status != null && COMPILE_SOURCES.matcher(status).matches();
    }

    /** True when two statuses are the same phase family (countdown ticks stay one phase). */
    public static boolean sameFamily(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.equals(b)) return true;
        if (isRunningTests(a) && isRunningTests(b)) return true;
        return isCompileSources(a) && isCompileSources(b);
    }
}
