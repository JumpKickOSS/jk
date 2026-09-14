// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.jsonl.OutputLine;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.command.VariantSelection;
import cc.jumpkick.config.RepositoriesScan;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk publish} — assemble, sign, and upload Maven artifacts (engine-hosted worker).
 * Credentials and GPG passphrase are resolved client-side and passed on the request. SNAPSHOTs
 * require {@code --allow-snapshot}.
 */
public final class PublishCommand implements CliCommand {

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public String description() {
        return "Publish artifacts to a package repository";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>(List.of(
                Opt.value("<url>", "Target Maven repository URL (none on --dry-run).", "--repo-url"),
                Opt.value("<user>", "HTTP Basic username (PUBLISH_USER env).", "--user"),
                Opt.value("<pass>", "HTTP Basic password (PUBLISH_PASSWORD env).", "--password"),
                Opt.value("<REGION>", "Object-store region for s3:// / gs://.", "--region"),
                Opt.value("<URL>", "Object-store endpoint override for s3://.", "--endpoint"),
                Opt.value("<file>", "Override the main jar path.", "--jar"),
                Opt.flag("Permit publishing -SNAPSHOT versions.", "--allow-snapshot"),
                Opt.flag("Print the upload plan; no HTTP requests.", "--dry-run"),
                Opt.flag("Detached .asc GPG signature per artifact.", "--sign"),
                Opt.value("<file>", "GPG secret key path. Required with --sign.", "--key-file"),
                Opt.value("<pass>", "Key passphrase (JK_GPG_PASSPHRASE env).", "--key-passphrase"),
                Opt.flag("Sign with Sigstore keyless OIDC (.sigstore).", "--sigstore"),
                Opt.flag("Emit a SLSA v1 in-toto provenance statement.", "--slsa"),
                Opt.flag("Emit CycloneDX 1.6 and SPDX 2.3 SBOMs.", "--sbom")));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Nullable
    URI repoUrl;

    @Nullable
    String username;

    @Nullable
    String password;

    @Nullable
    String region;

    @Nullable
    String endpoint;

    @Nullable
    Path jarPath;

    boolean allowSnapshot;
    boolean dryRun;
    boolean sign;

    @Nullable
    Path keyFile;

    @Nullable
    String keyPassphrase;

    boolean sigstore;
    boolean slsa;
    boolean sbom;
    GlobalOptions global;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.username = in.value("user").orElse(null);
        this.password = in.value("password").orElse(null);
        this.region = in.value("region").orElse(null);
        this.endpoint = in.value("endpoint").orElse(null);
        this.jarPath = in.value("jar").map(Path::of).orElse(null);
        this.allowSnapshot = in.isSet("allow-snapshot");
        this.dryRun = in.isSet("dry-run");
        this.sign = in.isSet("sign");
        this.keyFile = in.value("key-file").map(Path::of).orElse(null);
        this.keyPassphrase = in.value("key-passphrase").orElse(null);
        this.sigstore = in.isSet("sigstore");
        this.slsa = in.isSet("slsa");
        this.sbom = in.isSet("sbom");
        this.global = GlobalOptions.from(in);

        Path projectDir = global.workingDir();
        VariantSelection.install(in, projectDir);
        Path jkBuildPath = projectDir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(jkBuildPath)) {
            CommandWedge.printFail("Publish", jkBuildPath + " not found.");
            return Exit.NO_INPUT;
        }
        if (sign && keyFile == null) {
            CommandWedge.printFail("Publish", "--sign requires --key-file <path>.");
            return Exit.USAGE;
        }
        if (repoUrl == null && !dryRun) {
            CommandWedge.printFail("Publish", "--repo-url <url> is required; only a --dry-run publishes nowhere.");
            return Exit.USAGE;
        }
        Path cache = JkDirs.cache();

        // Project facts via PROJECT_INFO; inline repo credential is a deliberate client-side
        // read (keychain/env prompts stay here; secrets never ride the wire).
        ProjectInfo info = projectInfo(projectDir);
        if (info.error() != null) {
            CommandWedge.printFail("Publish", info.error());
            return Exit.CONFIG;
        }

        // Path deps are consume-only (docs/path-source-deps.md): a published POM would
        // reference a coordinate no consumer can resolve. Refuse before any upload —
        // `jk export` warn-and-skips for the same reason.
        for (String pathDep : info.pathDeps()) {
            CommandWedge.printFail(
                    "Publish",
                    "`" + pathDep
                            + "` is a path dependency — path deps are consume-only and cannot be"
                            + " published. Promote it to a [workspace] module or a published"
                            + " coordinate first.");
            return Exit.CONFIG;
        }

