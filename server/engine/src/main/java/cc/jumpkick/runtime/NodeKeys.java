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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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
            String nodeToken,
            List<String> later)
            throws IOException {
        Map<String, String> inputs = tree(nodeDir, withOut(project, later));
        inputs.put("command:", command.kind().key() + " " + command.value());
        inputs.put("out:", project.out());
        envInputs(inputs, env, project.envPrefixes());
        inputs.put("installed:", installed);
        inputs.put("node:", nodeToken);
        return keyed(TaskNames.NODE_BUILD, nodeDir, inputs);
    }

    /**
     * A {@code [[node.steps]]} entry's key: the files its {@code inputs} name, or with none the node
     * tree less {@code out} and every step's outputs; its command, the environment, the install and
     * Node.
     * {@code fetched} is the {@code pkg@version} an unlocked npx runs, else {@code null}.
     */
    static Keyed step(
            Path nodeDir,
            Path moduleDir,
            NodeProject project,
            NodeTable.Step step,
            List<String> outputs,
            Map<String, String> env,
            String installed,
            String nodeToken,
            @Nullable String fetched)
            throws IOException {
        // A step that names what it reads is keyed on that alone; one that names nothing reads the tree.
        Map<String, String> inputs = step.inputs().isEmpty() ? tree(nodeDir, outputs) : new TreeMap<>();
        inputs.putAll(inputFiles(moduleDir, step.inputs()));
        inputs.put(
                "command:", step.command().kind().key() + " " + step.command().value());
        inputs.put("outputs:", String.join(",", step.outputs()));
        if (fetched != null) inputs.put("fetched:", fetched);
        envInputs(inputs, env, project.envPrefixes());
        inputs.put("installed:", installed);
        inputs.put("node:", nodeToken);
        return keyed(stepTask(step), nodeDir, inputs);
    }

    private static List<String> withOut(NodeProject project, List<String> later) {
        List<String> all = new ArrayList<>(later);
        all.add(project.out());
        return all;
    }

    /** The task a {@code [[node.steps]]} entry runs as. */
    static String stepTask(NodeTable.Step step) {
        return TaskNames.NODE_STEP_PREFIX + step.name();
    }

    /**
     * The files {@code patterns} name, relative to {@code moduleDir}, keyed {@code input:<path>}: a
     * file, every file under a directory, or a glob's matches; a path that names nothing is
     * {@code absent}, so its appearance re-runs the step.
     */
    static Map<String, String> inputFiles(Path moduleDir, List<String> patterns) throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (String pattern : patterns) {
            String norm = pattern.replace('\\', '/');
            int glob = firstGlob(norm);
            if (glob < 0) {
                Path target = moduleDir.resolve(norm).normalize();
                if (Files.isRegularFile(target)) {
                    files.put(INPUT + norm, FileHashMemo.contentHash(target));
                } else if (Files.isDirectory(target)) {
                    PathUtil.forEachRegularFile(
                            target,
                            dir -> NEVER_INPUT.contains(String.valueOf(dir.getFileName())),
                            (file, attrs) -> files.put(
                                    INPUT + norm + "/" + rel(target, file), FileHashMemo.contentHash(file, attrs)));
                } else {
                    files.put(INPUT + norm, "absent");
                }
                continue;
            }
            int slash = norm.lastIndexOf('/', glob);
            Path base = moduleDir
                    .resolve(slash < 0 ? "." : norm.substring(0, slash))
                    .normalize();
            if (!Files.isDirectory(base)) continue;
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + norm);
            PathUtil.forEachRegularFile(
                    base, dir -> NEVER_INPUT.contains(String.valueOf(dir.getFileName())), (file, attrs) -> {
                        String rel = rel(moduleDir, file);
                        if (matcher.matches(Path.of(rel)))
                            files.put(INPUT + rel, FileHashMemo.contentHash(file, attrs));
                    });
        }
        return files;
    }

    private static final String INPUT = "input:";

    private static int firstGlob(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            if ("*?[{".indexOf(pattern.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    /** {@code file} relative to {@code dir}, {@code ../} where it lies outside, with forward slashes. */
    private static String rel(Path dir, Path file) {
        return dir.toAbsolutePath()
                .normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace(File.separatorChar, '/');
    }

    /**
     * {@code node-test}'s key: as {@link #build}'s, with the test script for the command. {@code
     * later}, here and in {@link #build}: the outputs of steps that run after this one, which it
     * never reads.
     */
    static Keyed test(
            Path nodeDir,
            NodeProject project,
            String script,
            Map<String, String> env,
            String installed,
            String nodeToken,
            List<String> later)
            throws IOException {
        Map<String, String> inputs = tree(nodeDir, withOut(project, later));
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
     * (files or directories relative to {@code nodeDir}) left out.
     */
    static Map<String, String> tree(Path nodeDir, List<String> outputs) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Set<Path> skipped = new HashSet<>();
        for (String out : outputs) skipped.add(nodeDir.resolve(out).normalize());
        PathUtil.forEachRegularFile(
                nodeDir,
                dir -> NEVER_INPUT.contains(String.valueOf(dir.getFileName())) || skipped.contains(dir.normalize()),
                (file, attrs) -> {
                    if (skipped.contains(file.normalize())) return;
                    files.put(
                            nodeDir.relativize(file).toString().replace(File.separatorChar, '/'),
                            FileHashMemo.contentHash(file, attrs));
                });
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
            else if (k.startsWith(INPUT)) named.add(k.substring(INPUT.length()));
            else if (INSTALL_FILES.contains(k)) named.add(k);
            else files++;
        }
        List<String> parts = new ArrayList<>();
        if (files > 0) parts.add(files + (files == 1 ? " file changed" : " files changed"));
        if (!named.isEmpty()) parts.add(String.join(", ", named) + " changed");
        return String.join(" · ", parts);
    }
}
