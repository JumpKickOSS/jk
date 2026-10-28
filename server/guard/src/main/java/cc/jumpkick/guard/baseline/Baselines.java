// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import cc.jumpkick.config.EnvValues;
import java.util.Map;
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

    /**
     * Whether the run is a CI build: the client's shell says so ({@code CI} rides the request), or
     * the engine was started on a runner. The engine is a daemon, so its own environment alone is
     * whichever shell started it.
     */
    public static boolean ciMode(@Nullable Map<String, String> clientEnv) {
        return (clientEnv != null && ciMode(clientEnv::get)) || ciMode();
    }

    /** Refuse a freeze without a reason or under CI; the message names the sanctioned path. */
    public static @Nullable String freezeRefusal(@Nullable String reason, boolean ci) {
        if (ci) return "jk guard freeze is refused under CI: accept violations locally and commit the baseline";
        if (reason == null || reason.isBlank())
            return "jk guard freeze needs -W: an entry without a reason is a suppression";
        return null;
    }
}
