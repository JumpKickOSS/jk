// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.engine.protocol.GeneratedFiles;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jk export bom} — freeze {@code jk-lock.toml} versions for a scope into a Maven BOM POM
 *
 */
public final class ExportBomCommand implements CliCommand {

    @Override
    public String name() {
        return "bom";
    }

    @Override
    public String description() {
        return "Export a Maven BOM (dependencyManagement) from jk-lock.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value(
                        "<main|test|all>",
                        "Which lock scopes to freeze (default main = export/main/runtime/provided/dev).",
                        "--scope"),
                Opt.value("<path>", "Output path relative to the project (default target/<name>-bom.pom).", "--out"),
                Opt.flag("Overwrite an existing file.", "--overwrite"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        boolean force = in.isSet("overwrite");
        Map<String, String> params = new LinkedHashMap<>();
        in.value("scope").ifPresent(s -> params.put("scope", s));
        in.value("out").ifPresent(o -> params.put("out", o));
        GeneratedFiles files = ExportSupport.generate(global.workingDir(), "export-bom", params, "jk export bom");
        if (files == null) return Exit.NO_INPUT;
        return ExportSupport.writeAll(files, force, "jk export bom");
    }
}
