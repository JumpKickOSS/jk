// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.PathClients;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ProjectRoots;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.ReleasedClient;
import cc.jumpkick.cli.mcp.McpBridge;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.version.Versions;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

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
        String project = ProjectRoots.find(cwd).map(Path::toString).orElse(null);
        new McpBridge(McpCommand::endpoint, project).serve(System.in, System.out);
        return 0;
    }

    /**
     * A live engine's MCP URL and bearer token, starting the engine when none is running. The newer
     * engine an update installed while this bridge ran is used as it is: the bridge only relays, so
     * it keeps serving its client across the update instead of refusing the engine it finds. When
     * that engine is stopped or on its way out, the installed client starts its own: this bridge's
     * engine would displace the newer release, and its jar may be gone.
     */
    private static McpBridge.Endpoint endpoint() throws IOException {
        EnginePaths.Paths paths = EnginePaths.current();
        Optional<EngineProbe.Status> live = EngineProbe.status(EnginePaths.activeSocket(paths));
        Optional<String> installed = EngineInstall.current().currentInstall().map(EngineInstall.Materialized::version);
        Reach reach = reach(
                live.map(EngineProbe.Status::version).orElse(null),
                live.map(EngineProbe.Status::draining).orElse(false),
                JkVersion.VERSION,
                ReleasedClient.current(),
                installed);
        switch (reach) {
            case LIVE_NEWER -> {}
            case START_INSTALLED -> startInstalledEngine(installed.orElseThrow());
            case OWN -> EngineClient.ensureRunning(paths, JkVersion.VERSION);
        }
        EngineProbe.Status status = EngineProbe.status(EnginePaths.activeSocket(paths))
                .orElseThrow(() -> new IOException("the jk engine did not answer a status request"));
        if (status.mcpUrl() == null) {
            throw new IOException("the jk engine is not serving MCP; see `jk engine status`");
        }
        String token =
                Files.readString(paths.httpToken(), StandardCharsets.UTF_8).trim();
        return new McpBridge.Endpoint(URI.create(status.mcpUrl()), token);
    }

    /** How a bridge reaches an engine: the newer one already serving, the install's own started, or this client's. */
    enum Reach {
        LIVE_NEWER,
        START_INSTALLED,
        OWN
    }

    /**
     * Which engine a bridge of version {@code client} reaches ({@code live} the version of the
     * engine that answers, null when none does): the live one when it is the newer
     * release the install names and not draining; else, when this released client is older than
     * the install, the installed release's engine; else its own.
     */
    static Reach reach(
            @Nullable String live,
            boolean draining,
            String client,
            boolean releasedClient,
            Optional<String> installed) {
        if (live != null && servesNewerInstall(live, draining, client, installed)) {
            return Reach.LIVE_NEWER;
        }
        if (ReleasedClient.applicable(client, releasedClient, false)
                && installed.filter(v -> Versions.compare(v, client) > 0).isPresent()) {
            return Reach.START_INSTALLED;
        }
        return Reach.OWN;
    }

    /** How long the installed client may take to start its engine. */
    private static final Duration START_WAIT = Duration.ofMinutes(2);

    /** {@code bin/jk engine start}: the installed release starts its engine; its output stays off this bridge's stdout. */
    private static void startInstalledEngine(String version) throws IOException {
        Path jk = PathClients.pathClient(JkDirs.binDir());
        if (!Files.isRegularFile(jk)) {
            throw new IOException(
                    "JumpKick was updated to " + version + " but " + jk + " is missing; reinstall JumpKick");
        }
        Process p = new ProcessBuilder(jk.toString(), "engine", "start", "--no-ansi")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        p.getOutputStream().close();
        try {
            if (!p.waitFor(START_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroy();
                throw new IOException("jk " + version + " did not start its engine in " + START_WAIT.toSeconds() + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while jk " + version + " started its engine", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("jk " + version + " could not start its engine (exit " + p.exitValue()
                    + "); see `jk engine status`");
        }
    }

    /** Whether the live engine is the newer release the install names, and not on its way out. */
    public static boolean servesNewerInstall(String live, boolean draining, String client, Optional<String> installed) {
        return !draining
                && Versions.compare(live, client) > 0
                && installed.filter(live::equals).isPresent();
    }
}
