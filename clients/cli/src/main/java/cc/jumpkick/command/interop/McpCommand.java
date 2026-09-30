// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ProjectRoots;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.mcp.McpBridge;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk mcp} — the engine's MCP server on stdio, for the project the working directory is in.
 * An MCP client launches it in the project, so its tools take no {@code dir} and no token lands in
 * the client's config. Outside a project the tools take {@code dir} as over HTTP.
 */
public final class McpCommand implements CliCommand {

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "MCP server on stdio for this project (an agent launches it)";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    /** Stdout is the MCP client's: JSON-RPC and nothing else. */
    @Override
    public boolean scriptMode(Invocation in) {
        return true;
    }

    @Override
    public int run(Invocation in) throws Exception {
        Path cwd = GlobalOptions.from(in).workingDir();
        String project = ProjectRoots.find(cwd == null ? Path.of("") : cwd)
                .map(Path::toString)
                .orElse(null);
        new McpBridge(McpCommand::endpoint, project).serve(System.in, System.out);
        return 0;
    }

    /** A live engine's MCP URL and bearer token, starting the engine when none is running. */
    private static McpBridge.Endpoint endpoint() throws IOException {
        EnginePaths.Paths paths = EnginePaths.current();
        EngineClient.ensureRunning(paths, JkVersion.VERSION);
        EngineProbe.Status status = EngineProbe.status(EnginePaths.activeSocket(paths))
                .orElseThrow(() -> new IOException("the jk engine did not answer a status request"));
        if (status.mcpUrl() == null) {
            throw new IOException("the jk engine is not serving MCP (HTTP or [mcp] is off; see `jk engine status`)");
        }
        String token =
                Files.readString(paths.httpToken(), StandardCharsets.UTF_8).trim();
        return new McpBridge.Endpoint(URI.create(status.mcpUrl()), token);
    }
}
