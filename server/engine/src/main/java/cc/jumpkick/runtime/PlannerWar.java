// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerSupport.assemblyDependencyJars;
import static cc.jumpkick.runtime.PlannerSupport.contributionsToken;
import static cc.jumpkick.runtime.PlannerSupport.packagedDirs;
import static cc.jumpkick.runtime.PlannerSupport.pluginDeclarationsFor;
import static cc.jumpkick.runtime.PlannerSupport.restorePackaged;
import static cc.jumpkick.runtime.PlannerSupport.stageClassesWithContributions;
import static cc.jumpkick.runtime.PlannerSupport.storePackaged;

import cc.jumpkick.compile.WarPackager;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/** The {@code [war]} packaging tail: the exploded web archive and the {@code .war} beside the jar. */
final class PlannerWar {

    private PlannerWar() {}

    static Task warStep(Path cache, Path lockFile, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_WAR)
                .stage(BuildStage.PACKAGE)
                .label("War")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(() -> EffortWeights.assemblyWeight(Objects.requireNonNull(lockFile.getParent(), "lock dir")))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    BuildBlock.War war = Objects.requireNonNull(project.build().war(), "[war]");
                    Path classes = ctx.require(MAIN_CLASSES);
                    List<Path> contributed =
                            packagedDirs(pluginDeclarationsFor(project, layout, cache), layout, project);
                    // A node sibling's output is web content under its webapp root, not a jar in WEB-INF/lib.
                    List<Path> nodeJars = NodePackaging.nodeJars(layout.moduleRoot(), project);
                    List<Path> libs = new ArrayList<>();
                    for (Path lib : assemblyDependencyJars(layout.moduleRoot(), project, lockFile, cache)) {
                        if (!nodeJars.contains(lib.toAbsolutePath().normalize())) libs.add(lib);
                    }
                    Map<Path, String> webContent = new LinkedHashMap<>();
                    for (NodePackaging.WebContent content : NodePackaging.webContent(layout.moduleRoot(), project)) {
                        webContent.put(content.out(), content.root());
                    }
                    Path webapp = layout.moduleRoot().resolve(war.webapp());
                    Path warFile = layout.warFile(war);
                    Path exploded = layout.explodedWarDir(war);
                    Map<String, String> manifest = new LinkedHashMap<>(project.manifest());
                    project.applicationOpt()
                            .map(JkBuild.Application::main)
                            .filter(main -> !main.isBlank())
                            .ifPresent(main -> manifest.put(Attributes.Name.MAIN_CLASS.toString(), main));
                    List<String> tokens = List.of(
                            "classes:" + ClasspathFingerprint.entry(classes),
                            "contrib:" + contributionsToken(contributed),
                            "libs:" + ClasspathFingerprint.of(libs),
                            "webapp:" + (Files.isDirectory(webapp) ? ClasspathFingerprint.entry(webapp) : ""),
                            "web:" + webTokens(webContent),
                            "manifest:" + new TreeMap<>(manifest));
                    String task = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_WAR, warFile);
                    String key = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokens);
                    Path base = layout.artifactDir();
                    if (restorePackaged(cache, key, base)) {
                        pruneExploded(warFile, exploded);
                        ctx.label(warFile.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + warFile.getFileName());
                    classes = stageClassesWithContributions(ctx, classes, contributed, layout);
                    new WarPackager()
                            .packageWar(new WarPackager.WarRequest(
                                    classes,
                                    Files.isDirectory(webapp) ? webapp : null,
                                    libs,
                                    exploded,
                                    warFile,
                                    manifest,
                                    webContent));
                    ctx.put(BUILD_OUTCOME, "built");
                    List<Path> outputs = new ArrayList<>(files(exploded));
                    outputs.add(warFile);
                    storePackaged(cache, task, key, tokens, base, outputs, persist);
                    ctx.progress(1);
                })
                .build();
    }

    /** Each web content directory's fingerprint and the path it lands under. */
    private static String webTokens(Map<Path, String> webContent) throws IOException {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Path, String> e : webContent.entrySet()) {
            parts.add(
                    e.getValue() + "=" + (Files.isDirectory(e.getKey()) ? ClasspathFingerprint.entry(e.getKey()) : ""));
        }
        return String.join(";", parts);
    }

    /**
     * Delete the files of {@code exploded} that {@code warFile} has no entry for: a restore writes
     * the archive's files and leaves those an earlier, different build exploded.
     */
    static void pruneExploded(Path warFile, Path exploded) throws IOException {
        Set<String> entries = new HashSet<>();
        try (ZipFile zip = new ZipFile(warFile.toFile())) {
            zip.stream().forEach(e -> entries.add(e.getName()));
        }
        for (Path file : files(exploded)) {
            String name = exploded.relativize(file).toString().replace(File.separatorChar, '/');
            if (!entries.contains(name)) Files.deleteIfExists(file);
        }
    }

    private static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> found = Files.find(dir, Integer.MAX_VALUE, (p, attrs) -> attrs.isRegularFile())) {
            return found.sorted().toList();
        }
    }

    /** Whether {@code project} packages a war. */
    static boolean declared(JkBuild project) {
        return project.build().war() != null;
    }
}
