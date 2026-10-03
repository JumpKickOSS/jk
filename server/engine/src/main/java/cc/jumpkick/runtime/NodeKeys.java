// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FileHashMemo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The action keys of a node build's steps, one body for the build and the forecast. A key hashes
 * the inputs its step reads; the inputs map is what the record keeps, so {@code jk explain} can name
 * the one that moved. Environment values enter hashed, never as written.
 */
final class NodeKeys {

    /** Files beside {@code package.json} that change what an install puts in {@code node_modules}. */
    static final List<String> INSTALL_FILES = List.of(
            NodeProject.PACKAGE_JSON,
            "package-lock.json",
            "npm-shrinkwrap.json",
            "pnpm-lock.yaml",
            "pnpm-workspace.yaml",
            ".pnpmfile.cjs",
            "yarn.lock",
            ".yarnrc.yml",
            "bun.lock",
            "bun.lockb",
            "bunfig.toml",
            ".npmrc");

    /** The lockfiles a frozen install needs; one must be present. */
    static final List<String> LOCKFILES =
            List.of("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb");

    /** Directories of a node tree that are no step's input: installs, caches and other tools' output. */
    static final Set<String> NEVER_INPUT = Set.of(
            "node_modules",
            OutputDirs.TARGET,
            ".git",
            ".svelte-kit",
            ".angular",
            ".turbo",
            ".cache",
            ".parcel-cache",
            ".nx",
            "coverage");

    /** One step's key, the task id it is stored under, and the inputs the record keeps. */
    record Keyed(String taskId, String key, Map<String, String> inputs) {}

    private NodeKeys() {}

    /** {@code node-install}'s key: the package files, the install command, Node and the platform. */
    static Keyed install(Path nodeDir, @Nullable String installOverride, String nodeToken) throws IOException {
        Map<String, String> inputs = new TreeMap<>();
        for (String name : INSTALL_FILES) {
            Path file = nodeDir.resolve(name);
            if (Files.isRegularFile(file)) inputs.put(name, FileHashMemo.contentHash(file));
        }
        inputs.put("node:", nodeToken);
        inputs.put("platform:", NodePlatform.host().key());
        inputs.put("install:", installOverride == null ? "frozen" : installOverride);
        return keyed(TaskNames.NODE_INSTALL, nodeDir, inputs);
    }

    /**
     * {@code node-build}'s key: the node tree (less {@code out} and {@link #NEVER_INPUT}), the
     * command, the environment the build reads, the install it built against and Node.
     */
    static Keyed build(
            Path nodeDir,
            NodeProject project,
            NodeTable.Command command,
            Map<String, String> env,
            String installed,
            String nodeToken)
            throws IOException {
        Map<String, String> inputs = tree(nodeDir, List.of(project.out()));
        inputs.put("command:", command.kind().key() + " " + command.value());
        inputs.put("out:", project.out());
        envInputs(inputs, env, project.envPrefixes());
        inputs.put("installed:", installed);
        inputs.put("node:", nodeToken);
        return keyed(TaskNames.NODE_BUILD, nodeDir, inputs);
    }

    /** {@code node-test}'s key: as {@link #build}'s, with the test script for the command. */
    static Keyed test(
            Path nodeDir,
            NodeProject project,
            String script,
            Map<String, String> env,
            String installed,
            String nodeToken)
            throws IOException {
        Map<String, String> inputs = tree(nodeDir, List.of(project.out()));
        inputs.put("test:", script);
        envInputs(inputs, env, project.envPrefixes());
        inputs.put("installed:", installed);
        inputs.put("node:", nodeToken);
        return keyed(TaskNames.NODE_TEST, nodeDir, inputs);
    }

    /** {@code node-package}'s key: the output's files, the root they sit under and the manifest attributes. */
    static Keyed pkg(Path nodeDir, Path out, String root, Map<String, String> manifest) throws IOException {
        Map<String, String> inputs = tree(out, List.of());
        inputs.put("root:", root);
        inputs.put("manifest:", new TreeMap<>(manifest).toString());
        return keyed(TaskNames.NODE_PACKAGE, nodeDir, inputs);
    }

    /**
     * Every file under {@code nodeDir} by its path relative to it, valued by content: dot-files
     * included ({@code .env}, a framework's config), {@link #NEVER_INPUT} and {@code outputs}
     * (paths relative to {@code nodeDir}) left out.
     */
    static Map<String, String> tree(Path nodeDir, List<String> outputs) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Set<Path> skipped = new HashSet<>();
        for (String out : outputs) skipped.add(nodeDir.resolve(out).normalize());
        PathUtil.forEachRegularFile(
                nodeDir,
                dir -> NEVER_INPUT.contains(String.valueOf(dir.getFileName())) || skipped.contains(dir.normalize()),
                (file, attrs) -> files.put(
                        nodeDir.relativize(file).toString().replace(File.separatorChar, '/'),
                        FileHashMemo.contentHash(file, attrs)));
        return files;
    }

    /**
     * {@code NODE_ENV}, {@code CI} and every variable under the frameworks' public prefixes or the
     * build's own, hashed: what {@link NodeEnv} hands the process from the request.
     */
    private static void envInputs(Map<String, String> inputs, Map<String, String> env, List<String> prefixes) {
        List<String> all = new ArrayList<>(BuildEnv.NODE_PREFIXES);
        all.addAll(prefixes);
        for (Map.Entry<String, String> e : env.entrySet()) {
            String name = e.getKey();
            boolean read = name.equals("NODE_ENV") || name.equals("CI");
            for (String prefix : all) read |= name.startsWith(prefix);
            if (read) inputs.put("env:" + name, Hashing.sha256Hex(e.getValue()).substring(0, 16));
        }
    }

    private static Keyed keyed(String task, Path nodeDir, Map<String, String> inputs) {
        String taskId = ActionKey.qualifiedTaskId(task, nodeDir);
        List<String> tokens = new ArrayList<>(inputs.size());
        for (Map.Entry<String, String> e : inputs.entrySet()) tokens.add(e.getKey() + "=" + e.getValue());
        return new Keyed(taskId, ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), tokens), inputs);
    }

    /** What changed between a step's last record and {@code now}, for {@code jk explain}; empty for nothing. */
    static String missReason(Map<String, String> prior, Map<String, String> now) {
        int files = 0;
        List<String> named = new ArrayList<>();
        Set<String> keys = new TreeSet<>(now.keySet());
        keys.addAll(prior.keySet());
        for (String k : keys) {
            if (Objects.equals(prior.get(k), now.get(k))) continue;
            if (k.endsWith(":")) named.add(k.substring(0, k.length() - 1));
            else if (k.startsWith("env:")) named.add("$" + k.substring(4));
            else if (INSTALL_FILES.contains(k)) named.add(k);
            else files++;
        }
        List<String> parts = new ArrayList<>();
        if (files > 0) parts.add(files + (files == 1 ? " file changed" : " files changed"));
        if (!named.isEmpty()) parts.add(String.join(", ", named) + " changed");
        return String.join(" · ", parts);
    }
}
