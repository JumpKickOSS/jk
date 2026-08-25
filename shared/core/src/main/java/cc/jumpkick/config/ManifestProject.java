// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;

import cc.jumpkick.model.Layout;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.ProjectInherit;
import cc.jumpkick.model.SourcesMode;
import cc.jumpkick.model.VersionSelector;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
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
            "java",
            "kotlin",
            "groovy",
            "scala",
            "sources",
            "description",
            "layout",
            "id",
            "module");

    private ManifestProject() {}

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
            throw new JkBuildParseException(
                    "name cannot use workspace inheritance — every module must declare its own name");
        }
        if (!root.contains("name")) {
            throw new JkBuildParseException("jk.toml is missing required key `name`");
        }
        java.util.EnumSet<ProjectInherit> inherits = java.util.EnumSet.noneOf(ProjectInherit.class);

        String name = requireString(root, "name", "name");

        String group = parseInheritableString(
                root, "group", ProjectInherit.GROUP, inherits, workspaceRoot, /* requiredWhenRootOrStandalone */ true);

        String version = parseInheritableString(
                root,
                "version",
                ProjectInherit.VERSION,
                inherits,
                workspaceRoot,
                /* requiredWhenRootOrStandalone */ true);

        String jdk;
        if (isWorkspaceInherit(root, "jdk") || (!workspaceRoot && !root.contains("jdk"))) {
            inherits.add(ProjectInherit.JDK);
            jdk = null;
        } else {
            jdk = parseJdkSpec(root);
        }

        int java;
        if (isWorkspaceInherit(root, "java") || (!workspaceRoot && !root.contains("java"))) {
            inherits.add(ProjectInherit.JAVA);
            java = 0;
        } else {
            java = parseJavaRelease(root);
            requireSupportedMajor("java", java);
        }

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

        String description;
        if (isWorkspaceInherit(root, "description")) {
            inherits.add(ProjectInherit.DESCRIPTION);
            description = null;
        } else {
            // Omitted description stays unset — never auto-inherits from the workspace root.
            description = root.getString("description");
        }

        M2Flags m2 = parseM2(root, workspaceRoot, inherits);
        boolean m2integration = m2.integration;
        boolean m2install = m2.install;

        Layout layout;
        if (isWorkspaceInherit(root, "layout") || (!workspaceRoot && !root.contains("layout"))) {
            inherits.add(ProjectInherit.LAYOUT);
            layout = Layout.AUTO;
        } else if (root.contains("layout")) {
            String layoutRaw = root.getString("layout");
            try {
                layout = Layout.parse(layoutRaw);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(e.getMessage());
            }
        } else {
            layout = Layout.AUTO;
        }

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
                description,
                m2integration,
                m2install,
                layout,
                inherits);
    }

    private record M2Flags(boolean integration, boolean install) {}

    /**
     * {@code [m2] integration} / {@code [m2] install} (both default true). A module may inherit
     * the whole table ({@code [m2] workspace = true}) or either key.
     */
    private static M2Flags parseM2(TomlTable root, boolean workspaceRoot, java.util.EnumSet<ProjectInherit> inherits) {
        if (root.contains("m2integration") || root.contains("m2install")) {
            throw new JkBuildParseException(
                    "m2integration / m2install moved under [m2] — use `integration` and `install`");
        }
        if (root.contains("m2") && !root.isTable("m2")) {
            throw new JkBuildParseException("`m2` must be a table — use [m2] with integration/install keys");
        }
        TomlTable m2 = root.isTable("m2") ? root.getTable("m2") : null;
        if (m2 != null) {
            for (String k : m2.keySet()) {
                if (!"integration".equals(k) && !"install".equals(k) && !"workspace".equals(k)) {
                    throw new JkBuildParseException("[m2] unknown key `" + k + "` — expected integration, install");
                }
            }
        }
        if (m2 != null && m2.contains("workspace") && isWorkspaceInherit(root, "m2")) {
            if (m2.contains("integration") || m2.contains("install")) {
                throw new JkBuildParseException(
                        "[m2] workspace = true cannot be combined with explicit integration/install"
                                + " — drop one (JK-2323)");
            }
            inherits.add(ProjectInherit.M2INTEGRATION);
            inherits.add(ProjectInherit.M2INSTALL);
            return new M2Flags(true, true);
        }
        return new M2Flags(
                parseM2Bool(root, "integration", workspaceRoot, ProjectInherit.M2INTEGRATION, inherits),
                parseM2Bool(root, "install", workspaceRoot, ProjectInherit.M2INSTALL, inherits));
    }

    private static boolean parseM2Bool(
            TomlTable root,
            String key,
            boolean workspaceRoot,
            ProjectInherit inherit,
            java.util.EnumSet<ProjectInherit> inherits) {
        // Guard the type: a top-level `m2 = "yes"` must surface as a clean parse error, not a raw
        // tomlj type exception from getTable (JK-2323).
        TomlTable m2 = root.isTable("m2") ? root.getTable("m2") : null;
        boolean present = m2 != null && m2.contains(key);
        if ((m2 != null && isWorkspaceInherit(m2, key)) || (!workspaceRoot && !present)) {
            inherits.add(inherit);
            return true;
        }
        if (!present) return true;
        Boolean value = m2.getBoolean(key);
        if (value == null) {
            throw new JkBuildParseException("[m2]." + key + " must be true or false");
        }
        return value;
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
            boolean workspaceRoot,
            boolean required) {
        String path = key;
        if (isWorkspaceInherit(root, key)) {
            if (workspaceRoot) {
                throw new JkBuildParseException("workspace root must set a concrete " + path + " (`" + key
                        + ".workspace = true` is only valid" + " on workspace modules)");
            }
            inherits.add(inherit);
            return Project.VERSION_FROM_WORKSPACE;
        }
        if (!root.contains(key)) {
            if (workspaceRoot || required) {
                // Members: omit → inherit. Roots: omit of group/version → error.
                if (!workspaceRoot) {
                    inherits.add(inherit);
                    return Project.VERSION_FROM_WORKSPACE;
                }
            }
            if (required) {
                throw new JkBuildParseException("jk.toml is missing required key `" + path + "`");
            }
            return null;
        }
        String value = root.getString(key);
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
        Boolean ws = t.getBoolean("workspace");
        if (!Boolean.TRUE.equals(ws)) {
            throw new JkBuildParseException(
                    key + ".workspace must be `true` (the only legal value), or set " + key + " to a concrete value");
        }
        for (String k : t.keySet()) {
            if (!"workspace".equals(k)) {
                throw new JkBuildParseException(key
                        + " with workspace inheritance must only set `workspace = true` (unexpected key `"
                        + k
                        + "`)");
            }
        }
        return true;
    }

    /**
     * {@code jdk}: vendor+major, bare major, unquoted int, or keyword
     * ({@code lts}/{@code stable}/{@code latest}/{@code native}). Point releases rejected.
     * Absent/blank → null.
     */
    static String parseJdkSpec(TomlTable root) {
        String spec = parseVersionSpec(root, "jdk", "jdk", "\"temurin-25\" or \"25\"");
        if (spec == null || isVersionKeyword(spec)) return spec;
        int major = Project.majorOf(spec);
        if (major == 0) {
            throw new JkBuildParseException(
                    "jdk = \"" + spec + "\" must include a major version (e.g. \"temurin-25\" or \"25\")");
        }
        requireSupportedMajor("jdk", major);
        return spec;
    }

    /**
     * {@code [native].graal}: same shape as {@code jdk}; {@code "native"} ≡ {@code "graalvm"}.
     * Point releases rejected. Absent/blank → null.
     */
    static String parseGraalSpec(TomlTable native_) {
        return parseVersionSpec(native_, "graal", "[native].graal", "\"graalvm-25\", \"25\", or \"native\"");
    }

    /**
     * Shared {@code jdk}/{@code graal} spec parser: int or string; keywords pass through; point
     * releases rejected. Null when absent/blank.
     */
    static String parseVersionSpec(TomlTable table, String key, String pathLabel, String exampleHint) {
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
        if (spec.isEmpty()) return null;
        if (isVersionKeyword(spec)) return spec;
        if (Project.hasPointRelease(spec)) {
            throw new JkBuildParseException(pathLabel
                    + " = \""
                    + spec
                    + "\" must not pin a point release — use \"<vendor>-<major>\" or "
                    + "\"<major>\" (e.g. "
                    + exampleHint
                    + "); jk keeps the patch current.");
        }
        return spec;
    }

    /** Version keywords (kept local so :core does not depend on :toolchain-jdk). */
    static boolean isVersionKeyword(String spec) {
        String norm = spec.toLowerCase(Locale.ROOT);
        return norm.equals("lts") || norm.equals("stable") || norm.equals("latest") || norm.equals("native");
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
                throw new JkBuildParseException("java must be an integer, got: \"" + s + "\"");
            }
        } else {
            throw new JkBuildParseException("java must be an integer");
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new JkBuildParseException("java out of range: " + value);
        }
        return (int) value;
    }

    /**
     * {@code kotlin} is a Kotlin compiler version selector (string), parsed the same way as a
     * floating dependency version: bare {@code 2.3.21} → caret, {@code =2.3.21} pins. Absent → {@code
     * null} (a Java project).
     */
    static VersionSelector parseKotlinVersion(TomlTable root) {
        if (!root.contains("kotlin")) return null;
        String raw = root.getString("kotlin");
        if (raw == null) {
            throw new JkBuildParseException("kotlin must be a version string, e.g. \"2.3.21\"");
        }
        if (raw.isBlank()) return null;
        return VersionSelector.parseFloating(raw);
    }

    /**
     * {@code groovy} is a Groovy compiler version selector (string), parsed the same way as
     * a floating dependency version: bare {@code 5.0.4} → caret, {@code =5.0.4} pins. Absent →
     * {@code null} (not a Groovy project).
     */
    static VersionSelector parseGroovyVersion(TomlTable root) {
        if (!root.contains("groovy")) return null;
        String raw = root.getString("groovy");
        if (raw == null) {
            throw new JkBuildParseException("groovy must be a version string, e.g. \"5.0.4\"");
        }
        if (raw.isBlank()) return null;
        return VersionSelector.parseFloating(raw);
    }

    /**
     * {@code scala} is a Scala compiler version selector (string), parsed the same way as a
     * floating dependency version: bare {@code 3} → caret, {@code =3.8.4} pins. Absent →
     * {@code null} (not a Scala project).
     */
    static VersionSelector parseScalaVersion(TomlTable root) {
        if (!root.contains("scala")) return null;
        String raw = root.getString("scala");
        if (raw == null) {
            throw new JkBuildParseException("scala must be a version string, e.g. \"3\"");
        }
        if (raw.isBlank()) return null;
        return VersionSelector.parseFloating(raw);
    }

    /**
     * jk only supports JDK 17 and above (LTS + latest). Reject any older value at parse time so users
     * learn the constraint up front instead of in the middle of a resolve.
     */
    static void requireSupportedMajor(String path, int value) {
        if (value == 0) return;
        if (value < 17) {
            throw new JkBuildParseException(path
                    + " = "
                    + value
                    + " is not supported — jk targets JDK 17 and above "
                    + "(LTS: 17, 21, 25, … plus the latest release).");
        }
    }
}
