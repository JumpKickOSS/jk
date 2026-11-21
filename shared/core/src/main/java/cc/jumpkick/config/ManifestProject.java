// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;
import static cc.jumpkick.config.TomlTableReader.optionalString;

import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.model.JdkTable;
import cc.jumpkick.model.Layout;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.ProjectInherit;
import cc.jumpkick.model.SourcesMode;
import cc.jumpkick.model.ToolchainSpec;
import cc.jumpkick.model.VersionSelector;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * Top-level project identity keys on {@code jk.toml} for {@link JkBuildParser}: name, inheritance,
 * JDK/language versions. Keys live at the root (not under a {@code [project]} table).
 */
@NullMarked
public final class ManifestProject {

    /**
     * Known root-level project identity / toolchain keys. Used by unowned-table checks so Cargo-style
     * inherit tables ({@code group = { workspace = true }}) are not rejected as plugin tables.
     */
    static final Set<String> PROJECT_KEYS = Set.of(
            "name",
            "group",
            "version",
            "jdk",
            "node",
            "java",
            "kotlin",
            "groovy",
            "scala",
            "sources",
            "javadoc",
            "description",
            "layout");

    private ManifestProject() {}

    /**
     * Key position is load-bearing in TOML: an identity key typed below the first table header is a
     * member of that table, not of the project. When a required key is missing at the root but sits
     * directly under a root table, the error says where it went; the bare "missing" message sent
     * people hunting for a typo in a key that was right there.
     */
    static String strandedHint(TomlTable root, String key) {
        for (String table : root.keySet()) {
            if (root.get(List.of(table)) instanceof TomlTable t
                    && t.contains(key)
                    && !(t.get(key) instanceof TomlTable)) {
                return strandedSuffix(key, table);
            }
        }
        return "";
    }

    /** The three keys that make a project itself; a dependency cannot be called any of them. */
    static final Set<String> IDENTITY_KEYS = Set.of("name", "group", "version");

    static String strandedSuffix(String key, String table) {
        return "; found it under [" + table + "], move it above the first table";
    }

    /** The whole error for an identity key found as a member of {@code table}. */
    static String strandedIdentity(String key, String table) {
        return "jk.toml is missing required key `" + key + "`" + strandedSuffix(key, table);
    }

