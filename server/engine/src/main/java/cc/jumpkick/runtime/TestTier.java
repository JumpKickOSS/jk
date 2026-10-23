// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.SlowestTests;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The tier one module's suite runs as, and the fast-tier method budget it is held to.
 *
 * @param name {@link SlowestTests#FAST_TIER} when the run includes no tag, the profile's name when
 *     one selected the included tags, else the tags joined with {@code +}; {@code all} for {@code
 *     --all}; empty when unknown
 * @param budgetMs {@code [test] fast-method-budget} in milliseconds when this is the fast tier, else 0
 */
record TestTier(String name, long budgetMs) {

    static final TestTier UNKNOWN = new TestTier("", 0);

    /** {@code --all}: every suite, no tag filter. */
    static final String ALL = "all";

    /** The tier {@code sel} runs under {@code profile}, with {@code budgetMs} kept only for the fast tier. */
    static TestTier of(TestSelection sel, @Nullable String profile, long budgetMs) {
        String name = name(sel.includeTags(), sel.excludeTags(), sel.allSuites(), profile);
        return new TestTier(name, SlowestTests.FAST_TIER.equals(name) ? budgetMs : 0);
    }

    static String name(
            List<String> includeTags, List<String> excludeTags, boolean allSuites, @Nullable String profile) {
        if (includeTags.isEmpty()) {
            return allSuites && excludeTags.isEmpty() ? ALL : SlowestTests.FAST_TIER;
        }
        if (profile != null && !profile.isBlank()) return profile;
        return String.join("+", includeTags);
    }

    /**
     * How many budgets a fast-tier method may run before it is stopped: over the budget is a warning,
     * since a loaded host slows every method; this far over is a hang or a test in the wrong tier.
     */
    static final int STOP_AT_BUDGETS = 4;

    /**
     * The JUnit Jupiter default timeout that stops a fast-tier method at {@link #STOP_AT_BUDGETS}
     * budgets, as system properties for the test JVM; none without a budget. The test thread is
     * interrupted and the method fails; an explicit {@code @Timeout} keeps its own.
     */
    List<String> timeoutArgs() {
        if (budgetMs <= 0) return List.of();
        return List.of("-Djunit.jupiter.execution.timeout.testable.method.default=" + stopMs() + " ms");
    }

    /**
     * The flags a suite fork is launched with beyond jk's heap and the module path: {@code
     * testJvmArgs}, then this tier's per-method timeout. The run-tests stamp keys this list and the
     * fork launches it, so neither can carry a flag the other lacks.
     */
    List<String> suiteJvmArgs(List<String> testJvmArgs) {
        List<String> timeouts = timeoutArgs();
        if (timeouts.isEmpty()) return testJvmArgs;
        List<String> all = new ArrayList<>(testJvmArgs);
        all.addAll(timeouts);
        return List.copyOf(all);
    }

    /** When a fast-tier method is stopped, in milliseconds; 0 without a budget. */
    long stopMs() {
        return budgetMs <= 0 ? 0 : budgetMs * STOP_AT_BUDGETS;
    }

    /** Whether a test of {@code millis} is over this tier's budget. */
    boolean overBudget(long millis) {
        return budgetMs > 0 && millis > budgetMs;
    }
}
