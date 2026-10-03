// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.model.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Whether a module has a node build, and where. A dedicated node module holds {@code package.json}
 * in its own directory and no JVM sources; a JVM module holds one beside its sources in {@code
 * src/main/node} (or {@code [node] dir}). A {@code package.json} at a JVM module's root that no
 * {@code [node]} table asks for is the repository's tooling and is ignored. Workspace roots and
 * modules built from a {@code pom.xml} have no node build, and neither does a module that declares no
 * {@code node} version and writes no {@code [node]} table.
 */
public final class NodeShape {

    /** The node build a module has. */
    public enum Kind {
        /** None. */
        NONE,
        /** The module is a node build: no JVM sources, {@code package.json} at its root. */
        MODULE,
        /** A JVM module with a node build in {@link #nodeDir}. */
        SIDE_BY_SIDE
    }

    /** The version proposed when no ecosystem file names one. */
    static final int DEFAULT_MAJOR = 24;

    private NodeShape() {}

    /** The node build {@code build} (inheritance applied) has in {@code moduleDir}. */
    public static Kind kind(JkBuild build, Path moduleDir) {
        if (exempt(build, moduleDir)) return Kind.NONE;
        if (build.project().nodeSpec().isEmpty() && !build.declaresNodeTable()) return Kind.NONE;
        NodeTable table = build.node();
        if (!jvmSources(moduleDir) && hasPackageJson(moduleDir.resolve(table.dir() == null ? "." : table.dir()))) {
            return Kind.MODULE;
        }
        Path side = moduleDir.resolve(sideBySideDir(table));
        return hasPackageJson(side) ? Kind.SIDE_BY_SIDE : Kind.NONE;
    }

    /** The directory holding the node build's {@code package.json}; {@code null} for none. */
    public static @Nullable Path nodeDir(JkBuild build, Path moduleDir) {
        return switch (kind(build, moduleDir)) {
            case NONE -> null;
            case MODULE ->
                moduleDir
                        .resolve(build.node().dir() == null ? "." : build.node().dir())
                        .normalize();
            case SIDE_BY_SIDE -> moduleDir.resolve(sideBySideDir(build.node())).normalize();
        };
    }

    /**
     * True for a dedicated node module: {@code node} resolves, {@code package.json} at the module root,
     * no JVM sources. Such a module has no JVM compile.
     */
    public static boolean isNodeModule(Project project, Path moduleDir) {
        return !project.nodeSpec().isEmpty() && !jvmSources(moduleDir) && hasPackageJson(moduleDir);
    }

    /**
     * Refuse a node build jk cannot run, each with its one-line fix: {@code [node]} without a {@code
     * package.json}, a {@code [node]} table that would build at a JVM module's root, a node build with
     * no {@code node} declared, and whatever {@link NodeProject#infer} refuses (Yarn 1).
     */
    public static void check(JkBuild build, Path moduleDir) {
        if (exempt(build, moduleDir)) return;
        NodeTable table = build.node();
        String where = label(moduleDir);
        boolean jvm = jvmSources(moduleDir);
        if (jvm
                && build.declaresNodeTable()
                && table.dir() == null
                && hasPackageJson(moduleDir)
                && !hasPackageJson(moduleDir.resolve(NodeTable.SIDE_BY_SIDE_DIR))) {
            throw new JkBuildParseException(where + ": package.json sits beside the JVM sources — move the node"
                    + " build into " + NodeTable.SIDE_BY_SIDE_DIR + ", or set [node] dir");
        }
        Kind kind = kind(build, moduleDir);
        if (kind == Kind.NONE) {
            if (build.declaresNodeTable()) {
                String dir = jvm ? sideBySideDir(table) : table.dir() == null ? "." : table.dir();
                throw new JkBuildParseException(where + ": [node] is set but " + dir + "/package.json does not exist"
                        + " — add the node build there, or remove [node]");
            }
            return;
        }
        Path nodeDir = nodeDir(build, moduleDir);
        if (nodeDir == null) return;
        // A package.json with neither node nor [node] is not refused: the webapp template's web module
        // is such a module, a resources jar its app depends on.
        if (build.project().nodeSpec().isEmpty() && !build.declaresNodeTable()) return;
        if (build.project().nodeSpec().isEmpty()) {
            Proposal p = propose(nodeDir);
            throw new JkBuildParseException(
                    where + ": package.json found but no node toolchain is declared — add node = " + p.spec()
                            + (p.source() == null ? "" : " (from " + p.source() + ")"));
        }
        NodeProject.infer(nodeDir, table);
    }

