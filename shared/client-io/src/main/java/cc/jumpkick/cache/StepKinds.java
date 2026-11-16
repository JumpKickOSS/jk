// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.run.TaskNames;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The step kind an action record names on its {@code KIND} line: an open, namespaced string, the
 * producer's namespace and its step ({@code java:compile}, {@code kotlin:compile}, {@code
 * java:run-tests}, {@code package:jar}, {@code node:build}). A store indexes it without knowing the
 * set, so a language or an intercepted tool added later brings its own namespace and changes no
 * reader; what a second write under one key means is the record's {@code WRITES} line, never the
 * kind.
 *
 * <p>This jk names the kinds of the records it writes from the step's task id, and knows the
 * namespaces those use ({@link #NAMESPACES}). A record of a kind this jk does not know is read and
 * carried like any other, but never leaves the machine: only a kind whose replay rule this jk
 * knows is shared ({@link ActionRecordText#shareable}).
 */
public final class StepKinds {

    private StepKinds() {}

    /** The namespaces of the kinds this jk writes. */
    public static final Set<String> NAMESPACES =
            Set.of("java", "kotlin", "groovy", "scala", "node", "package", "image", "guard", "jk");

    private static final Pattern SHAPE = Pattern.compile("[a-z][a-z0-9-]*:[a-z0-9][a-z0-9.-]*");

    /** Task bases whose kind is not their namespace and step read off the base: a base and its kind. */
    private static final List<String[]> NAMED = List.of(
            new String[] {TaskNames.COMPILE_JAVA, "java:compile"},
            new String[] {TaskNames.COMPILE_JAVA_INCREMENTAL, "java:compile"},
            new String[] {TaskNames.COMPILE_MAIN, "java:compile"},
            new String[] {TaskNames.COMPILE_TEST, "java:compile-test"},
            new String[] {TaskNames.COMPILE_TEST_FIXTURES, "java:compile-test-fixtures"},
            new String[] {TaskNames.COMPILE_KOTLIN, "kotlin:compile"},
            new String[] {TaskNames.COMPILE_TEST_KOTLIN, "kotlin:compile-test"},
            new String[] {TaskNames.COMPILE_GROOVY, "groovy:compile"},
            new String[] {TaskNames.COMPILE_TEST_GROOVY, "groovy:compile-test"},
            new String[] {TaskNames.RUN_TESTS, "java:run-tests"},
            new String[] {TaskNames.NATIVE_IMAGE, "package:native-image"},
            new String[] {TaskNames.WRITE_IMAGE, "image:write"},
            new String[] {TaskNames.IMAGE_PLAN, "image:plan"});

    /**
     * The kind of a record stored for task {@code taskId} ({@code <base>@<tag>}, or a bare name):
     * a named base's kind, {@code package:<rest>} for a {@code package-*} step, {@code
     * node:<rest>} for a {@code node-*} step, {@code guard:<rest>} for a guard, and {@code
     * jk:<base>} for every other step jk runs.
     */
    public static String of(String taskId) {
        int at = taskId.indexOf('@');
        String base = word(at >= 0 ? taskId.substring(0, at) : taskId);
        for (String[] named : NAMED) {
            if (named[0].equals(base)) return named[1];
        }
        if (base.startsWith("package-")) return "package:" + base.substring("package-".length());
        if (base.startsWith("node-")) return "node:" + base.substring("node-".length());
        if (base.equals(TaskNames.GUARD)) return "guard:check";
        if (base.startsWith("guard-")) return "guard:" + base.substring("guard-".length());
        return "jk:" + base;
    }

    /** Whether {@code kind} is a well-formed kind in a namespace this jk writes. */
    public static boolean known(@Nullable String kind) {
        if (kind == null || !SHAPE.matcher(kind).matches()) return false;
        return NAMESPACES.contains(kind.substring(0, kind.indexOf(':')));
    }

    /** {@code base} as a step word: lower case, anything outside {@code [a-z0-9.-]} a hyphen, never empty. */
    private static String word(String base) {
        StringBuilder out = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = Character.toLowerCase(base.charAt(i));
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' ? c : '-');
        }
        while (out.length() > 0 && out.charAt(0) == '-') out.deleteCharAt(0);
        return out.isEmpty() ? "step" : out.toString();
    }
}
