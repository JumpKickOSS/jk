// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
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
 * {@code ~/.jk/config.toml} repository leaking into the lock, and is named as such).
 */
final class ToolchainEvaluator implements Evaluator {

    /** One manifest as read: {@code module} is {@code ""} for the root. */
    record Manifest(String module, JkBuild build) {
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
        Path rootManifest = ctx.root().resolve(ManifestPaths.MANIFEST);
        if (Files.isRegularFile(rootManifest)) manifests.add(new Manifest("", JkBuildParser.parse(rootManifest)));
        for (Path m : ctx.modules()) {
            Path f = m.resolve(ManifestPaths.MANIFEST);
            if (Files.isRegularFile(f) && !f.equals(rootManifest)) {
                // The member as written, not as inherited: what the root says is judged once, at the root.
                manifests.add(new Manifest(WorkspaceModel.rel(ctx.root(), m), JkBuildParser.parseLocal(f)));
            }
        }
        Path lockFile = ctx.root().resolve(ManifestPaths.LOCK);
        Lockfile lock = Files.isRegularFile(lockFile) ? LockfileReader.read(lockFile) : null;
        return evaluate(rule, manifests, lock);
    }

    /** The mutation-time entry: {@code proposed} is the manifest text about to be written for {@code module}. */
    static Evaluation evaluateProposed(Rule rule, String module, String proposed, @Nullable Lockfile lock) {
        // The lock is what the last resolution did, not what this edit does: its arm is the build's.
        return evaluate(rule, List.of(new Manifest(module, JkBuildParser.parse(proposed))), null);
    }

    static Evaluation evaluate(Rule rule, List<Manifest> manifests, @Nullable Lockfile lock) {
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
            if (java != null) {
                String release = release(m.build());
                if (release != null && !VersionRanges.satisfies(release, java)) {
                    out.add(Observation.site(
                            "java " + m.where(),
                            m.file(),
                            0,
                            m.where() + " builds for Java " + release + ", outside " + java));
                }
            }
            if (kotlin != null && m.build().project().kotlin() != null) {
                String raw = m.build().project().kotlin().raw();
                String version = raw.replaceFirst("^[\\^~=]+", "");
                if (!VersionRanges.satisfies(version, kotlin)) {
                    out.add(Observation.site(
                            "kotlin " + m.where(),
                            m.file(),
                            0,
                            m.where() + " uses Kotlin " + raw + ", outside " + kotlin));
                }
            }
            for (PluginDeclaration p : m.build().plugins()) {
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
            for (RepositorySpec r : m.build().repositories()) {
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
