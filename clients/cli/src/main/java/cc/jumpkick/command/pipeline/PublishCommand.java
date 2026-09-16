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
import cc.jumpkick.model.RepositorySpec;
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
 *
 * <p>{@code --central} switches the target to the Sonatype Central Portal: one signed bundle of
 * jar, POM, sources and javadoc jars uploaded to the Portal's API, then polled to its verdict. The
 * token comes from the {@code central} credential ({@code jk repo login central --url
 * https://central.sonatype.com}, or {@code JK_REPO_CENTRAL_*}); {@code --repo-url} names another
 * Portal; a dry run writes the bundle under {@code target/publish/} and lists it.
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
                Opt.flag("Signed bundle to the Sonatype Central Portal.", "--central"),
                Opt.value("<type>", "Central: user-managed (default) or automatic.", "--publishing-type"),
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
    boolean central;

    @Nullable
    String publishingType;

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
        this.central = in.isSet(RepositorySpec.CENTRAL);
        this.publishingType = in.value("publishing-type").orElse(null);
        this.global = GlobalOptions.from(in);

        Path projectDir = global.workingDir();
        VariantSelection.install(in, projectDir);
        Path jkBuildPath = ManifestPaths.manifestIn(projectDir);
        if (!Files.exists(jkBuildPath)) {
            CommandWedge.printFail("Publish", jkBuildPath + " not found.");
            return Exit.NO_INPUT;
        }
        int usage = checkOptions();
        if (usage != 0) return usage;
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
            cred = repoUrl == null || (central && dryRun)
                    ? RepoCredential.ANONYMOUS
                    : central ? resolveCentralCredential() : resolvePublishCredential(jkBuildPath);
        } catch (RuntimeException e) {
            CommandWedge.printFail("Publish", e.getMessage());
            return Exit.CONFIG;
        }
        String gpgPass = sign ? (keyPassphrase != null ? keyPassphrase : System.getenv("JK_GPG_PASSPHRASE")) : null;

        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        BuildPlanResult result;
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
                            global.verbose,
                            central,
                            publishingType),
                    steps -> BuildPlanConsole.chooseConsoleListener("publish", steps, mode));
        } catch (IOException e) {
            CommandWedge.printFail("Publish", e.getMessage());
            return Exit.SOFTWARE;
        }
        result = outcome.result();

        if (!result.success()) {
            for (BuildPlanResult.Diagnostic d : result.errors()) {
                if ("snapshot".equals(d.code())) return Exit.DATA_ERR;
                if ("missing-jar".equals(d.code())) return Exit.NO_INPUT;
            }
            // A deployment the Portal refused is the one failure with facts of its own to print:
            // the id to look up and every validation error, beside the diagnostics already shown.
            if (outcome.deploymentId() != null && !global.outputIsJson()) {
                CliOutput.err(
                        "Central Portal deployment " + outcome.deploymentId() + " · " + outcome.deploymentState());
                for (String error : outcome.deploymentErrors()) CliOutput.err("  - " + error);
            }
            return 1;
        }

        report(info, outcome, projectDir);
        return 0;
    }

    /** The flag combinations that cannot publish anywhere, refused with the flag that fixes them; {@code 0} when the set is coherent. */
    private int checkOptions() {
        if (sign && keyFile == null) {
            CommandWedge.printFail("Publish", "--sign requires --key-file <path>.");
            return Exit.USAGE;
        }
        if (publishingType != null && !central) {
            CommandWedge.printFail("Publish", "--publishing-type applies to --central only.");
            return Exit.USAGE;
        }
        if (central) {
            // Central validates a signature on every file, so a bundle without one is refused
            // here, before anything is assembled, with the flags that make it valid.
            if (!sign) {
                CommandWedge.printFail("Publish", CENTRAL_SIGNING_REQUIRED);
                return Exit.USAGE;
            }
            if (sigstore) {
                CommandWedge.printFail("Publish", "--central takes GPG signatures only; drop --sigstore.");
                return Exit.USAGE;
            }
            try {
                publishingType = publishingType == null ? null : publishingType.trim();
                if (publishingType != null
                        && !"automatic".equalsIgnoreCase(publishingType)
                        && !"user-managed".equalsIgnoreCase(publishingType)) {
                    throw new IllegalArgumentException(
                            "--publishing-type must be user-managed or automatic, got: " + publishingType);
                }
            } catch (IllegalArgumentException e) {
                CommandWedge.printFail("Publish", e.getMessage());
                return Exit.USAGE;
            }
            if (repoUrl == null) repoUrl = CENTRAL_PORTAL;
        } else if (repoUrl == null && !dryRun) {
            CommandWedge.printFail("Publish", "--repo-url <url> is required; only a --dry-run publishes nowhere.");
            return Exit.USAGE;
        }
        return 0;
    }

    /**
     * The success lines: the coordinate and where it went, the Portal deployment when there is
     * one, the documents the run left under target/ (the SBOMs, a Central bundle) by path, and on
     * a dry run the bundle's entries. Paths are workspace-relative like every other path jk
     * prints: a member's target/ sits under the root, outside the member's own dir.
     */
    private void report(ProjectInfo info, EngineRequests.PublishOutcome outcome, Path projectDir) throws IOException {
        Path workspaceRoot = WorkspaceLocator.findRoot(projectDir).orElse(projectDir);
        if (!global.outputIsJson()) {
            String summary = dryRun ? "(dry-run)" : "(" + outcome.files() + " files)";
            String where = central ? " to the Central Portal " : " ";
            CliOutput.out("Published " + Coords.gav(info.group(), info.name(), info.version()) + where + summary);
            if (outcome.deploymentId() != null) {
                CliOutput.out("  deployment " + outcome.deploymentId() + " · " + outcome.deploymentState()
                        + ("VALIDATED".equals(outcome.deploymentState())
                                ? " — release it from the Portal, or publish with --publishing-type automatic"
                                : ""));
            }
            for (String written : outcome.written()) {
                CliOutput.out("  wrote " + displayPath(Path.of(written), workspaceRoot, projectDir));
            }
            if (dryRun) {
                for (String entry : outcome.bundle()) CliOutput.out("    " + entry);
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

    /** The production Portal; {@code --repo-url} with {@code --central} names another one (a stub, a mirror). */
    static final URI CENTRAL_PORTAL = URI.create("https://central.sonatype.com/");

    /** The credential id the Portal token lives under — the repository's own name — in the store and the {@code JK_REPO_<ID>_*} variables. */
    static final String CENTRAL_CREDENTIAL = RepositorySpec.CENTRAL;

    static final String CENTRAL_SIGNING_REQUIRED =
            "Maven Central requires a GPG signature on every file: pass --sign --key-file <secret-key.asc>"
                    + " (the passphrase via --key-passphrase or JK_GPG_PASSPHRASE).";

    /**
     * The Portal's user token: {@code --user}/{@code --password} (or {@code PUBLISH_USER} /
     * {@code PUBLISH_PASSWORD}) as the token's name and password, else whatever is stored for the
     * {@code central} id and bound to the Portal's origin. The worker encodes a name/password pair
     * the way the Portal documents and sends a stored bearer token as it is.
     */
    private RepoCredential resolveCentralCredential() {
        String user = username != null ? username : System.getenv("PUBLISH_USER");
        if (user != null && !user.isBlank()) {
            String pass = password != null ? password : System.getenv("PUBLISH_PASSWORD");
            return new RepoCredential.Basic(user, pass == null ? "" : pass);
        }
        URI portal = Objects.requireNonNull(repoUrl, "--central names the Portal");
        RepoCredential cred = new RepoCredentialResolver().resolve(CENTRAL_CREDENTIAL, portal, Optional.empty());
        if (cred.isAnonymous()) {
            throw new IllegalStateException("no Central Portal token: run `jk repo login " + CENTRAL_CREDENTIAL
                    + " --url " + portal + " --username <token-name>` with the token's password on stdin, or export"
                    + " JK_REPO_CENTRAL_USERNAME + JK_REPO_CENTRAL_PASSWORD with JK_REPO_CENTRAL_HOST="
                    + portal.getHost()
                    + " (a user token from https://central.sonatype.com/account).");
        }
        return cred;
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
