// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.run.TestSummary;
import org.jspecify.annotations.Nullable;

/**
 * What a module's test step left: the suite's summary when tests ran or a green run replayed, or,
 * for a workspace module a {@code --class} selection matched nothing in, why.
 *
 * @param result the suite's summary; {@code null} when the module skipped
 * @param tagExcluded the runner's {@code tag-excluded} warning when the tag filter dropped a named
 *     class
 * @param suiteHint the suite a {@code --class} names instead, when an unselected suite owns it
 */
public record TestRun(
        @Nullable TestSummary result,
        @Nullable String tagExcluded,
        @Nullable String suiteHint) {

    /** A run, or a replayed one, with its summary. */
    public static TestRun of(TestSummary result) {
        return new TestRun(result, null, null);
    }
}
