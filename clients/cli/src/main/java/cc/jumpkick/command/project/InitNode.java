// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.scaffold.NewGroupGuess;
import cc.jumpkick.scaffold.NodeGenerators;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk init} where a {@code package.json} sits: the directory becomes a node module pinned to
 * the Node.js its files suggest ({@link NodeShape#propose}), and in a monorepo root each
 * subdirectory holding one becomes a workspace member.
 */
final class InitNode {

    private InitNode() {}

    /** Directories never searched for a member's {@code package.json}. */
    private static final Set<String> SKIP = Set.of("node_modules", OutputDirs.TARGET, "build", "dist", "out");

    /** JVM source roots: a directory with one is a JVM module, not a node module. */
    private static final List<String> JVM_ROOTS =
            List.of("src/main/java", "src/main/kotlin", "src/main/groovy", "src/main/scala", "src/test/java");

    /**
     * Initialize {@code dir} as a node module when it holds a {@code package.json} and no JVM
     * sources; empty when it is not one, so the ordinary init runs.
     */
    static Optional<Integer> module(Path dir, @Nullable String name, @Nullable String group) throws IOException {
        if (Files.exists(ManifestPaths.manifestIn(dir)) || !isNodeDir(dir)) return Optional.empty();
        String resolved = name != null && !name.isBlank() ? name : leaf(dir);
        String g = group != null && !group.isBlank() ? group : NewGroupGuess.guess();
        NodeShape.Proposal p = write(dir, resolved, g);
        CliOutput.out(JkWedge.chipLine(
                Glyphs.CHECK,
                "Init",
                GlobalConfig.nerdFont(),
                "Initialized " + resolved + " as a node module · node = " + p.spec() + from(p)));
        return Optional.of(Exit.SUCCESS);
    }

    /** Subdirectories of {@code root}, two levels deep, that hold a {@code package.json} and no manifest. */
    static List<Path> members(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        collect(root, 1, out);
        return out;
    }

    /** Give each of {@code members} a node manifest and register it in {@code root}'s workspace. */
    static void addMembers(Path root, List<Path> members) throws IOException {
        for (Path m : members) {
            NodeShape.Proposal p = write(m, leaf(m), null);
            NewNode.join(root, m);
            CliOutput.out(JkWedge.chipLine(
                    Glyphs.CHECK,
                    "Init",
                    GlobalConfig.nerdFont(),
                    "Added " + root.relativize(m) + " as a node member · node = " + p.spec() + from(p)));
        }
    }

    private static void collect(Path dir, int depth, List<Path> out) throws IOException {
        List<Path> children = new ArrayList<>();
        PathUtil.forEachChild(dir, (child, attrs) -> {
            String n = String.valueOf(child.getFileName());
            if (attrs.isDirectory() && !n.startsWith(".") && !SKIP.contains(n)) children.add(child);
            return true;
        });
        children.sort(null);
        for (Path c : children) {
            if (Files.exists(ManifestPaths.manifestIn(c))) continue;
            if (isNodeDir(c)) out.add(c);
            else if (depth < 2) collect(c, depth + 1, out);
        }
    }

    private static boolean isNodeDir(Path dir) {
        if (!Files.isRegularFile(dir.resolve("package.json"))) return false;
        for (String root : JVM_ROOTS) {
            if (Files.isDirectory(dir.resolve(root))) return false;
        }
        return true;
    }

    private static NodeShape.Proposal write(Path dir, String name, @Nullable String group) throws IOException {
        NodeShape.Proposal p = NodeShape.propose(dir);
        try {
            Files.writeString(
                    ManifestPaths.manifestIn(dir),
                    NodeGenerators.manifest(name, p.spec(), group, p.source()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            CommandWedge.printFail("Init", e.getMessage());
            throw e;
        }
        return p;
    }

    private static String from(NodeShape.Proposal p) {
        return p.source() == null ? " (no version file; the default)" : " (from " + p.source() + ")";
    }

    private static String leaf(Path dir) {
        Path n = dir.toAbsolutePath().normalize().getFileName();
        return n == null ? "app" : n.toString();
    }
}
