// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.runtime.base.DokkaResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Forks the Dokka CLI over a module's Kotlin (and Java) sources. The run is described by Dokka's
 * JSON configuration file, one source set over the module's source files with the compile classpath
 * and the plugin classpath the output format needs. Offline: no package-list is fetched for the
 * JDK or the stdlib, so the output is a function of the inputs and needs no network.
 */
public final class DokkaTool {

    private static final long TIMEOUT_MINUTES = 30;

    private DokkaTool() {}

    /** Document {@code sources} into {@code outDir}, which must exist and be empty. */
    public static JavadocTool.Result run(
            Path javaHome,
            DokkaResolver.Tool tool,
            Path outDir,
            String moduleName,
            String moduleVersion,
            List<Path> sources,
            List<Path> classpath,
            int jdkVersion,
            Path workdir)
            throws IOException, InterruptedException {
        Path config = Files.createTempFile(outDir.getParent(), "dokka-", ".json");
        try {
            Files.writeString(
                    config,
                    configJson(tool, outDir, moduleName, moduleVersion, sources, classpath, jdkVersion),
                    StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(
                            JdkFingerprint.java(javaHome).toString(),
                            "-jar",
                            tool.cli().toAbsolutePath().toString(),
                            config.toAbsolutePath().toString())
                    .directory(workdir.toFile())
                    .redirectErrorStream(true);
            Process proc = JobWorkers.start(pb);
            byte[] captured;
            try (var in = proc.getInputStream()) {
                captured = in.readAllBytes();
            }
            if (!proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                throw new IOException("dokka timed out after " + TIMEOUT_MINUTES + " minutes");
            }
            return new JavadocTool.Result(
                    proc.exitValue(), List.of(), List.of(), new String(captured, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    /** Dokka's configuration file: one JVM source set over {@code sources}, plugins from {@code tool}. */
    static String configJson(
            DokkaResolver.Tool tool,
            Path outDir,
            String moduleName,
            String moduleVersion,
            List<Path> sources,
            List<Path> classpath,
            int jdkVersion) {
        Map<String, Object> sourceSet = new LinkedHashMap<>();
        sourceSet.put("displayName", "jvm");
        sourceSet.put("sourceSetID", Map.of("scopeId", moduleName, "sourceSetName", "main"));
        sourceSet.put("sourceRoots", absolute(sources));
        sourceSet.put("classpath", absolute(classpath));
        sourceSet.put("analysisPlatform", "jvm");
        sourceSet.put("jdkVersion", jdkVersion);
        sourceSet.put("skipEmptyPackages", true);
        sourceSet.put("reportUndocumented", false);
        sourceSet.put("noStdlibLink", true);
        sourceSet.put("noJdkLink", true);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("moduleName", moduleName);
        config.put("moduleVersion", moduleVersion);
        config.put("outputDir", outDir.toAbsolutePath().toString());
        config.put("offlineMode", true);
        config.put("pluginsClasspath", absolute(tool.plugins()));
        config.put("sourceSets", List.of(sourceSet));
        return MiniJson.writePretty(config);
    }

    private static List<String> absolute(List<Path> paths) {
        List<String> out = new ArrayList<>(paths.size());
        for (Path p : paths) out.add(p.toAbsolutePath().toString());
        return out;
    }
}
