// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.model.ToolchainSpec;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * The node build an imported module carries: its Node.js and, for a front end beside JVM sources,
 * the {@code [node]} table that places it. Shared by the Gradle node plugin and Quarkus Quinoa
 * mappings, for Maven and Gradle builds alike.
 */
public final class NodeImportMapping {

    private NodeImportMapping() {}

    /** Quinoa's keys in {@code application.properties}. */
    static final String QUINOA = "quarkus.quinoa.";

    /** Quinoa's default front-end directory. */
    static final String QUINOA_UI_DIR = "src/main/webui";

    /**
     * What to write: {@code spec} as the module's {@code node}, and {@code table} (null for a
     * dedicated node module, which needs none).
     */
    public record Mapped(ToolchainSpec spec, @Nullable NodeTable table) {}

    /** {@code build} with {@code mapped} written into it; {@code build} itself when nothing was mapped. */
    public static JkBuild apply(JkBuild build, @Nullable Mapped mapped) {
        if (mapped == null) return build;
        JkBuild out = build.withProject(build.project().withNodeSpec(mapped.spec()));
        return mapped.table() == null ? out : out.withBuild(out.build().withNode(mapped.table()));
    }

    /**
     * {@code version} as a spec: an exact {@code x.y.z} is a pin, anything else as written; null or
     * blank falls back to what {@code nodeDir}'s files suggest.
     */
    public static ToolchainSpec spec(@Nullable String version, Path nodeDir) {
        String v = version == null ? "" : version.trim();
        if (v.startsWith("v")) v = v.substring(1);
        if (v.isEmpty())
            return ToolchainSpec.parse("node", NodeShape.propose(nodeDir).spec());
        return ToolchainSpec.parse("node", v.matches("\\d+\\.\\d+\\.\\d+") ? "=" + v : v);
    }

    /** A side-by-side {@code [node]} table: the build in {@code dir}, its output under {@code classpathRoot}. */
    public static NodeTable sideBySide(String dir, @Nullable String classpathRoot, @Nullable String out) {
        return new NodeTable(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                out,
                classpathRoot,
                null,
                null,
                null,
                dir,
                false,
                List.of(),
                Map.of());
    }

    /**
     * Quarkus Quinoa: a module whose {@code application.properties} sets {@code quarkus.quinoa.*} (or
     * which holds {@value #QUINOA_UI_DIR}) builds its UI dir side by side, served from {@code
     * META-INF/resources} as Quinoa serves it. Null when the module has no Quinoa UI.
     */
    public static @Nullable Mapped quinoa(Path moduleDir, ImportReport.Builder report) {
        Properties props = properties(moduleDir.resolve("src/main/resources/application.properties"));
        boolean declared = props.stringPropertyNames().stream().anyMatch(k -> k.startsWith(QUINOA));
        String uiDir = props.getProperty(QUINOA + "ui-dir", QUINOA_UI_DIR).trim();
        Path ui = moduleDir.resolve(uiDir).normalize();
        if (!declared && !Files.isRegularFile(ui.resolve("package.json"))) return null;
        if (!Files.isRegularFile(ui.resolve("package.json"))) {
            report.warning(
                    "Quinoa is configured but `" + uiDir + "/package.json` is missing: no node build is" + " written.");
            return null;
        }
        String version = props.getProperty(QUINOA + "package-manager-install.node-version");
        String buildDir = props.getProperty(QUINOA + "build-dir");
        report.warning("Quinoa's UI in `" + uiDir + "` is a node build beside the Java sources ([node] dir), served"
                + " from META-INF/resources.");
        return new Mapped(
                spec(version, ui), sideBySide(uiDir, "META-INF/resources", buildDir == null ? null : buildDir.trim()));
    }

    private static Properties properties(Path file) {
        Properties props = new Properties();
        if (!Files.isRegularFile(file)) return props;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return new Properties();
        }
        return props;
    }
}
