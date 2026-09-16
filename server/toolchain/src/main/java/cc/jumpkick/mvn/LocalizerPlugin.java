// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Resource;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * {@code localizer-maven-plugin}: a {@code Messages} class for every {@code Messages.properties}
 * under the resource directories, generated into {@code target/generated-sources/localizer}. The
 * plugin ships a Maven mojo and an Ant task and no {@code main}, so no {@code [generate]} entry
 * can run it; the module gets a Tier-3 row naming the classes its sources import, and an
 * {@code add-source} root inside the plugin's output is that output, not an {@code extra-src}.
 */
final class LocalizerPlugin {

    static final String ARTIFACT = "localizer-maven-plugin";

    /** What an {@code add-source} root inside the output is, for the build-helper row. */
    static final String ADD_SOURCE_ROW =
            "the localizer plugin's output, which nothing fills under jk, so no `extra-src` root is written";

    private static final String DEFAULT_OUTPUT = "target/generated-sources/localizer";
    private static final String DEFAULT_MASK = "Messages.properties";
    private static final String MAIN_RESOURCES = "src/main/resources";
    private static final int NAMED = 5;

    private LocalizerPlugin() {}

    /**
     * The module-relative output root the plugin fills, after the row is written; empty when the
     * POM has no localizer plugin.
     */
    static Optional<String> outputRoot(Model model, ImportReport.Builder report) {
        Optional<Plugin> plugin = PluginFacts.plugin(model, ARTIFACT);
        if (plugin.isEmpty()) return Optional.empty();
        Path baseDir = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        String mask = DEFAULT_MASK;
        String output = DEFAULT_OUTPUT;
        for (Xpp3Dom config : PluginFacts.configurations(plugin.get())) {
            String declaredMask = PluginFacts.child(config, "fileMask");
            if (declaredMask != null) mask = declaredMask;
            String declaredOutput = PluginFacts.child(config, "outputDirectory");
            if (declaredOutput != null) output = SourceTreePlugins.moduleRelative(declaredOutput, baseDir);
        }
        List<String> resourceDirs = resourceDirs(model, baseDir);
        List<String> classes = baseDir == null ? List.of() : classes(baseDir, resourceDirs, mask);
        report.error("`" + ARTIFACT + "` generates " + describe(classes) + " from `" + mask + "` files under "
                + String.join(", ", resourceDirs) + " into `" + output + "`, and the plugin has no `main` a"
                + " `[generate]` entry could run (a Maven mojo and an Ant task only), so a source importing those"
                + " classes does not compile under jk. Run `jk mvn generate-sources` once, move `" + output
                + "` to a source root of its own (`src/generated/java`, listed in `[build] extra-src`) and check"
                + " it in, or keep the module under `jk mvn`.");
        return Optional.of(output);
    }

    /** The module-relative main resource directories the plugin scans, the layout's when the POM lists none. */
    private static List<String> resourceDirs(Model model, @Nullable Path baseDir) {
        List<String> dirs = new ArrayList<>();
        if (model.getBuild() != null) {
            for (Resource resource : model.getBuild().getResources()) {
                String raw = resource.getDirectory();
                if (raw == null || raw.isBlank()) continue;
                String dir = SourceTreePlugins.moduleRelative(raw.trim(), baseDir);
                if (!dirs.contains(dir)) dirs.add(dir);
            }
        }
        if (dirs.isEmpty()) dirs.add(MAIN_RESOURCES);
        return dirs;
    }

    /** The generated classes by qualified name: the file's directory is the package, its stem the class. */
    private static List<String> classes(Path baseDir, List<String> resourceDirs, String mask) {
        Pattern file = Pattern.compile(Pattern.quote(mask).replace("*", "\\E.*\\Q"));
        List<String> classes = new ArrayList<>();
        for (String resourceDir : resourceDirs) {
            Path root = baseDir.resolve(resourceDir);
            try {
                PathUtil.forEachRegularFile(root, (path, attrs) -> {
                    if (file.matcher(path.getFileName().toString()).matches()) classes.add(className(root, path));
                });
            } catch (IOException e) {
                // An unreadable resource tree names no classes; the row still names the plugin.
            }
        }
        Collections.sort(classes);
        return classes;
    }

    private static String className(Path root, Path file) {
        String name = file.getFileName().toString();
        int dot = name.indexOf('.');
        String simple = dot > 0 ? name.substring(0, dot) : name;
        Path dir = file.getParent();
        String pkg = dir == null || dir.equals(root)
                ? ""
                : root.relativize(dir).toString().replace('/', '.').replace('\\', '.');
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    private static String describe(List<String> classes) {
        if (classes.isEmpty()) return "a `Messages` class per directory";
        List<String> named = classes.subList(0, Math.min(NAMED, classes.size()));
        String text = "`" + String.join("`, `", named) + "`";
        int more = classes.size() - named.size();
        return more > 0 ? text + " and " + more + " more" : text;
    }
}
