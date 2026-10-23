// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Which inputs the outputs sitting in a module's target dir were built from, and what those
 * outputs are.
 *
 * <p>Preflight asks two separate questions: "are this module's inputs unchanged?", answered by the
 * fingerprint memo, and "are its outputs the ones those inputs produce?". The second is not "do the
 * files exist": a jar built from other sources exists, a class an IDE or a hand edit replaced
 * exists, and a module skipped on that basis ships them behind a build that reports {@code all
 * modules up to date}. Content returning to a state the action cache already holds (a revert, a
 * rebase, a branch switch) leaves the same shape: every step hits and nothing is missing.
 *
 * <p>So the facts live where the outputs are: one record under the module's target dir, written
 * after a build that succeeded, naming the input fingerprint, the compile keys each classes tree
 * was stamped with, and the digest of every file in the classes trees and of the module's jars.
 * Preflight calls a module clean only when the record exists, names the current inputs, and every
 * file still there matches it with no file added (a file that is gone is a restore, judged by
 * {@link ModuleOutputs}); the digests come through {@link FileHashMemo}, so an
 * untouched file costs a stat. A missing or unreadable record schedules the module, whose steps
 * then restore by key.
 */
public final class ModuleInputProvenance {

    private ModuleInputProvenance() {}

    /** The stamps a classes tree can carry, one per compile whose outputs it holds. */
    private static final List<String> STAMPS = List.of(BuildStamps.JAVA, BuildStamps.KOTLIN, BuildStamps.GROOVY);

    /** {@code target/<module>/.jk/inputs} — beside the preflight memo's own {@code .jk} dir. */
    private static Path recordFile(Path targetDir) {
        return targetDir.resolve(".jk").resolve("inputs");
    }

    /**
     * What one record says: the inputs, each tree's stamped compile keys, and each output's digest,
     * paths relative to the target dir with {@code /} separators.
     */
    record Record(String inputs, Map<String, Set<String>> keysByTree, Map<String, String> digests) {}

    /** The trees and jars a record covers, relative to the module's target dir. */
    private static List<Path> trees(BuildLayout layout) {
        return List.of(norm(layout.classesDir()), norm(layout.testClassesDir()), norm(layout.testFixturesClassesDir()));
    }

    private static List<Path> jars(BuildLayout layout, JkBuild build) {
        List<Path> out = new ArrayList<>(List.of(norm(layout.mainJar())));
        if (build.assembly()) out.add(norm(layout.assemblyJar()));
        return out;
    }

    /**
     * True unless this module's outputs are known to be the ones {@code fingerprint} produces: the
     * record is there, names these inputs, and every recorded file still there has its digest, with
     * no file the record does not name in a classes tree.
     */
    public static boolean outputsFromOtherInputs(
            Path workspaceRoot, Path moduleDir, JkBuild build, @Nullable String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return false;
        BuildLayout layout = BuildLayout.of(workspaceRoot, moduleDir, build);
        Path target = norm(layout.moduleTargetDir());
        Record record = read(target);
        if (record == null || !record.inputs().equals(fingerprint)) return true;
        try {
            Set<String> seen = new LinkedHashSet<>();
            for (Path tree : trees(layout)) {
                if (!tree.startsWith(target)) continue;
                boolean[] foreign = {false};
                PathUtil.forEachRegularFile(tree, (file, attrs) -> {
                    String rel = rel(target, file);
                    String want = record.digests().get(rel);
                    if (BuildStamps.isStampFile(file.getFileName().toString())) return;
                    if (want == null
                            || !want.equals(FileHashMemo.contentHash(
                                    file.toAbsolutePath().normalize(), attrs))) {
                        foreign[0] = true;
                    }
                    seen.add(rel);
                });
                if (foreign[0]) return true;
            }
            for (Map.Entry<String, String> e : record.digests().entrySet()) {
                if (seen.contains(e.getKey())) continue;
                // An output that is gone is the restore's to bring back (ModuleOutputs); only
                // bytes that are not the recorded ones are evidence of other inputs.
                Path file = target.resolve(e.getKey());
                if (Files.isRegularFile(file) && !e.getValue().equals(FileHashMemo.contentHash(file))) return true;
            }
            return false;
        } catch (IOException | RuntimeException unreadable) {
            return true;
        }
    }

