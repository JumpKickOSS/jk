// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.model.BuildIdentity;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The engine code that picks which test classes a suite runs, builds the JVM that runs them and
 * reads their outcome. A run-tests stamp keys by its identity rather than the whole engine's: the
 * test runner, the JDK, the classpath, flags, environment and selection are keyed by their own
 * content, so an engine change outside this code replays every green suite, and one inside it runs
 * them again. The {@code suite-code-keyed} guard holds the engine's selection and launch types to
 * this set.
 */
public final class SuiteCode {

    private SuiteCode() {}

    private static final String ROOT = "cc/jumpkick/";
    private static final String PACKAGE = ROOT + "test/";
    private static final String CLASS = ".class";

    /** The words a class's own name carries when it belongs to the set, wherever its package. */
    private static final List<String> WORDS = List.of("Test", "Suite", "JUnit");

    private static volatile @Nullable String cachedId;
    private static volatile @Nullable String overrideId;

    /**
     * Whether the archive entry {@code entry} is in the set: a jk class of this package, or one
     * whose top-level class name carries {@code Test}, {@code Suite} or {@code JUnit}.
     */
    public static boolean contains(String entry) {
        if (!entry.startsWith(ROOT) || !entry.endsWith(CLASS)) return false;
        if (entry.startsWith(PACKAGE)) return true;
        String simple = entry.substring(entry.lastIndexOf('/') + 1, entry.length() - CLASS.length());
        int nested = simple.indexOf('$');
        String top = nested < 0 ? simple : simple.substring(0, nested);
        for (String word : WORDS) {
            if (top.contains(word)) return true;
        }
        return false;
    }

    /** The version string a run-tests stamp keys by ({@link BuildIdentity#keyVersion}). */
    public static String keyVersion() {
        return BuildIdentity.keyVersion(id());
    }

    /** The set's identity in the running archive, 12 hex, or {@code ""} when there is no archive. */
    static String id() {
        String override = overrideId;
        if (override != null) return override;
        String local = cachedId;
        if (local != null) return local;
        synchronized (SuiteCode.class) {
            if (cachedId == null) cachedId = BuildIdentity.codeIdentity(SuiteCode::contains);
            return cachedId;
        }
    }

    /** Pretend the set's code is the one {@code id} names; {@code null} returns to the derived id. */
    public static void overrideIdForTests(@Nullable String id) {
        overrideId = id;
    }
}
