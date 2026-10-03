// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.NodeTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * frontend-maven-plugin's executions as a node build: the Node.js version, the install, the build
 * and test scripts, every other command as a {@code [[node.steps]]} entry by the phase it ran in.
 * Where the build lands is {@link FrontendImport}'s. corepack is never run: {@code corepack yarn x}
 * is the manager's own {@code yarn x}, which jk provisions from {@code packageManager}.
 */
final class FrontendPlugin {

    static final String ARTIFACT = "frontend-maven-plugin";

    /**
     * The node build a module's executions describe.
     *
     * @param dir the absolute directory holding {@code package.json}
     * @param workingDirectory {@code dir} relative to the module, {@code "."} for the module itself
     * @param version the {@code node =} spec, or null when no install goal names one
     * @param table every {@code [node]} key the executions set; nothing about placement
     */
    record Frontend(
            Path dir,
            String workingDirectory,
            @Nullable String version,
            NodeTable table,
            boolean failuresReport,
            List<EnvDecl> env) {}

    private static final List<String> INSTALL_GOALS = List.of(
            "install-node-and-npm",
            "install-node-and-yarn",
            "install-node-and-pnpm",
            "install-node-and-corepack",
            "install-bun");
    private static final List<String> COMMAND_GOALS = List.of("npm", "yarn", "pnpm", "bun", "corepack", "npx");
    private static final List<String> TOOL_GOALS =
            List.of("bower", "grunt", "gulp", "jspm", "karma", "webpack", "ember");
    private static final List<String> MANAGER_VERSIONS =
            List.of("npmVersion", "yarnVersion", "pnpmVersion", "bunVersion", "corepackVersion");
    private static final Map<String, String> MIRRORS = Map.of(
            "nodeDownloadRoot", "dist-mirror",
            "downloadRoot", "dist-mirror",
            "npmDownloadRoot", "registry",
            "yarnDownloadRoot", "registry",
            "pnpmDownloadRoot", "registry",
            "bunDownloadRoot", "registry",
            "corepackDownloadRoot", "registry",
            "npmRegistryURL", "registry");
    private static final List<String> SKIP_PROPERTIES = List.of(
            "skip.npm",
            "skip.yarn",
            "skip.pnpm",
            "skip.bun",
            "skip.corepack",
            "skip.npx",
            "skip.installnodenpm",
            "skip.installyarn",
            "skip.installnodepnpm",
            "skip.installnodecorepack",
            "skip.installbun");
    private static final List<String> PHASES = List.of(
            "validate",
            "initialize",
            "generate-sources",
            "process-sources",
            "generate-resources",
            "process-resources",
            "compile",
            "process-classes",
            "generate-test-sources",
            "process-test-sources",
            "generate-test-resources",
            "process-test-resources",
            "test-compile",
            "process-test-classes",
            "test",
            "prepare-package",
            "package",
            "pre-integration-test",
            "integration-test",
            "post-integration-test",
            "verify",
            "install",
            "deploy");
    private static final Set<String> INSTALL_WORDS = Set.of("install", "i", "ci");
    private static final Set<String> FROZEN_FLAGS = Set.of(
            "--frozen-lockfile",
            "--immutable",
            "--no-audit",
            "--no-fund",
            "--prefer-offline",
            "--silent",
            "--no-progress",
            "--non-interactive");
    private static final Set<String> MANAGER_BUILTINS = Set.of(
            "install",
            "i",
            "ci",
            "add",
            "remove",
            "up",
            "upgrade",
            "update",
            "link",
            "unlink",
            "set",
            "config",
            "dlx",
            "exec",
            "info",
            "why",
            "pack",
            "publish",
            "plugin",
            "workspace",
            "workspaces",
            "cache",
            "init",
            "node",
            "npm",
            "rebuild",
            "bin");
    private static final Pattern PACKAGE_MANAGER = Pattern.compile("\"packageManager\"\\s*:\\s*\"([^\"]+)\"");

    private FrontendPlugin() {}

    /** One execution's command, as jk runs it. */
    private record Parsed(
            Kind kind,
            NodeTable.@Nullable Command command,
            @Nullable String install) {
        enum Kind {
            INSTALL,
            COMMAND
        }
    }

    /** A command execution and where in Maven's lifecycle it ran. */
    private record Placed(String id, int phase, NodeTable.Command command) {}

