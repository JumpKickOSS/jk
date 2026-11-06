// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.jdk.JdkSelector;
import cc.jumpkick.model.Project;
import java.util.Locale;

/**
 * Parses {@code jk new --jdk}: the feature major and the pin {@code jk.toml} gets.
 */
final class NewJdkPlan {

    private NewJdkPlan() {}

    /** A resolved JDK: the feature {@code major} and the {@code pin} to write to {@code jk.toml}. */
    record Spec(int major, String pin) {}

    /**
     * Parse an explicit {@code --jdk} value (a concrete spec, not a keyword) into its {@link Spec}.
     * Accepts a bare major ({@code "25"}) or a vendor-hinted spec ({@code "corretto-25"}); the vendor
     * is preserved in the pin only in the latter case. Rejects a point release and a spec with no
     * major.
     *
     * @throws IllegalArgumentException with a user-facing message on a bad spec
     */
    static Spec parseExplicit(String raw) {
        String arg = raw.trim();
        if (Project.hasPointRelease(arg)) {
            throw new IllegalArgumentException("--jdk "
                    + arg
                    + " must not pin a point release — use \"<vendor>-<major>\" or \"<major>\" "
                    + "(e.g. \"temurin-25\" or \"25\"); jk keeps the patch current.");
        }
        JdkSelector.FlexibleQuery q = JdkSelector.parseFlexible(arg);
        int major = q.majorOpt().orElse(0);
        if (major == 0) {
            throw new IllegalArgumentException("--jdk "
                    + arg
                    + " must include a major version (e.g. \"temurin-25\", \"25\", or lts/stable/latest)");
        }
        if (!q.hints().isEmpty()) {
            String vendor = q.hints().get(0).toLowerCase(Locale.ROOT);
            return new Spec(major, vendor + "-" + major);
        }
        return new Spec(major, Integer.toString(major));
    }
}
