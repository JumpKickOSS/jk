// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import java.nio.file.Path;

/** Path-flag hygiene for the wire-only client. */
public final class CliPaths {

    private CliPaths() {}

    /**
     * A user-supplied path flag, absolutized against the invocation cwd. Every path the CLI
     * forwards to the engine must be absolute — the engine's cwd is unrelated to the client's,
     * so a raw relative {@code --cache-dir}/{@code --cache-file} silently points the verb at
     * the wrong tree. Matches the guarantee {@code GlobalOptions.workingDir} makes
     * for {@code -C}.
     */
    public static Path abs(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }
}
