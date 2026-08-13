// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.bsp.BspServer;
import cc.jumpkick.cli.ide.IdeEngineClient;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.AtomicWrites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * — Build Server Protocol host (wire-only via {@link IdeEngineClient}).
 *
 * <pre>
 * jk bsp install # write.bsp/jk.json
 * jk bsp # serve JSON-RPC on stdio (IDE launches this)
 * </pre>
 */
public final class BspCommand implements CliCommand {

    @Override
    public String name() {
        return "bsp";
    }

    @Override
    public String description() {
        return "Build Server Protocol host for IDEs (install or serve)";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("action", Arity.ZERO_OR_ONE, "install (write .bsp/jk.json) or serve (default: serve)"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        String action = in.positionals().isEmpty()
                ? "serve"
                : in.positionals().getFirst().trim().toLowerCase(Locale.ROOT);

        return switch (action) {
            case "install" -> install(dir);
            case "serve", "run" -> serve(dir, global);
            default -> {
                CliOutput.err(
                        cc.jumpkick.cli.tui.CommandWedge.fail("BSP", "expected install or serve (got " + action + ")"));
                yield Exit.USAGE;
            }
        };
    }

    private static int install(Path projectDir) throws Exception {
        if (!Files.isRegularFile(projectDir.resolve("jk.toml"))) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "BSP", "no jk.toml in " + cc.jumpkick.cli.PathDisplay.of(projectDir)));
            return Exit.CONFIG;
        }
        Path out = writeConnectionFile(projectDir);
        cc.jumpkick.cli.tui.CommandWedge.printOk("BSP", "Wrote " + cc.jumpkick.cli.PathDisplay.of(out, projectDir));
        CliOutput.out("Open this project in an IDE with BSP support (IntelliJ via Scala plugin / Metals).");
        return 0;
    }

    /**
     * Write {@code .bsp/jk.json} so BSP clients (Metals, IntelliJ Scala plugin, …) can spawn
     * {@code jk bsp serve} on stdio. Idempotent. Used by {@code jk bsp install} and {@code jk ide}
     * (including {@code idea}/{@code vscode} aliases).
     *
     * @return path of the connection file written
     */
    public static Path writeConnectionFile(Path projectDir) throws Exception {
        Path bspDir = projectDir.resolve(".bsp");
        Files.createDirectories(bspDir);
        // Prefer the jk on PATH; IDE will spawn: jk bsp serve
        String argv0 = System.getenv().getOrDefault("JK_BIN", "jk");
        String json = """
                {
                  "name": "jk",
                  "version": "%s",
                  "bspVersion": "2.1.0",
                  "languages": ["java", "kotlin", "groovy"],
                  "argv": ["%s", "bsp", "serve"]
                }
                """.formatted(escapeJson(cc.jumpkick.cli.Jk.VERSION), escapeJson(argv0));
        Path out = bspDir.resolve("jk.json");
        AtomicWrites.replace(out, json);
        return out;
    }

    private static int serve(Path projectDir, GlobalOptions global) throws Exception {
        var proj = ProjectContext.require(projectDir, "bsp").orElse(null);
        if (proj == null) return Exit.CONFIG;
        // Freshen once at serve start so IDE classpaths match manifests (long-lived process).
        int lockCode =
                cc.jumpkick.cli.EnsureFreshLock.ensure(projectDir, cc.jumpkick.util.JkDirs.cache(), global, "BSP");
        if (lockCode != 0) return lockCode;
        IdeEngineClient ide = IdeEngineClient.open(projectDir);
        ide.connect();
        new BspServer(ide, System.in, System.out).serve();
        return 0;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
