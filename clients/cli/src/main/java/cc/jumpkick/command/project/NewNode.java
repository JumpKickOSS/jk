// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.command.toolchain.NodeCli;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodeInstalls;
import cc.jumpkick.scaffold.NewGroupGuess;
import cc.jumpkick.scaffold.NodeGenerators;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk new --lang node -t <framework>}: the framework's own generator, run under the newest
 * Active LTS Node.js jk provisions, then the {@code jk.toml} that pins it. Also the {@code
 * --frontend} swap of a template's {@code web} module.
 */
final class NewNode {

    private NewNode() {}

    static final String WEDGE = "New";

    /**
     * The flags this mode reads.
     *
     * @param workspaceRoot the enclosing workspace, which the new module joins; null for a standalone project
     */
    record Args(
            @Nullable String framework,
            List<String> params,
            @Nullable String name,
            @Nullable String group,
            @Nullable Path directory,
            Path cwd,
            @Nullable Path workspaceRoot,
            boolean offline) {}

    static int apply(Args a) {
        Optional<NodeGenerators.Framework> framework = framework(a.framework());
        if (framework.isEmpty()) return Exit.USAGE;
        if (a.offline()) return offline(framework.get());
        String name = a.name() != null && !a.name().isBlank()
                ? a.name()
                : a.directory() != null && a.directory().getFileName() != null
                        ? a.directory().getFileName().toString()
                        : null;
        if (name == null || name.equals(".")) {
            CommandWedge.printFail(
                    WEDGE,
                    "name the project: jk new --lang node -t " + framework.get().id() + " <name>");
            return Exit.USAGE;
        }
        Path target = NewWizard.resolveTarget(a.directory(), a.cwd(), name);
        try {
            if (Files.exists(target) && !isEmpty(target)) {
                CommandWedge.printFail(WEDGE, target + " already exists and is not empty");
                return Exit.CONFIG;
            }
            int lts = newestLts();
            if (lts <= 0) return Exit.SOFTWARE;
            NodeHome home = NodeCli.home(a.cwd(), Integer.toString(lts)).orElseThrow();
            int exit = generate(home, framework.get(), target, a.params());
            if (exit != 0) return exit;
            Path root = a.workspaceRoot();
            boolean member = root != null;
            String group =
                    member ? null : a.group() != null && !a.group().isBlank() ? a.group() : NewGroupGuess.guess();
            Files.writeString(
                    ManifestPaths.manifestIn(target),
                    NodeGenerators.manifest(name, Integer.toString(lts), group, null),
                    StandardCharsets.UTF_8);
            ignore(target);
            if (root != null) join(root, target);
            CommandWedge.envelopeStart();
            CliOutput.out(JkWedge.chipLine(
                    Glyphs.CHECK,
                    "New Project",
                    GlobalConfig.nerdFont(),
                    "Created " + name + " (" + framework.get().id() + ", node = " + lts + ") → " + target));
            return Exit.SUCCESS;
        } catch (IOException e) {
            CommandWedge.printFail(WEDGE, e.getMessage());
            return Exit.SOFTWARE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Exit.SOFTWARE;
        }
    }

    /**
     * Replace {@code webDir}, a template's node module, with {@code framework}'s generator output,
     * keeping its {@code jk.toml}.
     */
    static int replaceFrontend(Path webDir, String frameworkId, List<String> params, boolean offline) {
        Optional<NodeGenerators.Framework> framework = framework(frameworkId);
        if (framework.isEmpty()) return Exit.USAGE;
        if (offline) return offline(framework.get());
        Path manifest = ManifestPaths.manifestIn(webDir);
        if (!Files.isRegularFile(manifest) || !Files.isRegularFile(webDir.resolve("package.json"))) {
            CommandWedge.printFail(WEDGE, "--frontend needs a template with a node web/ module; this one has none");
            return Exit.USAGE;
        }
        try {
            String toml = Files.readString(manifest, StandardCharsets.UTF_8);
            int lts = newestLts();
            if (lts <= 0) return Exit.SOFTWARE;
            NodeHome home = NodeCli.home(webDir, Integer.toString(lts)).orElseThrow();
            PathUtil.deleteRecursively(webDir);
            int exit = generate(home, framework.get(), webDir, params);
            if (exit != 0) return exit;
            Files.writeString(manifest, toml, StandardCharsets.UTF_8);
            ignore(webDir);
            return Exit.SUCCESS;
        } catch (IOException e) {
            CommandWedge.printFail(WEDGE, e.getMessage());
            return Exit.SOFTWARE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Exit.SOFTWARE;
        }
    }

