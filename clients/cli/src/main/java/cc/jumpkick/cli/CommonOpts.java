// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.model.command.Opt;

/**
 * Shared CLI option definitions so help text stays consistent across commands.
 */
public final class CommonOpts {

    private CommonOpts() {}

    /**
     * Download / action cache (CAS) override. Default is {@code $JK_CACHE_DIR}, else {@code
     * $JK_HOME/cache} ({@code ~/.jk/cache}). Engine-hosted commands pass the resolved path on the
     * wire so the resident engine uses the same tree — no need to wipe {@code ~/.jk/cache} for cold
     * resolve tests.
     */
    public static Opt cacheDir() {
        return Opt.value(
                "<dir>",
                "Override the download/action cache (CAS). Default: $JK_CACHE_DIR or $JK_HOME/cache (~/.jk/cache).",
                "--cache-dir");
    }

    /** Hidden variant for internal / rarely-needed commands. */
    public static Opt cacheDirHidden() {
        return cacheDir().hide();
    }
}