    /**
     * @param workspaceRoot when true, omitted optional fields keep local defaults (no inherit);
     *     group/name/version stay required and concrete. When false (module or standalone), omitted
     *     fields other than {@code name} and {@code description} mark workspace inheritance —
     *     members resolve them from the root; standalones drop optional inherits and still require
     *     concrete group+version (or fail if those were omitted).
     */
    static Project parseProject(TomlTable root, boolean workspaceRoot) {
        // name is the module identity — never workspace-inherited (Cargo package.name rule).
        if (root.isTable("name")) {
            throw new JkBuildParseException("name cannot use workspace = true; each module needs its own name");
        }
        if (!root.contains("name")) {
            throw new JkBuildParseException("jk.toml is missing required key `name`" + strandedHint(root, "name"));
        }
        java.util.EnumSet<ProjectInherit> inherits = java.util.EnumSet.noneOf(ProjectInherit.class);

        String name = requireString(root, "name", "name");

        String group = parseInheritableString(root, "group", ProjectInherit.GROUP, inherits, workspaceRoot);

        String version = parseInheritableString(root, "version", ProjectInherit.VERSION, inherits, workspaceRoot);

        ToolchainSpec jdkSpec;
        boolean declaresJdk = root.contains("jdk");
        if (isWorkspaceInherit(root, "jdk") || (!workspaceRoot && !declaresJdk)) {
            inherits.add(ProjectInherit.JDK);
            jdkSpec = ToolchainSpec.NONE;
        } else {
            jdkSpec = parseJdkToolchain(root);
        }

        ToolchainSpec nodeSpec = parseNodeToolchain(root);
        if (nodeSpec == null) {
            nodeSpec = ToolchainSpec.NONE;
            if (!workspaceRoot) inherits.add(ProjectInherit.NODE);
        }

        int java;
        if (isWorkspaceInherit(root, "java") || (!workspaceRoot && !root.contains("java"))) {
            inherits.add(ProjectInherit.JAVA);
            java = 0;
        } else {
            java = parseJavaRelease(root);
            requireCompilableRelease(java);
        }
        // A jdk of a lower major than java cannot build it; a higher one builds that level on it.
        if (java > 0 && !jdkSpec.isEmpty() && root.isTable("jdk")) parseJdkToolchain(root, java);

        VersionSelector kotlin;
        if (isWorkspaceInherit(root, "kotlin") || (!workspaceRoot && !root.contains("kotlin"))) {
            inherits.add(ProjectInherit.KOTLIN);
            kotlin = null;
        } else {
            kotlin = parseKotlinVersion(root);
        }

        VersionSelector groovy;
        if (isWorkspaceInherit(root, "groovy") || (!workspaceRoot && !root.contains("groovy"))) {
            inherits.add(ProjectInherit.GROOVY);
            groovy = null;
        } else {
            groovy = parseGroovyVersion(root);
        }

        VersionSelector scala;
        if (isWorkspaceInherit(root, "scala") || (!workspaceRoot && !root.contains("scala"))) {
            inherits.add(ProjectInherit.SCALA);
            scala = null;
        } else {
            scala = parseScalaVersion(root);
        }

        // sources = true → PUBLISH; sources = "always" → ALWAYS; absent/false → DISABLED
        // description is special: omit stays null (no auto-inherit). Explicit description.workspace = true ok.
        SourcesMode sourcesMode;
        if (isWorkspaceInherit(root, "sources") || (!workspaceRoot && !root.contains("sources"))) {
            inherits.add(ProjectInherit.SOURCES);
            sourcesMode = SourcesMode.DISABLED;
        } else {
            Object sourcesRaw = root.get("sources");
            if ("always".equalsIgnoreCase(sourcesRaw instanceof String s ? s : "")) {
                sourcesMode = SourcesMode.ALWAYS;
            } else if (Boolean.TRUE.equals(sourcesRaw)) {
                sourcesMode = SourcesMode.PUBLISH;
            } else {
                sourcesMode = SourcesMode.DISABLED;
            }
        }

        // javadoc = false → DISABLED; "strict" → STRICT; true → LENIENT; absent → PUBLISH
        JavadocMode javadocMode;
        if (isWorkspaceInherit(root, "javadoc") || (!workspaceRoot && !root.contains("javadoc"))) {
            inherits.add(ProjectInherit.JAVADOC);
            javadocMode = JavadocMode.PUBLISH;
        } else {
            javadocMode = parseJavadocMode(root.get("javadoc"));
        }

        String description;
        if (isWorkspaceInherit(root, "description")) {
            inherits.add(ProjectInherit.DESCRIPTION);
            description = null;
        } else {
            // Omitted description stays unset — never auto-inherits from the workspace root.
            description = optionalString(root, "description", "description");
        }

        boolean m2integration = parseM2Integration(root, workspaceRoot, inherits);
        InstallDestination installDestination = parseInstallDestination(root, workspaceRoot, inherits);

        Layout layout;
        if (isWorkspaceInherit(root, "layout") || (!workspaceRoot && !root.contains("layout"))) {
            inherits.add(ProjectInherit.LAYOUT);
            layout = Layout.AUTO;
        } else if (root.contains("layout")) {
            String layoutRaw = optionalString(root, "layout", "layout");
            try {
                layout = Layout.parse(layoutRaw);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(e.getMessage());
            }
        } else {
            layout = Layout.AUTO;
        }

        String jdk = jdkSpec.resolverSpec(java);
        if (jdk.isEmpty()) jdk = null;
        return new Project(
                group,
                name,
                version,
                jdk,
                java,
                kotlin,
                groovy,
                scala,
                sourcesMode,
                javadocMode,
                description,
                m2integration,
                installDestination,
                layout,
                inherits,
                jdkSpec,
                nodeSpec);
    }

