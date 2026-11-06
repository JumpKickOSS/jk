// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import cc.jumpkick.docs.AgentsGuide;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What jk adds to a standalone project a template wrote, before the first lock and code graph. Shared
 * by {@code jk new}, MCP {@code new} and {@code POST /api/projects}, which all scaffold through
 * the engine's Giter8 apply.
 */
public final class NewProjectSeed {

    private NewProjectSeed() {}

    /**
     * What every standalone scaffold gets from a template: a
     * seeded {@code .gitignore} unless one is already there, and jk's section of {@code AGENTS.md}.
     * A workspace module gets neither; the workspace root owns both.
     */
    public static void finish(Path dir) throws IOException {
        writeGitignore(dir);
        AgentsGuide.sync(dir);
    }

    /**
     * Seed a {@code .gitignore} covering jk's outputs. Don't clobber an existing file — the user (or
     * their template) may have customised it. We only create one on first scaffold.
     */
    private static void writeGitignore(Path dir) throws IOException {
        Path gitignore = dir.resolve(".gitignore");
        if (Files.exists(gitignore)) return;
        Files.writeString(gitignore, """
                # jk build outputs (`.jk/` is source and checked in)
                target/

                # IntelliJ IDEA
                .idea/
                *.iml
                *.ipr
                *.iws
                out/

                # VS Code
                .vscode/
                *.code-workspace
                .history/

                # Eclipse compiler (used by VS Code Java plugins)
                .classpath
                .project
                .settings/
                .factorypath
                **/src/**/bin/
                **/test/**/bin/
                """, StandardCharsets.UTF_8);
    }
}
