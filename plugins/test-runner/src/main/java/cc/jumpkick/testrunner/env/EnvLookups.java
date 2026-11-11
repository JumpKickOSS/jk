// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Where a probed class's {@code System.getenv} calls land: each lookup is noted — the variable's
 * name, whether it had a value, the test class running and the class that asked — and passed on
 * unchanged. Values are never kept.
 *
 * <p>The two methods carry {@code System.getenv}'s exact names and descriptors: {@link EnvProbe}
 * repoints a class's reference to {@code java/lang/System} at this class and leaves the rest of
 * the call as compiled.
 */
public final class EnvLookups {

    /** The system property the test runner sets to the class whose tests are running. */
    public static final String CURRENT_TEST = "jk.test.class";

    /** How a lookup that found a value is written. */
    public static final String SET = "set";

    /** How a lookup that found nothing is written. */
    public static final String UNSET = "unset";

    /** The name a whole-map read is noted under. */
    public static final String WHOLE_MAP = "*";

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private EnvLookups() {}

    /** {@code System.getenv(name)}, noted. */
    public static @Nullable String getenv(String name) {
        String value = System.getenv(name);
        note(name, value != null);
        return value;
    }

    /** {@code System.getenv()}, noted as a read of every variable. */
    public static Map<String, String> getenv() {
        note(WHOLE_MAP, true);
        return System.getenv();
    }

    private static void note(String name, boolean set) {
        String test = System.getProperty(CURRENT_TEST, "");
        String caller = WALKER.walk(frames -> frames.skip(2)
                .findFirst()
                .map(f -> f.getDeclaringClass().getName())
                .orElse(""));
        SEEN.add(test + '\t' + caller + '\t' + name + '\t' + (set ? SET : UNSET));
    }

    /** Writes every lookup noted so far to {@code file}, one per line: an empty file says the probe ran and saw none. */
    static void writeTo(Path file) {
        try {
            Files.createDirectories(file.getParent());
            String lines = SEEN.isEmpty() ? "" : String.join("\n", SEEN) + "\n";
            Files.write(file, lines.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // a diagnostic the JVM could not write is a diagnostic not given, never a failed suite
        }
    }
}
