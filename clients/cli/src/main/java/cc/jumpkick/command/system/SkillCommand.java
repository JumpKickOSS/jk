// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.docs.AgentsGuide;
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
 * {@code jk skill} — print the JumpKick skill, one topic, install the folder, or bring jk's section of
 * the project's {@code AGENTS.md} up to date. Same bytes as MCP {@code skill} / {@code jk://skill}. No
 * engine round-trip.
 */
public final class SkillCommand implements CliCommand {

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Print or install the agent skill; update AGENTS.md";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("List topic names", "-l", "--list"));
    }

    /** A topic name, or {@code install} / {@code agents} and an optional directory. */
    @Override
    public List<Param> parameters() {
        return List.of(Param.of("topic", Arity.ZERO_OR_MORE, "Topic, install [dir], or agents [dir]"));
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
        if (!pos.isEmpty() && "agents".equals(pos.get(0))) {
            Path dir = pos.size() > 1 ? Path.of(pos.get(1)) : projectRoot();
            if (!dir.isAbsolute()) dir = Path.of("").toAbsolutePath().resolve(dir);
            try {
                AgentsGuide.Change change = AgentsGuide.sync(dir);
                CliOutput.outRaw(agentsLine(change, dir.resolve(AgentsGuide.FILE)) + "\n");
                CliOutput.stdout().flush();
                return Exit.SUCCESS;
            } catch (IOException e) {
                CliOutput.err(e.getMessage() == null ? "AGENTS.md update failed" : e.getMessage());
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
            CliOutput.err("jk skill -l");
            return Exit.USAGE;
        }
        CliOutput.outRaw(text);
        CliOutput.stdout().flush();
        return Exit.SUCCESS;
    }

    static String agentsLine(AgentsGuide.Change change, Path file) {
        return switch (change) {
            case CREATED -> "created " + file;
            case INSERTED -> "added jk's section to " + file;
            case UPDATED -> "updated jk's section of " + file;
            case UNCHANGED, ABSENT -> file + " is current";
        };
    }

    /** The workspace root owning the nearest {@code jk.toml} above the cwd, that project, else the cwd. */
    static Path projectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path cursor = dir; cursor != null; cursor = cursor.getParent()) {
            if (Files.isRegularFile(ManifestPaths.manifestIn(cursor))) {
                return WorkspaceScan.findRoot(cursor).orElse(cursor);
            }
        }
        return dir;
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
