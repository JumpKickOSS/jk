// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The JumpKick skill: one {@code SKILL.md} plus one file per topic, printed by {@code jk skill} and
 * the MCP {@code skill} tool, and written by {@code jk skill install} under {@code .agents/skills/jk}.
 */
public final class JkSkill {

    /** Topic names, in reading order. Each is a sibling of {@code SKILL.md}. */
    public static final List<String> TOPICS = List.of(
            "dependencies",
            "tests",
            "workspaces",
            "lockfile",
            "imports",
            "plugins",
            "guards",
            "layout",
            "jdk",
            "engine");

    /**
     * Project-relative skills directory from the Agent Skills layout: {@code <project>/.agents/skills}.
     * Install writes {@code jk/SKILL.md} inside it. {@code name} in the frontmatter matches that folder.
     */
    public static final String INSTALL_DIR = ".agents/skills";

    private static final String RESOURCE_DIR = "/cc/jumpkick/docs/skill/";

    private JkSkill() {}

    /** {@code SKILL.md}, including frontmatter. Always LF, always ends in a newline. */
    public static String core() {
        return load("SKILL.md");
    }

    /** One topic file, or {@code null} when {@code name} is not a topic. */
    public static @Nullable String topic(@Nullable String name) {
        if (name == null || !TOPICS.contains(name)) return null;
        return load(name + ".md");
    }

    /**
     * Write {@code <skillsRoot>/jk/SKILL.md} and each topic file. Existing files are replaced.
     *
     * @return the skill directory ({@code skillsRoot/jk})
     */
    public static Path install(Path skillsRoot) throws IOException {
        Path dest = skillsRoot.resolve("jk");
        Files.createDirectories(dest);
        Files.writeString(dest.resolve("SKILL.md"), core(), StandardCharsets.UTF_8);
        for (String name : TOPICS) {
            String body = topic(name);
            if (body == null) throw new IllegalStateException("missing skill topic: " + name);
            Files.writeString(dest.resolve(name + ".md"), body, StandardCharsets.UTF_8);
        }
        return dest;
    }

    private static String load(String name) {
        String resource = RESOURCE_DIR + name;
        try (InputStream in = JkSkill.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing skill resource: " + resource);
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            return raw.endsWith("\n") ? raw : raw + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