    private static Optional<NodeGenerators.Framework> framework(@Nullable String id) {
        Optional<NodeGenerators.Framework> f = NodeGenerators.find(id);
        if (f.isEmpty()) {
            CommandWedge.printFail(
                    WEDGE,
                    (id == null || id.isBlank() ? "name a framework with -t" : "unknown framework `" + id + "`")
                            + "; one of: " + NodeGenerators.ids());
        }
        return f;
    }

    private static int offline(NodeGenerators.Framework f) {
        CommandWedge.printFail(
                WEDGE, "the " + f.id() + " generator comes from the npm registry — run without --offline");
        return Exit.CONFIG;
    }

    private static int newestLts() {
        int lts = NodeInstalls.newestLtsMajor(NodeCli.releasesOrNone());
        if (lts <= 0) {
            CommandWedge.printFail(
                    WEDGE, "the Node.js release index could not be read — `jk node list-remote` says why");
        }
        return lts;
    }

    /** Run the generator beside {@code target}, then install when it left no lockfile. */
    private static int generate(NodeHome home, NodeGenerators.Framework f, Path target, List<String> params)
            throws IOException, InterruptedException {
        Path parent = Objects.requireNonNull(target.toAbsolutePath().getParent(), "target has a parent");
        Files.createDirectories(parent);
        String dir = String.valueOf(target.getFileName());
        int exit = NodeCli.exec(home, NodeGenerators.argv(f, dir, params), parent);
        if (exit != 0) {
            CommandWedge.printFail(WEDGE, "the " + f.id() + " generator exited with " + exit);
            return exit;
        }
        if (!Files.isRegularFile(target.resolve("package.json"))) {
            CommandWedge.printFail(WEDGE, "the " + f.id() + " generator wrote no package.json in " + target);
            return Exit.SOFTWARE;
        }
        if (NodeGenerators.LOCKFILES.stream().noneMatch(l -> Files.exists(target.resolve(l)))) {
            exit = NodeCli.exec(home, List.of("npm", "install", "--no-audit", "--no-fund"), target);
            if (exit != 0) {
                CommandWedge.printFail(WEDGE, "npm install exited with " + exit);
                return exit;
            }
        }
        return Exit.SUCCESS;
    }

    /** {@code target/} and {@code node_modules/} in the module's {@code .gitignore}. */
    private static void ignore(Path dir) throws IOException {
        Path file = dir.resolve(".gitignore");
        String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        List<String> lines = text.lines().map(String::trim).toList();
        StringBuilder add = new StringBuilder();
        for (String entry : List.of("node_modules/", "target/")) {
            String bare = entry.substring(0, entry.length() - 1);
            if (!lines.contains(entry) && !lines.contains(bare) && !lines.contains("/" + bare)) {
                add.append(entry).append('\n');
            }
        }
        if (add.isEmpty()) return;
        String sep = text.isEmpty() || text.endsWith("\n") ? "" : "\n";
        Files.writeString(file, text + sep + add, StandardCharsets.UTF_8);
    }

    /** Register {@code module} in {@code root}'s {@code [workspace] modules}. */
    static void join(Path root, Path module) throws IOException {
        Path manifest = ManifestPaths.manifestIn(root);
        String rel = root.toAbsolutePath()
                .normalize()
                .relativize(module.toAbsolutePath().normalize())
                .toString();
        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        Files.writeString(manifest, JkBuildEditor.registerWorkspaceModule(text, rel), StandardCharsets.UTF_8);
    }

    private static boolean isEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        boolean[] any = {false};
        PathUtil.forEachChild(dir, (child, attrs) -> {
            any[0] = true;
            return false;
        });
        return !any[0];
    }
}