    /**
     * {@code javadoc}: {@code false} → never, {@code "strict"} → every build with doclint on, {@code
     * true} → every build, absent → only when published or asked for.
     */
    static JavadocMode parseJavadocMode(@Nullable Object raw) {
        if (raw == null) return JavadocMode.PUBLISH;
        if (raw instanceof Boolean b) return b ? JavadocMode.LENIENT : JavadocMode.DISABLED;
        if (raw instanceof String str) {
            if ("strict".equalsIgnoreCase(str.trim())) return JavadocMode.STRICT;
            if (EnvValues.parseBool(str).orElse(true) == Boolean.FALSE) return JavadocMode.DISABLED;
        }
        return JavadocMode.LENIENT;
    }

    /**
     * {@code [m2] integration} (default true). A module may inherit the whole table ({@code [m2]
     * workspace = true}) or the key.
     */
    private static boolean parseM2Integration(
            TomlTable root, boolean workspaceRoot, java.util.EnumSet<ProjectInherit> inherits) {
        if (root.contains("m2") && !root.isTable("m2")) {
            throw new JkBuildParseException("`m2` must be a table — use [m2] with an integration key");
        }
        TomlTable m2 = root.isTable("m2") ? root.getTable("m2") : null;
        if (m2 != null) {
            for (String k : m2.keySet()) {
                if (!"integration".equals(k) && !"workspace".equals(k)) {
                    throw new JkBuildParseException("[m2] unknown key `" + k + "` — expected integration");
                }
            }
        }
        if (m2 != null && m2.contains("workspace") && isWorkspaceInherit(root, "m2")) {
            if (m2.contains("integration")) {
                throw new JkBuildParseException("[m2] sets both workspace = true and integration; drop one");
            }
            inherits.add(ProjectInherit.M2INTEGRATION);
            return true;
        }
        boolean present = m2 != null && m2.contains("integration");
        if ((m2 != null && isWorkspaceInherit(m2, "integration")) || (!workspaceRoot && !present)) {
            inherits.add(ProjectInherit.M2INTEGRATION);
            return true;
        }
        if (m2 == null || !present) return true;
        Boolean value = m2.get(List.of("integration")) instanceof Boolean b ? b : null;
        if (value == null) {
            throw new JkBuildParseException("[m2].integration must be true or false");
        }
        return value;
    }

    /**
     * {@code [install] destination}: {@code "m2"} or {@code "jk"}. Null when unset, which defers to the
     * machine's {@code config.toml} and then to {@code "m2"}. A member that names none inherits its
     * workspace root's.
     */
    private static @Nullable InstallDestination parseInstallDestination(
            TomlTable root, boolean workspaceRoot, java.util.EnumSet<ProjectInherit> inherits) {
        if (root.contains("install") && !root.isTable("install")) {
            throw new JkBuildParseException("`install` must be a table — use [install] with a target key");
        }
        TomlTable install = root.isTable("install") ? root.getTable("install") : null;
        boolean present = install != null && install.contains(InstallDestination.KEY);
        if ((install != null && isWorkspaceInherit(install, InstallDestination.KEY)) || (!workspaceRoot && !present)) {
            inherits.add(ProjectInherit.INSTALL_DESTINATION);
            return null;
        }
        if (install == null || !present) return null;
        String word = String.valueOf(install.get(List.of(InstallDestination.KEY)));
        return InstallDestination.parse(word)
                .orElseThrow(() -> new JkBuildParseException(
                        "[install] destination must be \"m2\" or \"jk\", not `" + word + "`"));
    }

    /**
     * String project field that may be concrete, {@code field.workspace = true}, or omitted (member
     * → inherit; root → error if required).
     */
    static String parseInheritableString(
            TomlTable root,
            String key,
            ProjectInherit inherit,
            java.util.EnumSet<ProjectInherit> inherits,
            boolean workspaceRoot) {
        String path = key;
        if (isWorkspaceInherit(root, key)) {
            if (workspaceRoot) {
                throw new JkBuildParseException(
                        "workspace root cannot use " + key + ".workspace = true; set " + path + " directly");
            }
            inherits.add(inherit);
            return Project.VERSION_FROM_WORKSPACE;
        }
        if (!root.contains(key)) {
            // Members: omit -> inherit. Roots: omit of group/version -> error.
            if (!workspaceRoot) {
                inherits.add(inherit);
                return Project.VERSION_FROM_WORKSPACE;
            }
            throw new JkBuildParseException("jk.toml is missing required key `" + path + "`" + strandedHint(root, key));
        }
        String value = root.get(List.of(key)) instanceof String s ? s : null;
        if (value == null) {
            throw new JkBuildParseException(path + " must be a string (e.g. \"1.0.0\") or `{ workspace = true }`");
        }
        if (value.isBlank()) {
            throw new JkBuildParseException(path + " must not be blank");
        }
        return value;
    }

