// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
    record Record(
            String inputs,
            @Nullable String sources,
            Map<String, Set<String>> keysByTree,
            Map<String, String> digests,
            Map<String, String> jvmSources) {}

    /** The source files class facts are compiled from: what {@link #staleSources} compares one by one. */
    private static boolean jvmSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".groovy");
    }

    /** Each JVM source under the module's source roots, module-relative path to digest. */
    private static Map<String, String> jvmSources(Path moduleDir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (Path root : ModuleLayoutPlugins.fingerprintRoots(moduleDir, false)) {
            if (!Files.isDirectory(root)) continue;
            PathUtil.forEachRegularFile(root, (file, attrs) -> {
                if (!jvmSource(file)) return;
                Path abs = file.toAbsolutePath().normalize();
                out.put(rel(moduleDir, abs), FileHashMemo.contentHash(abs, attrs));
            });
        }
        return out;
    }

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
     * The module's JVM sources edited since the build whose classes sit in its target dir, as
     * module-relative paths (edited, added or gone); empty when the classes are the ones its
     * sources compile to. {@code null} when the classes cannot be vouched for at all: no record,
     * a record without per-file digests, or a class file the record does not name. A class whose
     * source is not in the set was compiled from that source as it stands, so its facts hold even
     * while another file of the module is mid-edit. The graph reads class facts on that basis.
     */
    public static @Nullable Set<String> staleSources(Path workspaceRoot, Path moduleDir, JkBuild build) {
        BuildLayout layout = BuildLayout.of(workspaceRoot, moduleDir, build);
        Path target = norm(layout.moduleTargetDir());
        Record record = read(target);
        if (record == null || record.sources() == null || record.jvmSources().isEmpty()) return null;
        try {
            for (Path tree : trees(layout)) {
                if (!tree.startsWith(target) || !Files.isDirectory(tree)) continue;
                boolean[] foreign = {false};
                PathUtil.forEachRegularFile(tree, (file, attrs) -> {
                    if (BuildStamps.isStampFile(file.getFileName().toString())) return;
                    String want = record.digests().get(rel(target, file));
                    if (want == null
                            || !want.equals(FileHashMemo.contentHash(
                                    file.toAbsolutePath().normalize(), attrs))) {
                        foreign[0] = true;
                    }
                });
                if (foreign[0]) return null;
            }
            if (record.sources().equals(PreflightMemo.sourceFingerprint(moduleDir))) return Set.of();
            Map<String, String> now = jvmSources(norm(moduleDir));
            Set<String> stale = new TreeSet<>();
            now.forEach((rel, sha) -> {
                if (!sha.equals(record.jvmSources().get(rel))) stale.add(rel);
            });
            for (String rel : record.jvmSources().keySet()) {
                if (!now.containsKey(rel)) stale.add(rel);
            }
            return Collections.unmodifiableSet(stale);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The output files the record vouches for, absolute path to digest, when it names {@code
     * fingerprint} as their inputs; empty otherwise.
     */
    public static Map<Path, String> recordedOutputs(
            Path workspaceRoot, Path moduleDir, JkBuild build, @Nullable String fingerprint) {
        Path target = norm(BuildLayout.of(workspaceRoot, moduleDir, build).moduleTargetDir());
        Record record = read(target);
        if (record == null || fingerprint == null || !record.inputs().equals(fingerprint)) return Map.of();
        Map<Path, String> out = new LinkedHashMap<>();
        record.digests().forEach((rel, sha) -> out.put(target.resolve(rel), sha));
        return out;
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
        // The guard salt is the workspace's, not the module's: one reading of it serves every module.
        Map<Path, String> saltByRoot = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            Path dir = unit.dir().toAbsolutePath().normalize();
            String fp = fingerprints.get(dir);
            if (fp == null || fp.isBlank()) continue;
            try {
                BuildLayout layout = BuildLayout.of(workspaceRoot, dir, unit.manifest());
                Path target = norm(layout.moduleTargetDir());
                StringBuilder sb = new StringBuilder("inputs ").append(fp).append('\n');
                String sources = PreflightMemo.sourceFingerprint(dir, saltByRoot);
                if (sources != null) sb.append("sources ").append(sources).append('\n');
                jvmSources(dir).forEach((rel, sha) -> sb.append("src ")
                        .append(sha)
                        .append(' ')
                        .append(rel)
                        .append('\n'));
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
            String sources = null;
            Map<String, Set<String>> keys = new LinkedHashMap<>();
            Map<String, String> digests = new LinkedHashMap<>();
            Map<String, String> jvmSources = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("inputs ")) {
                    inputs = line.substring("inputs ".length()).strip();
                } else if (line.startsWith("sources ")) {
                    sources = line.substring("sources ".length()).strip();
                } else if (line.startsWith("key ")) {
                    String[] p = line.substring("key ".length()).split(" ", 2);
                    if (p.length != 2) return null;
                    keys.computeIfAbsent(p[0], k -> new LinkedHashSet<>()).add(p[1]);
                } else if (line.startsWith("out ")) {
                    String[] p = line.substring("out ".length()).split(" ", 2);
                    if (p.length != 2 || !FileHashMemo.isSha256(p[0])) return null;
                    digests.put(p[1], p[0]);
                } else if (line.startsWith("src ")) {
                    String[] p = line.substring("src ".length()).split(" ", 2);
                    if (p.length != 2 || !FileHashMemo.isSha256(p[0])) return null;
                    jvmSources.put(p[1], p[0]);
                } else if (!line.isBlank()) {
                    return null;
                }
            }
            return inputs == null || inputs.isEmpty() ? null : new Record(inputs, sources, keys, digests, jvmSources);
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
