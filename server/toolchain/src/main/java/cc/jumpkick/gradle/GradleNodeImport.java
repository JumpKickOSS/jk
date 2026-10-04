// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.NodeImportMapping;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * gradle-node-plugin ({@value #PLUGIN}): its {@code node { version }} is the module's Node.js, and
 * the {@code package.json} its {@code nodeProjectDir} names is a node build — the module itself
 * when it has no JVM sources, else a side-by-side {@code [node] dir}. Read from the build script's
 * text, which the Gradle model does not carry.
 */
final class GradleNodeImport {

    private GradleNodeImport() {}

    static final String PLUGIN = "com.github.node-gradle.node";

    private static final Pattern NODE_BLOCK = Pattern.compile("(?m)^\\s*node\\s*\\{");
    private static final Pattern VERSION = Pattern.compile("\\bversion\\s*(?:=|\\.set\\()\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PROJECT_DIR =
            Pattern.compile("\\bnodeProjectDir\\s*(?:=|\\.set\\()\\s*(?:file\\(\\s*)?[\"']([^\"']+)[\"']");

    private static final List<String> JVM_ROOTS =
            List.of("src/main/java", "src/main/kotlin", "src/main/groovy", "src/main/scala");

    /** The node build of the project in {@code dir}, or null when it does not apply the plugin. */
    static NodeImportMapping.@Nullable Mapped map(Path dir, Set<String> applied, ImportReport.Builder report) {
        if (!applied.contains(PLUGIN)) return null;
        String block = nodeBlock(script(dir));
        String version = group(VERSION, block);
        String nodeDir = group(PROJECT_DIR, block);
        Path ui = nodeDir == null ? dir : dir.resolve(nodeDir).normalize();
        if (!Files.isRegularFile(ui.resolve("package.json"))) {
            report.warning("plugin `" + PLUGIN + "` is applied but `" + (nodeDir == null ? "." : nodeDir)
                    + "/package.json` is missing: no node build is written.");
            return null;
        }
        boolean jvm = JVM_ROOTS.stream().anyMatch(r -> Files.isDirectory(dir.resolve(r)));
        if (!jvm) return new NodeImportMapping.Mapped(NodeImportMapping.spec(version, ui), null);
        if (nodeDir == null || ui.equals(dir)) {
            report.warning("plugin `" + PLUGIN + "` builds a package.json beside the JVM sources: move the front end"
                    + " into src/main/node (a [node] build beside them) or a module of its own.");
            return null;
        }
        String rel = dir.relativize(ui).toString().replace('\\', '/');
        return new NodeImportMapping.Mapped(
                NodeImportMapping.spec(version, ui), NodeImportMapping.sideBySide(rel, null, null));
    }

    private static String script(Path dir) {
        for (String name : List.of("build.gradle.kts", "build.gradle")) {
            Path f = dir.resolve(name);
            if (!Files.isRegularFile(f)) continue;
            try {
                return Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }
        return "";
    }

    /** The text of the script's {@code node { … }} block, braces balanced; empty when there is none. */
    static String nodeBlock(String script) {
        Matcher m = NODE_BLOCK.matcher(script);
        if (!m.find()) return "";
        int depth = 1;
        for (int i = m.end(); i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return script.substring(m.end(), i);
        }
        return script.substring(m.end());
    }

    private static @Nullable String group(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
