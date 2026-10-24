// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.runtime.base.ProvidedJars;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A module's plugin-provided classpath ({@code [[contribute.provided-classpath]]}): step tools on
 * the compile and test classpaths, never on the runtime or in an artifact — whole, or globbed out
 * of a directory tool such as an unpacked distribution.
 */
public final class ProvidedClasspath {

    private ProvidedClasspath() {}

    /**
     * The resolved {@code [[contribute.provided-classpath]]} entries, split by where they join:
     * {@code compile} reaches the main compile and both test classpaths, {@code testOnly} the test
     * classpaths alone, {@code compile-only} the two compile classpaths alone, {@code test-runtime}
     * the test JVM's alone. An entry with {@code jars} globs is every regular file they match under its
     * tool's directory, in glob order and path order within a glob; one without is the tool's own
     * path. The entry names its tool itself, so only the named tools are fetched, whatever their
     * scope.
     */
    static Provided contributed(JkBuild project, BuildPlanner.Inputs in, Cas cas, PluginBuild.StepTools tools) {
        try {
            // A test-runtime entry is fetched only for a build that runs tests: a distribution that
            // only the test JVM reads is no cost to a build that compiles.
            return providedClasspath(project, in.dir(), in.lockFile(), cas, tools, !PlannerResources.skipJUnit(in));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("cannot resolve the plugin-contributed compile classpath: " + e.getMessage(), e);
        }
    }

    /**
     * The forecast's view of {@link #contributed}: the same entries resolved the same way, so a
     * compile key priced before the build names the jars the build's compile reads. A tool that
     * does not resolve leaves the forecast without it, which keys a compile the build would not
     * make — a predicted miss, never a false hit.
     */
    static Provided forecast(JkBuild project, Path moduleDir, Path lockFile, Cas cas, boolean withTestRuntime) {
        try {
            return providedClasspath(project, moduleDir, lockFile, cas, new PluginBuild.StepTools(), withTestRuntime);
        } catch (IOException | RuntimeException e) {
            Log.debug("provided classpath of " + moduleDir + " left out of the forecast", e);
            return Provided.NONE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Provided.NONE;
        }
    }

    /**
     * The model's view of {@link #contributed}: each module's provided jars named
     * for an IDE ({@code <tool>/<path in it>} for a globbed jar, the tool's artifact name for a
     * tool that is one jar). A module whose tools do not resolve lists none.
     */
    public static ProvidedJars providedJars(Cas cas) {
        return (moduleDir, module) -> {
            try {
                Provided provided = providedClasspath(
                        module, moduleDir, LockPaths.lockFile(moduleDir), cas, new PluginBuild.StepTools(), false);
                List<ProvidedJars.Jar> out = new ArrayList<>(provided.jars().size());
                // What only the test JVM runs with is not a library the IDE compiles against.
                for (Provided.Jar jar : provided.jars())
                    if (!jar.runtimeOnly()) out.add(new ProvidedJars.Jar(jar.name(), jar.path(), jar.testOnly()));
                return out;
            } catch (IOException | RuntimeException e) {
                Log.debug("provided jars of " + moduleDir + " left out of the IDE model", e);
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        };
    }

    private static Provided providedClasspath(
            JkBuild project,
            Path moduleDir,
            Path lockFile,
            Cas cas,
            PluginBuild.StepTools tools,
            boolean withTestRuntime)
            throws IOException, InterruptedException {
        List<PluginContributions.Provided> entries = PluginContributions.providedClasspath(project, moduleDir).stream()
                .filter(entry -> withTestRuntime || !entry.runtimeOnly())
                .toList();
        if (entries.isEmpty()) return Provided.NONE;
        Set<String> names = new LinkedHashSet<>();
        for (PluginContributions.Provided entry : entries) names.add(entry.dependency());
        Map<String, Path> fetched =
                tools.fetch(tools.named(project, moduleDir, lockFile, names), project, cas, lockFile);
        List<Provided.Jar> jars = new ArrayList<>();
        for (PluginContributions.Provided entry : entries) {
            Path path = fetched.get(entry.dependency());
            if (path == null) {
                throw new RuntimeException("[[contribute.provided-classpath]] names `" + entry.dependency()
                        + "` but no step-dependency resolved under that artifact name");
            }
            if (entry.jars().isEmpty()) {
                jars.add(new Provided.Jar(
                        entry.dependency(), path, entry.testOnly(), entry.compileOnly(), entry.runtimeOnly()));
                continue;
            }
            for (String glob : entry.jars()) {
                for (Path jar : matching(path, glob)) {
                    jars.add(new Provided.Jar(
                            entry.dependency() + "/" + relativeSlashed(path, jar),
                            jar,
                            entry.testOnly(),
                            entry.compileOnly(),
                            entry.runtimeOnly()));
                }
            }
        }
        return new Provided(List.copyOf(jars));
    }

    /** A plugin's provided classpath, each jar with the name an IDE lists it under. */
    record Provided(List<Jar> jars) {
        static final Provided NONE = new Provided(List.of());

        record Jar(String name, Path path, boolean testOnly, boolean compileOnly, boolean runtimeOnly) {}

        /** On every compile classpath. */
        List<Path> compile() {
            return jars.stream()
                    .filter(j -> !j.testOnly() && !j.runtimeOnly())
                    .map(Jar::path)
                    .toList();
        }

        /** On the test JVM's classpath whatever the test sources are: not test-only, compile-only or none. */
        List<Path> runtime() {
            return jars.stream()
                    .filter(j -> !j.testOnly() && !j.compileOnly())
                    .map(Jar::path)
                    .toList();
        }

        /** The jars the test JVM runs with: {@link #runtime} and the test-only ones. */
        List<Path> testRuntime() {
            return jars.stream().filter(j -> !j.compileOnly()).map(Jar::path).toList();
        }

        /** On the test classpaths alone. */
        List<Path> testOnly() {
            return jars.stream().filter(Jar::testOnly).map(Jar::path).toList();
        }
    }

    /**
     * The regular files under {@code dir} whose forward-slash relative path matches {@code glob}
     * ({@code *} within one segment, {@code **} across them), path-sorted.
     */
    static List<Path> matching(Path dir, String glob) throws IOException {
        Pattern pattern = Pattern.compile(globRegex(glob));
        List<Path> out = new ArrayList<>();
        PathUtil.forEachRegularFile(dir, (file, attrs) -> {
            if (pattern.matcher(relativeSlashed(dir, file)).matches()) out.add(file);
        });
        out.sort(Comparator.comparing(p -> relativeSlashed(dir, p)));
        return out;
    }

    private static String relativeSlashed(Path dir, Path file) {
        return dir.relativize(file).toString().replace('\\', '/');
    }

    /** {@code glob} as a regex over forward-slash paths. */
    static String globRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }
}
