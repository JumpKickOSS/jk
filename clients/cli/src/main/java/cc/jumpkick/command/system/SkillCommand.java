// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.docs.JkSkill;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk skill} — print the JumpKick skill, one topic, or install the folder. Same bytes as MCP
 * {@code skill} / {@code jk://skill}. No engine round-trip.
 */
public final class SkillCommand implements CliCommand {

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Print the JumpKick skill, or install it for agents";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("List topic names", "--list"));
    }

    /** A topic name, or {@code install} and an optional directory. */
    @Override
    public List<Param> parameters() {
        return List.of(Param.of("topic", Arity.ZERO_OR_MORE, "Topic, or install [dir]"));
    }

    /** The skill is a document (and install prints a path), so stdout is byte-exact. */
    @Override
    public boolean scriptMode(Invocation in) {
        return true;
    }

    @Override
    public int run(Invocation in) {
        if (in.isSet("list")) {
            CliOutput.outRaw(String.join("\n", JkSkill.TOPICS) + "\n");
            CliOutput.stdout().flush();
            return Exit.SUCCESS;
        }
        List<String> pos = in.positionals();
        if (!pos.isEmpty() && "install".equals(pos.get(0))) {
            Path root = pos.size() > 1 ? Path.of(pos.get(1)) : defaultSkillsRoot();
            if (!root.isAbsolute()) root = Path.of("").toAbsolutePath().resolve(root);
            try {
                Path written = JkSkill.install(root);
                CliOutput.outRaw(written.toString() + "\n");
                CliOutput.stdout().flush();
                return Exit.SUCCESS;
            } catch (IOException e) {
                CliOutput.err(e.getMessage() == null ? "install failed" : e.getMessage());
                return Exit.CONFIG;
            }
        }
        if (pos.isEmpty()) {
            CliOutput.outRaw(JkSkill.core());
            CliOutput.stdout().flush();
            return Exit.SUCCESS;
        }
        String text = JkSkill.topic(pos.get(0));
        if (text == null) {
            CliOutput.err("unknown skill topic: " + pos.get(0));
            CliOutput.err("jk skill --list");
            return Exit.USAGE;
        }
        CliOutput.outRaw(text);
        CliOutput.stdout().flush();
        return Exit.SUCCESS;
    }

    /** {@code <project>/.agents/skills}, walking up to the nearest {@code jk.toml}, else the cwd. */
    static Path defaultSkillsRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path cursor = dir; cursor != null; cursor = cursor.getParent()) {
            if (Files.isRegularFile(ManifestPaths.manifestIn(cursor))) return cursor.resolve(JkSkill.INSTALL_DIR);
        }
        return dir.resolve(JkSkill.INSTALL_DIR);
    }
}