        // Resolve everything env/keychain-shaped here — never inside the engine. A dry run with no
        // repository has nothing to authenticate to.
        RepoCredential cred;
        try {
            cred = repoUrl == null ? RepoCredential.ANONYMOUS : resolvePublishCredential(jkBuildPath);
        } catch (RuntimeException e) {
            CommandWedge.printFail("Publish", e.getMessage());
            return Exit.CONFIG;
        }
        String gpgPass = sign ? (keyPassphrase != null ? keyPassphrase : System.getenv("JK_GPG_PASSPHRASE")) : null;

        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        BuildPlanResult result;
        int files;
        EngineRequests.PublishOutcome outcome;
        try {
            outcome = EngineClient.runPublish(
                    EnginePaths.current(),
                    new EngineRequests.PublishRequest(
                            projectDir,
                            cache,
                            repoUrl,
                            region,
                            endpoint,
                            jarPath,
                            allowSnapshot,
                            dryRun,
                            sign ? keyFile : null,
                            gpgPass,
                            sigstore,
                            slsa,
                            sbom,
                            cred,
                            global.offline,
                            global.verbose),
                    steps -> BuildPlanConsole.chooseConsoleListener("publish", steps, mode));
        } catch (IOException e) {
            CommandWedge.printFail("Publish", e.getMessage());
            return Exit.SOFTWARE;
        }
        result = outcome.result();
        files = outcome.files();

        if (!result.success()) {
            for (BuildPlanResult.Diagnostic d : result.errors()) {
                if ("snapshot".equals(d.code())) return Exit.DATA_ERR;
                if ("missing-jar".equals(d.code())) return Exit.NO_INPUT;
            }
            return 1;
        }

        // The documents the run left under target/ (the SBOMs), by path, so a release script or
        // a reader takes them from here without an upload. Workspace-relative like every other
        // path jk prints: a member's target/ sits under the root, outside the member's own dir.
        Path workspaceRoot = WorkspaceLocator.findRoot(projectDir).orElse(projectDir);
        if (!global.outputIsJson()) {
            String summary = dryRun ? "(dry-run)" : "(" + files + " files)";
            CliOutput.out("Published " + Coords.gav(info.group(), info.name(), info.version()) + " " + summary);
            for (String written : outcome.written()) {
                CliOutput.out("  wrote " + displayPath(Path.of(written), workspaceRoot, projectDir));
            }
        } else {
            for (String written : outcome.written()) {
                CliOutput.out(new OutputLine(
                                Clock.SYSTEM.millis(),
                                "publish",
                                "wrote " + displayPath(Path.of(written), workspaceRoot, projectDir))
                        .encode());
            }
        }
        return 0;
    }

    /**
     * A written document's path as the reader sees it: relative to the workspace root when it
     * sits under it, else to the project directory, else absolute. Forward slashes on every
     * platform, so a release script reads one spelling.
     */
    static String displayPath(Path written, Path workspaceRoot, Path projectDir) {
        Path p = written.toAbsolutePath().normalize();
        Path ws = workspaceRoot.toAbsolutePath().normalize();
        Path proj = projectDir.toAbsolutePath().normalize();
        Path shown = p.startsWith(ws) ? ws.relativize(p) : p.startsWith(proj) ? proj.relativize(p) : p;
        return shown.toString().replace(shown.getFileSystem().getSeparator(), "/");
    }

    private RepoCredential resolvePublishCredential(Path jkBuildPath) {
        String user = username != null ? username : System.getenv("PUBLISH_USER");
        if (user != null && !user.isBlank()) {
            String pass = password != null ? password : System.getenv("PUBLISH_PASSWORD");
            return new RepoCredential.Basic(user, pass == null ? "" : pass);
        }
        String matchedName = null;
        Optional<RepoCredential> inline = Optional.empty();
        String target = Objects.requireNonNull(repoUrl, "--repo-url").toString();
        for (RepositoriesScan.Repo repo : RepositoriesScan.scan(jkBuildPath)) {
            String base = repo.url();
            String basePrefix = base.endsWith("/") ? base : base + "/";
            if (target.equals(base) || target.startsWith(basePrefix)) {
                matchedName = repo.name();
                inline = repo.credentialOpt();
                break;
            }
        }
        return new RepoCredentialResolver().resolve(matchedName, repoUrl, inline);
    }

    private static ProjectInfo projectInfo(Path dir) {
        try {
            return EngineClient.projectInfo(EnginePaths.current(), dir);
        } catch (Exception e) {
            return ProjectInfo.error(String.valueOf(e.getMessage()));
        }
    }
}
