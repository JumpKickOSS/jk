// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.validate.TierPartition;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The build model as the guard-test runtime's {@code Model} reads it: one JSON document the engine
 * writes before forking the suite, so the forked JVM parses no manifest and resolves nothing.
 */
public final class GuardModelSnapshot {

    private GuardModelSnapshot() {}

    public static void write(Path root, List<Path> modules, Path out) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, MiniJson.write(snapshot(root, modules)));
    }

    static Map<String, Object> snapshot(Path root, List<Path> modules) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        Map<String, Object> deps = new LinkedHashMap<>();
        Map<String, Object> java = new LinkedHashMap<>();
        String kotlin = null;
        TreeSet<String> repositories = new TreeSet<>();
        List<Path> all = new ArrayList<>();
        all.add(root);
        for (Path m : modules)
            if (!m.toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize())) all.add(m);
        WorkspaceModel model = WorkspaceModel.of(root, modules);
        for (Path dir : all) {
            Path manifest = ManifestPaths.manifestIn(dir);
            if (!Files.isRegularFile(manifest)) continue;
            String rel = WorkspaceModel.rel(root, dir);
            JkBuild build;
            try {
                build = rel.isEmpty() ? JkBuildParser.parse(manifest) : JkBuildParser.parseLocal(manifest);
            } catch (RuntimeException unparseable) {
                continue;
            }
            names.add(rel);
            Map<String, Object> byScope = new LinkedHashMap<>();
            for (var e : build.dependencies().byScope().entrySet()) {
                List<Object> list = new ArrayList<>();
                for (Dependency d : e.getValue()) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("coordinate", d.isWorkspace() ? workspaceTarget(model, d) : d.module());
                    one.put("version", d.isWorkspace() ? "" : d.version().raw());
                    one.put("workspace", d.isWorkspace());
                    list.add(one);
                }
                byScope.put(e.getKey().tomlSection(), list);
            }
            deps.put(rel, byScope);
            String release = ToolchainEvaluator.release(build);
            if (release != null) java.put(rel, Integer.parseInt(release));
            if (rel.isEmpty() && build.project().kotlin() != null)
                kotlin = build.project().kotlin().raw();
            for (RepositorySpec r : build.repositories()) repositories.add(r.name());
        }
        doc.put("modules", names);
        doc.put("deps", deps);
        List<Object> lock = new ArrayList<>();
        Path lockFile = root.resolve(ManifestPaths.LOCK);
        if (Files.isRegularFile(lockFile)) {
            Lockfile lf = LockfileReader.read(lockFile);
            for (Lockfile.Artifact a : lf.artifacts()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("coordinate", DependEvaluator.ga(a.name()));
                one.put("version", a.version());
                String source = a.source() == null ? "" : a.source();
                int plus = source.indexOf('+');
                one.put("repository", plus < 0 ? source : source.substring(0, plus));
                List<String> scopes = new ArrayList<>();
                for (Scope s : a.scopes()) scopes.add(s.tomlSection());
                one.put("scopes", scopes);
                lock.add(one);
            }
        }
        doc.put("lock", lock);
        Map<String, Object> tiers = new LinkedHashMap<>();
        List<Object> tierList = new ArrayList<>();
        for (TierPartition.Tier t : TierPartition.table(root).tiers()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", t.name());
            one.put("include", new ArrayList<>(t.include()));
            one.put("exclude", new ArrayList<>(t.exclude()));
            tierList.add(one);
        }
        tiers.put("tiers", tierList);
        doc.put("tiers", tiers);
        Map<String, Object> toolchain = new LinkedHashMap<>();
        toolchain.put("java", java);
        toolchain.put("kotlin", kotlin);
        toolchain.put("repositories", new ArrayList<>(repositories));
        doc.put("toolchain", toolchain);
        return doc;
    }

    private static String workspaceTarget(WorkspaceModel model, Dependency d) {
        String name = Dependency.workspaceName(d.module());
        String rel = name == null ? null : model.moduleNamed(name);
        return rel == null ? String.valueOf(name) : rel;
    }
}
