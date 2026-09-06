// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import cc.jumpkick.config.EnvValues;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Policy around the file: who may write it and when. */
public final class Baselines {

    /** What a red CI build says instead of writing. */
    public static final String CI_MESSAGE =
            "the baseline would tighten but this is a CI build; run `jk build` locally and commit jk-guards-baseline.toml";

    private Baselines() {}

    /**
     * Whether this process is a CI build, in which case the engine never writes the baseline and a
     * loose one is red. {@code CI} is the variable every hosted runner sets; {@code false} opts out.
     */
    public static boolean ciMode(Function<String, @Nullable String> env) {
        return EnvValues.isCi(env);
    }

    public static boolean ciMode() {
        return ciMode(System::getenv);
    }

    /** Refuse a freeze without a reason or under CI; the message names the sanctioned path. */
    public static @Nullable String freezeRefusal(@Nullable String reason, boolean ci) {
        if (ci) return "jk guard freeze is refused under CI: accept violations locally and commit the baseline";
        if (reason == null || reason.isBlank())
            return "jk guard freeze needs --reason: an entry without a reason is a suppression";
        return null;
    }
}