    /** A {@code node =} value the ecosystem files in {@code dir} suggest, and the file that suggested it. */
    public record Proposal(String spec, @Nullable String source) {}

    /**
     * {@code .nvmrc}, {@code .node-version}, {@code package.json} {@code devEngines.runtime}, {@code
     * volta.node} and {@code engines.node}, in that order; the first that names a major or LTS wins,
     * else {@value #DEFAULT_MAJOR}.
     */
    public static Proposal propose(Path dir) {
        for (String file : List.of(".nvmrc", ".node-version")) {
            String text = read(dir.resolve(file));
            String spec = text == null ? null : spec(text);
            if (spec != null) return new Proposal(spec, file);
        }
        Object pkg = hasPackageJson(dir) ? NodeProject.packageJson(dir) : null;
        Object runtime = MiniJson.get(MiniJson.get(pkg, "devEngines"), "runtime");
        if (runtime instanceof List<?> list) {
            for (Object r : list) {
                if ("node".equals(MiniJson.str(r, "name"))) runtime = r;
            }
        }
        if (runtime instanceof Map<?, ?> && "node".equals(MiniJson.str(runtime, "name"))) {
            String spec = spec(String.valueOf(MiniJson.str(runtime, "version")));
            if (spec != null) return new Proposal(spec, "package.json devEngines.runtime");
        }
        String volta = MiniJson.str(MiniJson.get(pkg, "volta"), "node");
        String voltaSpec = volta == null ? null : spec(volta);
        if (voltaSpec != null) return new Proposal(voltaSpec, "package.json volta.node");
        String engines = MiniJson.str(MiniJson.get(pkg, "engines"), "node");
        String enginesSpec = engines == null ? null : spec(engines);
        if (enginesSpec != null) return new Proposal(enginesSpec, "package.json engines.node");
        return new Proposal(Integer.toString(DEFAULT_MAJOR), null);
    }

    private static final Pattern MAJOR = Pattern.compile("(\\d+)");

    /** A version text as a {@code node =} value: its first number as a major, or {@code "lts"}. */
    private static @Nullable String spec(String text) {
        String t = text.trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("lts")) return "\"lts\"";
        Matcher m = MAJOR.matcher(t);
        return m.find() ? m.group(1) : null;
    }

    private static boolean exempt(JkBuild build, Path moduleDir) {
        return build.isWorkspaceRoot() || ManifestPaths.isShadowed(moduleDir);
    }

    private static String sideBySideDir(NodeTable table) {
        return table.dir() == null ? NodeTable.SIDE_BY_SIDE_DIR : table.dir();
    }

    private static boolean hasPackageJson(Path dir) {
        return Files.isRegularFile(dir.resolve(NodeProject.PACKAGE_JSON));
    }

    /** Whether {@code dir} holds JVM sources a compile would read. */
    static boolean jvmSources(Path dir) {
        for (String lang : List.of("java", "kotlin", "groovy", "scala")) {
            if (Files.isDirectory(dir.resolve("src/main/" + lang))) return true;
        }
        Path src = dir.resolve("src");
        for (String ext : List.of(".java", ".kt", ".groovy", ".scala")) {
            if (Languages.anySourceUnder(src, ext)) return true;
        }
        return false;
    }

    private static String label(Path moduleDir) {
        Path name = moduleDir.toAbsolutePath().normalize().getFileName();
        return (name == null ? "" : name + "/") + ManifestPaths.MANIFEST;
    }

    private static @Nullable String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
