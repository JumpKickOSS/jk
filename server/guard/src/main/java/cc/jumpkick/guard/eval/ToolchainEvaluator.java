// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceResolve;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * {@code toolchain}: the build environment the manifests declare. Model substrate — plan time and,
 * like {@code depend}, mutation time through {@link #evaluateProposed}. Arms: {@code java} (a
 * release range every manifest's {@code java} — else its {@code jdk} major — must satisfy, the root
 * and each member), {@code kotlin} (the same over {@code kotlin =}), {@code plugins.pinned} (no
 * plugin with a floating version — {@code latest}, a caret, a tilde, a range or a wildcard) and
 * {@code repositories.only} (every repository a manifest declares, and every source the lock
 * resolved through, is on the list; a lock source no manifest declares is a user-level
 * {@code ~/.jk/config.toml} repository leaking into the lock, and is named as such). A value a
 * member inherits from the root is judged at the root, or at the member when the rule's scope leaves
 * the root out ({@link Manifest#judgedBy}).
 */
final class ToolchainEvaluator implements Evaluator {

    /**
     * One manifest as read: {@code module} is {@code ""} for the root; {@code local} is the file as
     * written and {@code effective} adds what a member inherits from the root.
     */
    record Manifest(String module, JkBuild local, JkBuild effective) {
        /** The root, whose values are its own. */
        static Manifest root(JkBuild build) {
            return new Manifest("", build, build);
        }

        /** The member at {@code dir}, read from {@code local} as {@code jk guard} and {@code jk add} both read it. */
        static Manifest member(Path root, Path dir, JkBuild local) {
            return new Manifest(WorkspaceModel.rel(root, dir), local, WorkspaceResolve.applyWorkspace(dir, local));
        }

        /**
         * What {@code rule} judges of this manifest. A rule that judges the root judges an inherited
         * value there, once, so it reads each member as written; a rule whose scope leaves the root
         * out reads a member with what it inherits, or an inherited value would go unjudged.
         */
        JkBuild judgedBy(Rule rule) {
            return module.isEmpty() || rule.applies("") ? local : effective;
        }

        String file() {
            return (module.isEmpty() ? "" : module + "/") + ManifestPaths.MANIFEST;
        }

        String where() {
            return module.isEmpty() ? "root" : module;
        }
    }

    private static final Pattern FLOATING = Pattern.compile("latest|.*[\\^~<>*+,].*|.*\\.[xX]$|.*\\bRELEASE\\b.*");

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        List<Manifest> manifests = new ArrayList<>();
        Path rootManifest = ManifestPaths.manifestIn(ctx.root());
        if (Files.isRegularFile(rootManifest)) manifests.add(Manifest.root(JkBuildParser.parse(rootManifest)));
        for (Path m : ctx.modules()) {
            Path f = ManifestPaths.manifestIn(m);
            if (Files.isRegularFile(f) && !f.equals(rootManifest))
                manifests.add(Manifest.member(ctx.root(), m, JkBuildParser.parseLocal(f)));
        }
        Path lockFile = ctx.root().resolve(ManifestPaths.LOCK);
        Lockfile lock =
                DependEvaluator.readsLock(rule) && Files.isRegularFile(lockFile) ? LockfileReader.read(lockFile) : null;
        return judge(rule, manifests, lock);
    }

    /**
     * The mutation-time entry: {@code proposed} is the manifest text about to be written for {@code
     * module}, whose {@code jk.toml} is {@code file}. It is read as the build reads that file, with
     * what it inherits, and judged through {@link #judge}, so a rule scoped elsewhere never refuses
     * it and an inherited value is judged where {@code jk guard} judges it.
     */
    static Evaluation evaluateProposed(
            Rule rule, Path root, String module, Path file, String proposed, @Nullable Lockfile lock) {
        JkBuild local = JkBuildParser.parseLocal(proposed, file);
        Path dir = file.toAbsolutePath().normalize().getParent();
        Manifest m = module.isEmpty() || dir == null ? Manifest.root(local) : Manifest.member(root, dir, local);
        // The lock is what the last resolution did, not what this edit does: its arm is the build's.
        return judge(rule, List.of(m), null);
    }

    /**
     * {@code rule} over what it reads of the manifests and lock on offer: the manifests of the
     * modules in its scope, and the lock only when the rule is unscoped. The lock names no module,
     * and a lock source counts as declared when any manifest declares it, so a rule that reads only
     * some manifests cannot judge it honestly. The one place both the build's evaluation and the
     * mutation-time check decide what a rule sees.
     */
    static Evaluation judge(Rule rule, List<Manifest> manifests, @Nullable Lockfile lock) {
        List<Manifest> read =
                manifests.stream().filter(m -> rule.applies(m.module())).toList();
        return evaluate(rule, read, DependEvaluator.readsLock(rule) ? lock : null);
    }

    private static Evaluation evaluate(Rule rule, List<Manifest> manifests, @Nullable Lockfile lock) {
        TomlTable t = rule.table();
        String java = t.getString("java");
        String kotlin = t.getString("kotlin");
        TomlTable plugins = t.getTable("plugins");
        TomlTable repositories = t.getTable("repositories");
        boolean pinned = plugins != null && Boolean.TRUE.equals(plugins.getBoolean("pinned"));
        Set<String> only = null;
        if (repositories != null) {
            TomlArray arr = repositories.getArray("only");
            if (arr == null) return Evaluation.failed("repositories needs `only = [names]`");
            only = new TreeSet<>();
            for (int i = 0; i < arr.size(); i++) only.add(String.valueOf(arr.get(i)));
        }
        if (java != null && !VersionRanges.valid(java))
            return Evaluation.failed(
                    "java: `" + java + "` is not a version range (>=x, >x, <=x, <x, =x, comma-separated)");
        if (kotlin != null && !VersionRanges.valid(kotlin))
            return Evaluation.failed(
                    "kotlin: `" + kotlin + "` is not a version range (>=x, >x, <=x, <x, =x, comma-separated)");
        if (plugins != null && !pinned && plugins.isEmpty())
            return Evaluation.failed("plugins needs `pinned = true`; there is nothing else to ask of a plugin yet");
        if (manifests.isEmpty()) return Evaluation.notEvaluated("no manifest to read");

        List<Observation> out = new ArrayList<>();
        long pluginCount = 0;
        Set<String> declaredRepos = new TreeSet<>();
        for (Manifest m : manifests) {
            JkBuild build = m.judgedBy(rule);
            if (java != null) {
                String release = release(build);
                if (release != null && !VersionRanges.satisfies(release, java)) {
                    out.add(Observation.site(
                            "java " + m.where(),
                            m.file(),
                            0,
                            m.where() + " builds for Java " + release + ", outside " + java));
                }
            }
            if (kotlin != null && build.project().kotlin() != null) {
                String raw = build.project().kotlin().raw();
                String version = raw.replaceFirst("^[\\^~=]+", "");
                if (!VersionRanges.satisfies(version, kotlin)) {
                    out.add(Observation.site(
                            "kotlin " + m.where(),
                            m.file(),
                            0,
                            m.where() + " uses Kotlin " + raw + ", outside " + kotlin));
                }
            }
            for (PluginDeclaration p : build.plugins()) {
                pluginCount++;
                if (pinned && p.path() == null && FLOATING.matcher(p.version()).matches()) {
                    out.add(Observation.site(
                            "plugin:" + p.group() + ":" + p.name(),
                            m.file(),
                            0,
                            "plugin " + p.alias() + " (" + p.group() + ":" + p.name() + ") is at version \""
                                    + p.version() + "\", which floats; pin the version a release was tested with"));
                }
            }
            for (RepositorySpec r : build.repositories()) {
                declaredRepos.add(r.name());
                if (only != null && !only.contains(r.name())) {
                    out.add(Observation.site(
                            "repository:" + r.name(),
                            m.file(),
                            0,
                            m.where() + " declares repository `" + r.name() + "` (" + r.url() + "); allowed: " + only));
                }
            }
        }
        long artifacts = 0;
        if (only != null && lock != null) {
            Map<String, List<String>> bySource = new TreeMap<>();
            for (Lockfile.Artifact a : lock.artifacts()) {
                artifacts++;
                String source = a.source();
                if (source == null) continue;
                int plus = source.indexOf('+');
                String name = plus < 0 ? source : source.substring(0, plus);
                if (!only.contains(name) && !declaredRepos.contains(name)) {
                    bySource.computeIfAbsent(name, k -> new ArrayList<>())
                            .add(DependEvaluator.ga(a.name()) + "@" + a.version());
                }
            }
            for (var e : bySource.entrySet()) {
                List<String> arts = e.getValue();
                String sample = arts.size() <= 3
                        ? String.join(", ", arts)
                        : String.join(", ", arts.subList(0, 3)) + " and " + (arts.size() - 3) + " more";
                out.add(
                        Observation.site(
                                "repository:" + e.getKey(),
                                ManifestPaths.LOCK,
                                0,
                                "the lock resolved " + sample + " through repository `" + e.getKey()
                                        + "`, which no manifest declares and `only` does not allow — a ~/.jk/config.toml [repositories] entry leaking into the lock, or a stale lock; re-lock without it"));
            }
        }
        Map<String, Long> population = new LinkedHashMap<>();
        population.put("manifests", (long) manifests.size());
        population.put("plugins", pluginCount);
        population.put("artifacts", artifacts);
        return Evaluation.of(population, out);
    }

    /** The Java release a manifest builds for: {@code java}, else the {@code jdk} major; {@code null} when neither is declared. */
    static @Nullable String release(JkBuild build) {
        int java = build.project().java();
        if (java > 0) return Integer.toString(java);
        String jdk = build.project().jdk();
        if (jdk == null || jdk.isBlank()) return null;
        String major = jdk.split("[.+-]")[0];
        return major.chars().allMatch(Character::isDigit) ? major : null;
    }
}