    /**
     * Cargo-style {@code field.workspace = true} / {@code field = { workspace = true }} at the root
     * of {@code jk.toml}. Only the boolean {@code true} is legal; extra keys are rejected.
     */
    static boolean isWorkspaceInherit(TomlTable root, String key) {
        if (!root.contains(key) || !root.isTable(key)) return false;
        TomlTable t = root.getTable(key);
        // A table without workspace is the key's own table form (jdk = { vendor = … }), read by its parser.
        if (t != null && !t.contains("workspace")) return false;
        Boolean ws = t != null && t.get(List.of("workspace")) instanceof Boolean b ? b : null;
        if (t == null || !Boolean.TRUE.equals(ws)) {
            throw new JkBuildParseException(key + ".workspace can only be `true`; or set " + key + " directly");
        }
        for (String k : t.keySet()) {
            if (!"workspace".equals(k)) {
                throw new JkBuildParseException(key + " = { workspace = true } takes no other keys, not `" + k + "`");
            }
        }
        return true;
    }

    /**
     * The {@code jdk} table, the one spelling of a project's JDK vendor and version: {@code jdk = {
     * vendor = "corretto", version = "21", required = true }}. {@code vendor} and {@code version} are
     * each optional; {@code version} is a major ({@code "21"}), an exact build ({@code "25.0.4.1"})
     * or a floor within one major ({@code ">=25.0.5"}); {@code required} (default false) makes the
     * table binding for which JDK builds the project and which cached results serve it, where by
     * default it is a suggestion. The string forms ({@code "temurin-25"}, {@code "=temurin-25.0.4"})
     * are retired, with the table to write in their place. Absent → {@link ToolchainSpec#NONE}.
     * {@code javaLevel} is the module's {@code java}, which a {@code jdk} of a lower major contradicts.
     */
    static ToolchainSpec parseJdkToolchain(TomlTable root) {
        return parseJdkToolchain(root, 0);
    }

    static ToolchainSpec parseJdkToolchain(TomlTable root, int javaLevel) {
        if (!root.contains("jdk")) return ToolchainSpec.NONE;
        if (!root.isTable("jdk")) {
            RetiredKeys.refuse(root); // the string forms are retired, with the table to write
            throw JkBuildParseException.at(
                    root, "jdk", "jdk must be a table: jdk = { vendor = \"…\", version = \"…\" }");
        }
        TomlTable table = root.getTable("jdk");
        if (table == null) return ToolchainSpec.NONE;
        for (String k : table.keySet()) {
            if (!Set.of("vendor", "version", "required").contains(k)) {
                throw new JkBuildParseException("jdk takes vendor, version and required, not `" + k + "`");
            }
        }
        String vendor = table.contains("vendor") ? scalarSpec(table, "vendor", "jdk.vendor", "\"corretto\"") : null;
        String version = table.contains("version") ? scalarSpec(table, "version", "jdk.version", "\"21\"") : null;
        Object req = table.get("required");
        if (req != null && !(req instanceof Boolean))
            throw new JkBuildParseException("jdk.required must be true or false");
        boolean required = Boolean.TRUE.equals(req);
        ToolchainSpec spec;
        try {
            spec = JdkTable.spec(vendor, version, required);
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException(e.getMessage());
        }
        int major = version == null ? 0 : JdkTable.major(version);
        if (major > 0) requireSupportedMajor("jdk", major);
        if (javaLevel > 0 && major > 0 && major < javaLevel) {
            throw new JkBuildParseException("jdk.version = \"" + version + "\" is JDK " + major + ", below java = "
                    + javaLevel + ": a JDK cannot build a level above its own; raise the jdk version or lower java");
        }
        return spec;
    }

