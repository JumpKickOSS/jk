// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.PackageIo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipOutputStream;

/**
 * The {@code intellij-plugin} packager: the zip an IDE installs from disk. It holds one directory
 * named after the module, whose {@code lib/} carries the module's classes as a jar — {@code
 * plugin.xml} patched by {@link PluginXml} — and every runtime dependency's jar. The distribution's
 * jars are provided by the IDE and never packaged.
 */
final class PluginZip {

    static final String PLUGIN_XML = "META-INF/plugin.xml";

    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

    private PluginZip() {}

    static void produce(PackageIo io) throws Exception {
        String name = io.project().name();
        String version = io.project().version();
        String platform = io.config().string("version");
        String since = io.config().stringOpt("since-build").orElse(null);
        if (since == null) since = ProductInfo.branch(platform);
        if (since == null) {
            throw new IllegalArgumentException("[intellij] version = \"" + platform + "\" is not an IntelliJ build"
                    + " number; write the build IntelliJ IDEA's About dialog shows (IC-262.10968.63 is"
                    + " version = \"262.10968.63\"), or set since-build");
        }
        String until = io.config().stringOpt("until-build").orElse(null);

        Path pluginXml = io.classesDir().resolve(PLUGIN_XML);
        if (!Files.isRegularFile(pluginXml)) {
            throw new IllegalStateException("no " + PLUGIN_XML + " in the module's resources — an IntelliJ plugin"
                    + " is described by src/main/resources/" + PLUGIN_XML);
        }
        byte[] patched = PluginXml.patch(Files.readString(pluginXml, StandardCharsets.UTF_8), version, since, until)
                .getBytes(StandardCharsets.UTF_8);

        Path zip = io.artifactPath();
        Files.createDirectories(zip.getParent());
        String jarName = name + "-" + version + ".jar";
        Path jar = zip.resolveSibling(jarName);
        io.label(jarName);
        writeJar(io.classesDir(), jar, patched);

        io.label(zip.getFileName().toString());
        String lib = name + "/lib/";
        Set<String> written = new LinkedHashSet<>();
        try (ZipOutputStream out = new ZipOutputStream(DeterministicZip.archiveStream(zip))) {
            ZIP.writeEntry(out, lib + jarName, jar);
            written.add(jarName);
            for (PackageIo.RuntimeEntry entry : io.runtimeEntries()) {
                Path dep = entry.jar();
                if (dep == null) continue;
                if (!written.add(entry.fileName())) continue;
                ZIP.writeEntry(out, lib + entry.fileName(), dep);
            }
        }
        io.produced(jar);
    }

    /** Jar {@code classesDir} with {@code plugin.xml} replaced by {@code pluginXml}; no build stamps. */
    static void writeJar(Path classesDir, Path jar, byte[] pluginXml) throws IOException {
        // Entries in the order of their names, not of Path, which compares without case on Windows:
        // the same classes make the same jar on every host.
        Map<String, Path> files = new TreeMap<>();
        PathUtil.forEachRegularFile(
                classesDir,
                (file, attrs) ->
                        files.put(classesDir.relativize(file).toString().replace('\\', '/'), file));
        try (ZipOutputStream out = new ZipOutputStream(DeterministicZip.archiveStream(jar))) {
            for (Map.Entry<String, Path> file : files.entrySet()) {
                String rel = file.getKey();
                if (BuildStamps.isStampFile(rel) || isScratch(rel)) continue;
                if (rel.equals(PLUGIN_XML)) ZIP.writeEntry(out, rel, pluginXml);
                else ZIP.writeEntry(out, rel, file.getValue());
            }
        }
    }

    /** A {@code .jk-*} plugin scratch path: build bookkeeping, not plugin content. */
    private static boolean isScratch(String rel) {
        return rel.startsWith(".jk-") || rel.contains("/.jk-");
    }
}
