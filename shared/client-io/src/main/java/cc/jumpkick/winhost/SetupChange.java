// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One host change JumpKick recommends: what it does ({@code title}), why, and its effect. {@code id}
 * is stable across runs for the same change, so an answer given to one proposal applies to the
 * next; a root's id is derived from its path. {@code path} is the folder an exclusion names, or the
 * environment an install names ({@code wsl:Ubuntu-26.10}).
 */
public record SetupChange(
        String id,
        String title,
        String why,
        String effect,
        Kind kind,
        @Nullable String path) {

    /** What a change does, and whether it needs the elevated script. */
    public enum Kind {
        /** A Defender folder exclusion. */
        EXCLUDE(true),
        /** Windows' {@code LongPathsEnabled}. */
        LONG_PATHS(true),
        /** git's {@code core.longpaths} for this user. */
        GIT_LONG_PATHS(false),
        /** CurrentUser's PowerShell execution policy. */
        EXECUTION_POLICY(false),
        /** JumpKick installed into another environment, run by the {@link SetupEnvironment} that proposed it. */
        ENVIRONMENT(false);

        private final boolean elevated;

        Kind(boolean elevated) {
            this.elevated = elevated;
        }

        public boolean elevated() {
            return elevated;
        }
    }

    public static final String LONG_PATHS = "long-paths";
    public static final String GIT_LONG_PATHS = "git-long-paths";
    public static final String EXECUTION_POLICY = "powershell-policy";

    /** The id of the exclusion of {@code path}: the same for every spelling of one folder. */
    public static String excludeId(String path) {
        String digest = Hashing.sha256Hex(WinPaths.key(path).getBytes(StandardCharsets.UTF_8));
        return "exclude-" + digest.substring(0, 10);
    }

    /** The change as the decision form's item: {@code id}, {@code label}, {@code help}. */
    public Map<String, Object> item() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", title);
        m.put("help", why + " " + effect);
        return m;
    }
}