    /**
     * The Node.js version: {@code node = 24} at the root, or {@code version} inside a {@code [node]}
     * table (TOML allows one or the other). A major is a floor, a point release a suggestion, a
     * leading {@code =} an exact pin, {@code lts} the newest Active LTS; Node has no vendor axis.
     * {@code null} when this manifest declares none, or marks it {@code workspace = true}.
     */
    static @Nullable ToolchainSpec parseNodeToolchain(TomlTable root) {
        String raw;
        if (root.isTable("node")) {
            TomlTable node = root.getTable("node");
            if (node == null) return null;
            boolean inherit = node.contains("workspace");
            if (inherit && !Boolean.TRUE.equals(node.get("workspace"))) {
                throw new JkBuildParseException("[node] workspace must be `true` (the only legal value)");
            }
            raw = scalarSpec(node, "version", "[node] version", "24 or \"=24.21.0\"");
            if (inherit && raw != null) {
                throw new JkBuildParseException("[node] sets both version and workspace = true — keep one");
            }
        } else {
            raw = scalarSpec(root, "node", "node", "24 or \"=24.21.0\"");
        }
        if (raw == null) return null;
        if (raw.startsWith("=") && raw.indexOf('.') < 0) {
            throw new JkBuildParseException(
                    "node = \"" + raw + "\": an exact pin needs a full version, e.g. \"=24.21.0\"");
        }
        ToolchainSpec spec;
        try {
            spec = ToolchainSpec.parse("node", raw);
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException(e.getMessage());
        }
        if (!spec.vendor().isEmpty()) {
            throw new JkBuildParseException("node takes a version, not a vendor: node = \"" + raw
                    + "\" — write node = 24, \"24.21.0\", \"=24.21.0\" or \"lts\"");
        }
        String v = spec.version();
        boolean lts = "lts".equalsIgnoreCase(v);
        if (!v.isEmpty() && !lts && (ToolchainSpec.isKeyword(v) || Project.majorOf(v) <= 0)) {
            throw new JkBuildParseException(
                    "node must be a version or lts, e.g. 24, \"=24.21.0\" or \"lts\", got \"" + raw + "\"");
        }
        return spec;
    }

    /**
     * {@code [native].graal} / {@code graal-vendor} / {@code graal-version}; {@code "native"} ≡
     * {@code "graalvm"}. Absent → {@link ToolchainSpec#NONE}.
     */
    static ToolchainSpec parseGraalToolchain(TomlTable native_) {
        return parseToolchain(native_, "graal", "[native].graal", "\"graalvm-25\", \"25\", or \"native\"");
    }

    /**
     * Shared reader for the three keys that describe one toolchain — {@code <key>},
     * {@code <key>-vendor}, {@code <key>-version}. Point releases are welcome now: they are the
     * whole point of an {@code =} pin, and a bare one simply records what built the lock.
     */
    static ToolchainSpec parseToolchain(TomlTable table, String key, String pathLabel, String exampleHint) {
        String combined = scalarSpec(table, key, pathLabel, exampleHint);
        String vendor = scalarSpec(table, key + "-vendor", pathLabel + "-vendor", "\"temurin\"");
        String version = scalarSpec(table, key + "-version", pathLabel + "-version", "25 or \"25.0.4\"");
        try {
            return ToolchainSpec.of(pathLabel, combined, vendor, version);
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException(e.getMessage());
        }
    }

    /**
     * {@code java} accepts either an unquoted TOML integer or a quoted numeric string
     * (coerced). Absent → {@code 0} ({@code javaRelease} falls back to the {@code jdk} major).
     */
    static int parseJavaRelease(TomlTable root) {
        if (!root.contains("java")) return 0;
        Object raw = root.get("java");
        long value;
        if (raw instanceof Long l) {
            value = l;
        } else if (raw instanceof String s) {
            try {
                value = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw JkBuildParseException.at(root, "java", "java must be an integer, got: \"" + s + "\"");
            }
        } else if (raw instanceof TomlTable) {
            throw JkBuildParseException.at(
                    root,
                    "java",
                    "java is the language level, a plain integer (java = 25);"
                            + " a JDK vendor or version goes on jdk = { vendor = \"corretto\", version = \"21\" }");
        } else {
            throw JkBuildParseException.at(root, "java", "java must be an integer");
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw JkBuildParseException.at(root, "java", "java out of range: " + value);
        }
        return (int) value;
    }