    /**
     * The node build {@code model}'s frontend-maven-plugin describes, or null when it has none, when
     * no {@code package.json} is there to build, or when the project is on Yarn 1.
     */
    static @Nullable Frontend map(Model model, Path moduleDir, ImportReport.Builder report) {
        Plugin plugin = PluginFacts.plugin(model, ARTIFACT).orElse(null);
        if (plugin == null) return null;
        Xpp3Dom shared = plugin.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
        String configured = setting(shared, null, "workingDirectory");
        String workingDirectory = workingDirectory(configured);
        Path dir = moduleDir.resolve(workingDirectory).normalize();
        String where = "`" + ARTIFACT + "`";
        if (!Files.isRegularFile(dir.resolve("package.json"))) {
            // A parent's plugin reaches every child; only a directory the module names is worth a row.
            if (configured != null) {
                report.warning(where + " runs in `" + workingDirectory + "`, which has no package.json; no node"
                        + " build is imported");
            }
            return null;
        }
        String packageManager = packageManager(dir);
        if (yarnOne(dir, packageManager)) {
            report.error(where + " builds with Yarn 1, which jk does not run: in `" + workingDirectory
                    + "` run `yarn set version stable && yarn install`, then commit, and import again");
            return null;
        }
        boolean skip = false;
        for (String property : SKIP_PROPERTIES) {
            if (isTrue(model.getProperties().getProperty(property))) skip = true;
        }
        String version = null;
        String install = null;
        boolean failuresReport = isTrue(setting(shared, null, "testFailureIgnore"));
        Map<String, String> env = new LinkedHashMap<>(env(shared));
        List<Placed> placed = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        reportSettings(shared, null, packageManager, report, reported);
        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom config = execution.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
            reportSettings(shared, config, packageManager, report, reported);
            env.putAll(env(config));
            if (isTrue(setting(shared, config, "testFailureIgnore"))) failuresReport = true;
            boolean skipped = isTrue(setting(shared, config, "skip"));
            int phase = phase(execution.getPhase());
            for (String goal : execution.getGoals()) {
                String id = "`" + execution.getId() + "`";
                if (INSTALL_GOALS.contains(goal)) {
                    if (skipped) skip = true;
                    String v = setting(shared, config, "nodeVersion");
                    if (v != null) version = nodeVersion(v);
                    continue;
                }
                if (TOOL_GOALS.contains(goal)) {
                    report.warning(where + " goal `" + goal + "` (execution " + id + ") is not imported: run it from"
                            + " an npm script, or as a [[node.steps]] npx step");
                    continue;
                }
                if (!COMMAND_GOALS.contains(goal)) continue;
                if (skipped) {
                    report.warning(where + " execution " + id + " sets <skip>true</skip> and is not imported");
                    continue;
                }
                Parsed parsed = parse(goal, setting(shared, config, "arguments"));
                if (parsed == null) {
                    report.warning(where + " execution " + id + " names no command and is not imported");
                } else if (parsed.kind() == Parsed.Kind.INSTALL) {
                    if (parsed.install() != null) install = parsed.install();
                } else if (phase >= PHASES.indexOf("install")) {
                    report.warning(where + " execution " + id + " runs at `" + execution.getPhase()
                            + "`, after the build; it is not imported");
                } else {
                    placed.add(
                            new Placed(stepName(execution.getId()), phase, Objects.requireNonNull(parsed.command())));
                }
            }
        }
        if (version == null) {
            report.warning(where + " names no nodeVersion: write `node = 24` (or the version the project needs)");
        }
        NodeTable table = table(placed, install, skip);
        return new Frontend(dir, workingDirectory, version, table, failuresReport, envDecls(env));
    }

    /** The {@code [node]} keys of the placed commands: the build, the test and every other as a step. */
    private static NodeTable table(List<Placed> placed, @Nullable String install, boolean skip) {
        List<Placed> sorted = new ArrayList<>(placed);
        sorted.sort((a, b) -> Integer.compare(a.phase(), b.phase()));
        int compileEnd = PHASES.indexOf("process-classes");
        int testEnd = PHASES.indexOf("test");
        int packageEnd = PHASES.indexOf("package");
        List<Placed> build = new ArrayList<>();
        List<Placed> test = new ArrayList<>();
        List<Placed> pkg = new ArrayList<>();
        for (Placed p : sorted) {
            if (p.phase() <= compileEnd) build.add(p);
            else if (p.phase() <= testEnd || p.phase() > packageEnd) test.add(p);
            else pkg.add(p);
        }
        Placed buildCommand = pick(build, "build");
        Placed testCommand =
                test.stream().filter(p -> isScript(p, "test")).findFirst().orElse(null);
        List<NodeTable.Step> steps = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean afterBuild = false;
        for (Placed p : build) {
            if (p == buildCommand) {
                afterBuild = true;
                continue;
            }
            steps.add(step(
                    p, afterBuild ? NodeTable.Before.PACKAGE : NodeTable.Before.BUILD, NodeTable.Tier.BUILD, names));
        }
        for (Placed p : test) {
            if (p != testCommand) steps.add(step(p, NodeTable.Before.TEST, NodeTable.Tier.TEST, names));
        }
        for (Placed p : pkg) steps.add(step(p, NodeTable.Before.PACKAGE, NodeTable.Tier.BUILD, names));
        NodeTable.Command buildRun = buildCommand == null ? null : buildCommand.command();
        if (buildRun != null
                && buildRun.kind() == NodeTable.Command.Kind.RUN
                && buildRun.value().equals("build")) {
            buildRun = null;
        }
        // The test script is jk's default test; any other test-phase command is a test-tier step.
        return new NodeTable(
                null, null, install, buildRun, null, null, null, null, null, null, null, null, null, skip, steps,
                Map.of());
    }

    /** The build: the script named {@code script}, else the last script or npx command of the build phases. */
    private static @Nullable Placed pick(List<Placed> placed, String script) {
        Placed last = null;
        for (Placed p : placed) {
            if (isScript(p, script)) return p;
            if (p.command().kind() != NodeTable.Command.Kind.EXEC) last = p;
        }
        return last;
    }

    private static boolean isScript(Placed p, String script) {
        return p.command().kind() == NodeTable.Command.Kind.RUN
                && p.command().value().equals(script);
    }

    private static NodeTable.Step step(Placed p, NodeTable.Before before, NodeTable.Tier tier, Set<String> names) {
        String name = p.id();
        for (int n = 2; !names.add(name); n++) name = p.id() + "-" + n;
        return new NodeTable.Step(name, p.command(), before, tier, List.of(), List.of(), false);
    }

    /**
     * {@code arguments} of a {@code goal} execution as jk runs it. {@code corepack <pm> …} is {@code
     * <pm> …}; a frozen install is the implicit {@code node-install}; a package script is a run; an
     * {@code exec} / {@code dlx} is an npx; anything else runs as given.
     */
    static @Nullable Parsed parse(String goal, @Nullable String arguments) {
        List<String> tokens = new ArrayList<>(
                arguments == null || arguments.isBlank()
                        ? List.of()
                        : Arrays.asList(arguments.trim().split("\\s+")));
        if (goal.equals("npx")) {
            return tokens.isEmpty()
                    ? null
                    : new Parsed(Parsed.Kind.COMMAND, NodeTable.Command.npx(String.join(" ", tokens)), null);
        }
        String pm = goal;
        if (goal.equals("corepack")) {
            if (tokens.isEmpty()) return null;
            pm = tokens.remove(0);
        } else if (tokens.isEmpty() && !goal.equals("yarn")) {
            tokens.add("install");
        }
        if (tokens.isEmpty()) return new Parsed(Parsed.Kind.INSTALL, null, null);
        String first = tokens.get(0);
        List<String> rest = tokens.subList(1, tokens.size());
        if (INSTALL_WORDS.contains(first)) {
            boolean frozen = rest.stream().allMatch(FROZEN_FLAGS::contains);
            return new Parsed(Parsed.Kind.INSTALL, null, frozen ? null : pm + " " + String.join(" ", tokens));
        }
        String all = pm + " " + String.join(" ", tokens);
        if ((first.equals("exec") || first.equals("dlx")) && !rest.isEmpty()) {
            List<String> argv = new ArrayList<>(rest);
            if (argv.get(0).equals("--")) argv.remove(0);
            return argv.isEmpty()
                    ? null
                    : new Parsed(Parsed.Kind.COMMAND, NodeTable.Command.npx(String.join(" ", argv)), null);
        }
        String script = null;
        List<String> extra = rest;
        if (first.equals("run") || first.equals("run-script")) {
            if (rest.isEmpty()) return null;
            script = rest.get(0);
            extra = rest.subList(1, rest.size());
        } else if (pm.equals("npm")
                ? Set.of("test", "t", "start").contains(first)
                : !MANAGER_BUILTINS.contains(first)) {
            script = first.equals("t") ? "test" : first;
        }
        if (script != null && extra.isEmpty()) {
            return new Parsed(Parsed.Kind.COMMAND, NodeTable.Command.run(script), null);
        }
        return new Parsed(Parsed.Kind.COMMAND, new NodeTable.Command(NodeTable.Command.Kind.EXEC, all), null);
    }

    /** The report rows for settings jk takes from elsewhere: mirrors, credentials, proxies, manager versions. */
    private static void reportSettings(
            @Nullable Xpp3Dom shared,
            @Nullable Xpp3Dom config,
            @Nullable String packageManager,
            ImportReport.Builder report,
            Set<String> reported) {
        String where = "`" + ARTIFACT + "`";
        for (Map.Entry<String, String> mirror : MIRRORS.entrySet()) {
            String url = setting(shared, config, mirror.getKey());
            if (url == null || !reported.add(mirror.getKey())) continue;
            report.warning(where + " `<" + mirror.getKey() + ">` is machine configuration jk does not take from a"
                    + " project: add `[node] " + mirror.getValue() + " = \"" + url + "\"` to ~/.jk/config.toml");
        }
        String server = setting(shared, config, "serverId");
        if (server != null && reported.add("serverId")) {
            report.warning(where + " `<serverId>" + server + "</serverId>`: jk takes the mirror's credentials from"
                    + " `jk repo login <host>`, JK_REPO_<HOST>_TOKEN, or the settings.xml <server> of a node or"
                    + " npm <mirror>");
        }
        for (String key : List.of(
                "npmInheritsProxyConfigFromMaven",
                "yarnInheritsProxyConfigFromMaven",
                "bowerInheritsProxyConfigFromMaven")) {
            if (EnvValues.parseBool(setting(shared, config, key)).map(b -> !b).orElse(false) && reported.add(key)) {
                report.warning(where + " `<" + key + ">false`: jk hands every package manager the proxy it uses"
                        + " itself; a host that must go direct belongs in no-proxy");
            }
        }
        for (String key : MANAGER_VERSIONS) {
            String v = setting(shared, config, key);
            if (v == null || v.equals("provided") || packageManager != null || !reported.add(key)) continue;
            String pm = key.substring(0, key.length() - "Version".length());
            report.warning(where + " `<" + key + ">" + v + "`: jk takes the manager's version from package.json —"
                    + " add `\"packageManager\": \""
                    + (pm.equals("corepack") ? "<manager>@<version>" : pm + "@" + stripV(v)) + "\"`");
        }
    }

    private static boolean isTrue(@Nullable String raw) {
        return EnvValues.parseBool(raw).orElse(false);
    }

    private static @Nullable String setting(@Nullable Xpp3Dom shared, @Nullable Xpp3Dom config, String name) {
        String own = PluginFacts.child(config, name);
        return own != null ? own : PluginFacts.child(shared, name);
    }

    private static Map<String, String> env(@Nullable Xpp3Dom config) {
        Map<String, String> out = new LinkedHashMap<>();
        Xpp3Dom vars = config == null ? null : config.getChild("environmentVariables");
        if (vars == null) return out;
        for (Xpp3Dom var : vars.getChildren()) {
            String value = PluginFacts.usable(var.getValue());
            if (value != null) out.put(var.getName(), value);
        }
        return out;
    }

    private static List<EnvDecl> envDecls(Map<String, String> env) {
        List<EnvDecl> out = new ArrayList<>();
        env.forEach((k, v) -> out.add(new EnvDecl.Set(k, v)));
        return out;
    }

    /** {@code workingDirectory} relative to the module, with a leading {@code ${basedir}/} dropped. */
    private static String workingDirectory(@Nullable String raw) {
        if (raw == null) return ".";
        String p = raw.replace('\\', '/');
        for (String prefix : List.of("${basedir}/", "${project.basedir}/", "./")) {
            if (p.startsWith(prefix)) p = p.substring(prefix.length());
        }
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "." : p;
    }

    /** The plugin's exact version is a {@code node = "=x.y.z"} pin; a bare major stays a floor. */
    static String nodeVersion(String raw) {
        String v = stripV(raw);
        return v.indexOf('.') > 0 && v.chars().filter(c -> c == '.').count() == 2 ? "=" + v : v.split("\\.")[0];
    }

    private static String stripV(String v) {
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    private static int phase(@Nullable String phase) {
        int i = phase == null ? -1 : PHASES.indexOf(phase.trim().toLowerCase(Locale.ROOT));
        return i < 0 ? PHASES.indexOf("generate-resources") : i;
    }

    private static String stepName(@Nullable String id) {
        String s = id == null ? "step" : id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "step" : s;
    }

    /** {@code package.json}'s {@code packageManager}, or null. */
    static @Nullable String packageManager(Path dir) {
        try {
            Matcher m = PACKAGE_MANAGER.matcher(Files.readString(dir.resolve("package.json")));
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean yarnOne(Path dir, @Nullable String packageManager) {
        if (packageManager != null) return packageManager.startsWith("yarn@1");
        return Files.isRegularFile(dir.resolve("yarn.lock")) && !Files.isRegularFile(dir.resolve(".yarnrc.yml"));
    }
}