    /**
     * The digests a record gives the files of {@code tree} when it was taken under {@code key}, keyed
     * by path relative to the tree; empty when there is no record, or none for that key. A step that
     * rewrites a compile's classes in place (an enhancer) leaves files the compile record does not
     * describe; this is what tells them from foreign bytes.
     */
    public static Map<String, String> treeDigests(Path tree, String key) {
        Path abs = tree.toAbsolutePath().normalize();
        Path target = abs.getParent();
        if (target == null) return Map.of();
        Record record = read(target);
        if (record == null) return Map.of();
        String treeRel = rel(target, abs);
        if (!record.keysByTree().getOrDefault(treeRel, Set.of()).contains(key)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        String prefix = treeRel + "/";
        for (Map.Entry<String, String> e : record.digests().entrySet()) {
            if (e.getKey().startsWith(prefix)) out.put(e.getKey().substring(prefix.length()), e.getValue());
        }
        return out;
    }

    /** Record each built module's inputs and outputs. Best-effort: never fails a build. */
    public static void record(Path workspaceRoot, BuildGraph.Result graph, Map<Path, String> fingerprints) {
        if (graph == null || fingerprints == null || fingerprints.isEmpty()) return;
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            Path dir = unit.dir().toAbsolutePath().normalize();
            String fp = fingerprints.get(dir);
            if (fp == null || fp.isBlank()) continue;
            try {
                BuildLayout layout = BuildLayout.of(workspaceRoot, dir, unit.manifest());
                Path target = norm(layout.moduleTargetDir());
                StringBuilder sb = new StringBuilder("inputs ").append(fp).append('\n');
                Map<String, String> digests = new TreeMap<>();
                for (Path tree : trees(layout)) {
                    if (!tree.startsWith(target) || !Files.isDirectory(tree)) continue;
                    for (String stamp : STAMPS) {
                        String key = FreshnessStamp.stampedKey(tree, stamp).orElse(null);
                        if (key != null)
                            sb.append("key ")
                                    .append(rel(target, tree))
                                    .append(' ')
                                    .append(key)
                                    .append('\n');
                    }
                    PathUtil.forEachRegularFile(tree, (file, attrs) -> {
                        if (BuildStamps.isStampFile(file.getFileName().toString())) return;
                        digests.put(
                                rel(target, file),
                                FileHashMemo.contentHash(file.toAbsolutePath().normalize(), attrs));
                    });
                }
                for (Path jar : jars(layout, unit.manifest())) {
                    if (jar.startsWith(target) && Files.isRegularFile(jar)) {
                        digests.put(rel(target, jar), FileHashMemo.contentHash(jar));
                    }
                }
                digests.forEach((rel, sha) ->
                        sb.append("out ").append(sha).append(' ').append(rel).append('\n'));
                Path file = recordFile(target);
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                AtomicWrites.replaceIfChanged(file, sb.toString());
            } catch (IOException | RuntimeException unwritable) {
                // The next preflight finds no record and schedules the module: the honest answer
                // for outputs nothing vouched for.
                Log.debug("provenance: not recorded", "module", dir, "cause", unwritable);
            }
        }
    }

    private static @Nullable Record read(Path target) {
        Path file = recordFile(target);
        try {
            if (!Files.isRegularFile(file)) return null;
            String inputs = null;
            Map<String, Set<String>> keys = new LinkedHashMap<>();
            Map<String, String> digests = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("inputs ")) {
                    inputs = line.substring("inputs ".length()).strip();
                } else if (line.startsWith("key ")) {
                    String[] p = line.substring("key ".length()).split(" ", 2);
                    if (p.length != 2) return null;
                    keys.computeIfAbsent(p[0], k -> new LinkedHashSet<>()).add(p[1]);
                } else if (line.startsWith("out ")) {
                    String[] p = line.substring("out ".length()).split(" ", 2);
                    if (p.length != 2 || !FileHashMemo.isSha256(p[0])) return null;
                    digests.put(p[1], p[0]);
                } else if (!line.isBlank()) {
                    return null;
                }
            }
            return inputs == null || inputs.isEmpty() ? null : new Record(inputs, keys, digests);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static Path norm(Path p) {
        return p.toAbsolutePath().normalize();
    }

    private static String rel(Path target, Path file) {
        return target.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
