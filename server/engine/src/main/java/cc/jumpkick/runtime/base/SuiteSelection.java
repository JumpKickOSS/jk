// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.run.TaskNames;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Which tests a run's {@code run-tests} ran, as the key its timings are kept under.
 *
 * <p>A suite's wall is a property of the selection that produced it: the integration tier of a
 * module takes ten times what its default tier does, so a ledger that keeps one wall per module
 * prices every tier off a blend of all of them. The key is the selection's identity: the profile's
 * name or the included tags, the suites named outright, {@code all} for every suite. The default
 * selection has none, and its step keeps the bare {@link TaskNames#RUN_TESTS}; any other is {@code
 * run-tests@<key>} ({@link TaskNames#suiteStep}).
 *
 * <p>Excluded tags are not part of the key: they come from the module's {@code [test]} baseline in
 * the ordinary case, and a run that overrides them is a tag filter on the same tier.
 */
@NullMarked
public final class SuiteSelection {

    private SuiteSelection() {}

    /** The selection's key; empty for the default selection. */
    public static String key(TestSelection selection, @Nullable String profile) {
        List<String> parts = new ArrayList<>();
        if (selection.allSuites()) {
            parts.add("all");
        } else {
            if (!selection.suites().isEmpty() && !selection.suites().equals(List.of("test"))) {
                parts.add(String.join("-", selection.suites()));
            }
        }
        if (!selection.includeTags().isEmpty()) {
            String named = Profiles.named(profile);
            parts.add(named != null ? named : String.join("-", selection.includeTags()));
        }
        return sanitize(String.join("_", parts));
    }

    /** The metrics name of {@code run-tests} for a run with {@code selection} under {@code profile}. */
    public static String step(TestSelection selection, @Nullable String profile) {
        return TaskNames.suiteStep(key(selection, profile));
    }

    /** {@link #step} for the session the calling thread runs under. */
    public static String current() {
        Session session = SessionContext.current();
        return step(session.testSelection(), session.profile());
    }

    /**
     * Whether the run runs only some of the suite's classes ({@code --class}): what it measures is
     * those classes, not the suite, so it leaves no suite wall behind.
     */
    public static boolean partial(TestSelection selection) {
        return !selection.classes().isEmpty();
    }

    private static String sanitize(String key) {
        return key.replaceAll("[^A-Za-z0-9_-]+", "_");
    }
}
