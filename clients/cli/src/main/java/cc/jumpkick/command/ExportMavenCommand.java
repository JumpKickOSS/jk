// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.engine.protocol.GeneratedFiles;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.util.List;

/**
 * {@code jk export maven} — emit {@code pom.xml} (or a reactor for workspaces). Content is
 * engine-generated; this command writes and reports.
 */
public final class ExportMavenCommand implements CliCommand {

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public List<String> aliases() {
        return List.of("pom");
    }

    @Override
    public String description() {
        return "Generate a Maven pom.xml from jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Overwrite existing pom.xml files.", "--overwrite"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        boolean force = in.isSet("overwrite");
        GeneratedFiles files =
                ExportSupport.generate(global.workingDir(), "export-maven", "jk export maven", global);
        if (files == null) return Exit.NO_INPUT;
        return ExportSupport.writeAll(files, force, "jk export maven");
    }
}
