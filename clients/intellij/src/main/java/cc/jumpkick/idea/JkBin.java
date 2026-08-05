// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.io.File;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Resolves the {@code jk} binary for wire-only plugin actions. */
public final class JkBin {

    private JkBin() {}

    /**
     * Resolution order: {@code JK_BIN} env, IntelliJ application property {@code jk.bin}, then
     * bare {@code jk} on PATH.
     */
    public static @NotNull String path() {
        String env = System.getenv("JK_BIN");
        if (env != null && !env.isBlank()) return env.trim();
        String prop = System.getProperty("jk.bin");
        if (prop != null && !prop.isBlank()) return prop.trim();
        return "jk";
    }

    /** True when a project base has a JumpKick manifest. */
    public static boolean isJumpKickRoot(@Nullable File base) {
        return base != null && new File(base, "jk.toml").isFile();
    }
}