    /** One toolchain key as text: unquoted int or string. Null when absent/blank. */
    private static @Nullable String scalarSpec(TomlTable table, String key, String pathLabel, String exampleHint) {
        if (!table.contains(key)) return null;
        Object raw = table.get(key);
        String spec;
        if (raw instanceof Long l) {
            spec = Long.toString(l);
        } else if (raw instanceof String s) {
            spec = s.trim();
        } else {
            throw new JkBuildParseException(pathLabel + " must be a string, e.g. " + exampleHint);
        }
        return spec.isEmpty() ? null : spec;
    }

    /**
     * {@code kotlin} is a Kotlin compiler version selector (string) in the dependency version
     * grammar: bare {@code 2.3.21} pins, {@code ^2.3} floats within the line. Absent → {@code null}
     * (a Java project).
     */
    static @Nullable VersionSelector parseKotlinVersion(TomlTable root) {
        if (!root.contains("kotlin")) return null;
        String raw = root.get(List.of("kotlin")) instanceof String s ? s : null;
        if (raw == null) {
            throw new JkBuildParseException("kotlin must be a version string, e.g. \"2.3.21\"");
        }
        if (raw.isBlank()) return null;
        return ManifestSelectors.checked(VersionSelector.parse(raw), root, "kotlin", "kotlin");
    }

    /**
     * {@code groovy} is a Groovy compiler version selector (string) in the dependency version
     * grammar: bare {@code 5.0.4} pins, {@code ^5} floats within the line. Absent → {@code null}
     * (not a Groovy project).
     */
    static @Nullable VersionSelector parseGroovyVersion(TomlTable root) {
        if (!root.contains("groovy")) return null;
        String raw = root.get(List.of("groovy")) instanceof String s ? s : null;
        if (raw == null) {
            throw new JkBuildParseException("groovy must be a version string, e.g. \"5.0.4\"");
        }
        if (raw.isBlank()) return null;
        return ManifestSelectors.checked(VersionSelector.parse(raw), root, "groovy", "groovy");
    }

    /**
     * {@code scala} is a Scala compiler version selector (string) in the dependency version
     * grammar: bare {@code 3.8.4} pins, {@code ^3} floats within the line. Absent → {@code null}
     * (not a Scala project).
     */
    static @Nullable VersionSelector parseScalaVersion(TomlTable root) {
        if (!root.contains("scala")) return null;
        String raw = root.get(List.of("scala")) instanceof String s ? s : null;
        if (raw == null) {
            throw new JkBuildParseException("scala must be a version string, e.g. \"3.8.4\"");
        }
        if (raw.isBlank()) return null;
        return ManifestSelectors.checked(VersionSelector.parse(raw), root, "scala", "scala");
    }

    /**
     * jk runs on JDK 17 and above (LTS + latest). Reject an older {@code jdk} at parse time so users
     * learn the constraint up front instead of in the middle of a resolve.
     */
    static void requireSupportedMajor(String path, int value) {
        if (value == 0) return;
        if (value < JavaRelease.FLOOR) {
            throw new JkBuildParseException(
                    path + " = " + value + " is not supported; JumpKick needs JDK " + JavaRelease.FLOOR + " or later");
        }
    }

    /**
     * {@code java} is a {@code --release} the toolchain JDK cross-compiles for: a level below jk's
     * floor parses, and the build says once what it is doing with it; a level no current javac has a
     * release for is refused.
     */
    static void requireCompilableRelease(int value) {
        if (value == 0) return;
        if (value < JavaRelease.OLDEST) {
            throw new JkBuildParseException(
                    "java = " + value + " is too old for javac; the oldest is " + JavaRelease.OLDEST);
        }
    }
}
