// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import cc.jumpkick.model.JkVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The JumpKick playbook printed by {@code jk manual} (and MCP {@code jk_manual} / {@code
 * jk://manual}). Also the short {@code AGENTS.md} body scaffolded into new projects.
 */
public final class JkManual {

    private static final String RESOURCE = "/cc/jumpkick/docs/jk-manual.md";

    /**
     * Seeded at the project root by {@code jk new} / {@code jk init} (standalone only). Not
     * overwritten when a template already shipped one.
     */
    public static final String AGENTS_MD = """
            # AGENTS.md

            This project uses **JumpKick** (`jk`) for its build — not Maven or Gradle.

            It is important that you view the manual by running:

            ```bash
            jk manual
            ```

            If the JumpKick engine MCP server is connected, call **`jk_manual`** (or read `jk://manual`)
            instead of shelling out.

            Do not add `pom.xml`, `build.gradle`, or `build.gradle.kts`. Do not run `mvn` or `./gradlew`
            for this project. The manifest is `jk.toml`; the lockfile is `jk-lock.toml` (commit it);
            outputs are under `target/`. After a build or test, triage failures by reading
            `target/jk-results.md` with your file/grep tools — that is faster than running `jk results`
            as a shell command.
            """;

    private JkManual() {}

    /**
     * Full playbook markdown, with the running JumpKick version substituted. Always LF, always ends
     * in a newline — a document consumed as bytes ({@code jk manual}, MCP), not terminal chrome.
     */
    public static String markdown() {
        String raw = load().replace("${jk.version}", JkVersion.VERSION);
        return raw.endsWith("\n") ? raw : raw + "\n";
    }

    /**
     * Write {@code AGENTS.md} if the path is missing. Existing files (template or user) win.
     *
     * @return {@code true} when a file was written
     */
    public static boolean ensureAgentsGuide(Path dir) throws IOException {
        Path file = dir.resolve("AGENTS.md");
        if (Files.exists(file)) return false;
        Files.writeString(file, AGENTS_MD, StandardCharsets.UTF_8);
        return true;
    }

    private static String load() {
        try (InputStream in = JkManual.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing playbook resource: " + RESOURCE);
            }
            // Always LF: a Windows checkout may have copied the resource with CRLF.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
